(ns otel.exporter.chdb.explorer
  "Bounded, read-only distributions over the embedded ClickStack-shaped
  telemetry tables. Caller data is used only as query parameters; every table,
  timestamp column, and explored expression comes from a closed allowlist."
  ;; db.jdbc must load before jdbc.core is compiled: it installs Jolt's
  ;; java.sql class shims, including ResultSet. Own that ordering here so a
  ;; standalone explorer consumer needs no undocumented bootstrap require.
  (:require [db.jdbc]
            [clojure.string :as str]
            [jdbc.core :as jdbc]
            [otel.context :as context]
            [otel.exporter.chdb.attribute-projection :as attribute-projection]))

(def max-time-range-nanos
  "Largest accepted half-open query window (24 hours)."
  (* 24 60 60 1000000000))

(def max-result-limit
  "Largest number of buckets returned for each requested field."
  100)

(def max-field-count
  "Largest number of fields accepted by one request."
  8)

(def max-text-length
  "Largest UTF-8 character prefix returned for an attribute value."
  256)

(def default-text-length 128)

(def ^:private typed-span-request-keys
  #{:end-unix-nano :keys :limit :max-text-length :signal :start-unix-nano})
(def ^:private safe-typed-column #"a[sv]_sp_[a-z0-9_]+_[0-9a-f]{16}")

(def max-counter-source-points
  "Largest raw cumulative-counter snapshot set accepted by one request."
  10000)

(def max-counter-source-bytes
  "Largest chDB result payload accepted while loading counter snapshots (64 MiB)."
  67108864)

(def max-counter-scan-rows 100000)
(def max-counter-scan-bytes 67108864)
(def max-counter-memory-bytes 134217728)
(def max-counter-query-seconds 5)

(def max-histogram-source-points max-counter-source-points)
(def max-histogram-source-bytes max-counter-source-bytes)
(def max-histogram-scan-rows max-counter-scan-rows)
(def max-histogram-scan-bytes max-counter-scan-bytes)
(def max-histogram-memory-bytes max-counter-memory-bytes)
(def max-histogram-query-seconds max-counter-query-seconds)

(def ^:private metric-series-request-keys
  #{:metric-kind :metric-name :group-by :bucket :aggregates
    :start-unix-nano :end-unix-nano :limit :max-text-length})

(def ^:private metric-series-tables
  {:gauge "otel_metrics_gauge"
   :sum "otel_metrics_sum"
   :histogram "otel_metrics_histogram"})

(def ^:private cumulative-counter-request-keys
  #{:metric-kind :temporality :monotonic? :metric-name :group-by :bucket
    :aggregates :start-unix-nano :end-unix-nano :limit :max-text-length})

(def ^:private cumulative-counter-aggregates [:increase :rate])

(def ^:private cumulative-histogram-request-keys
  #{:metric-kind :temporality :metric-name :group-by :bucket :aggregates
    :start-unix-nano :end-unix-nano :limit :max-text-length})

(def ^:private cumulative-histogram-aggregates
  [:count :sum :avg :p50 :p95 :p99])

(def ^:private histogram-quantile-presets
  {:p50 {:quantile 0.50 :numerator 1 :denominator 2}
   :p95 {:quantile 0.95 :numerator 19 :denominator 20}
   :p99 {:quantile 0.99 :numerator 99 :denominator 100}})

(def ^:private histogram-quantiles
  (into {} (map (fn [[name preset]] [name (:quantile preset)]))
        histogram-quantile-presets))

(def ^:private metric-series-group-fields
  {:service-name {:expression "ServiceName" :alias "servicename"
                  :result-key :servicename :output-key :service-name}
   :metric-unit {:expression "MetricUnit" :alias "metricunit"
                 :result-key :metricunit :output-key :metric-unit}
   :scope-name {:expression "ScopeName" :alias "scopename"
                :result-key :scopename :output-key :scope-name}
   :deployment-environment
   {:expression "ResourceAttributes['deployment.environment.name']"
    :alias "deploymentenvironment" :result-key :deploymentenvironment
    :output-key :deployment-environment}})

(def ^:private metric-series-group-order
  [:service-name :metric-unit :scope-name :deployment-environment])

(def ^:private metric-series-buckets
  {:none nil
   :1m (* 60 1000000000)
   :5m (* 5 60 1000000000)
   :15m (* 15 60 1000000000)
   :1h (* 60 60 1000000000)})

(def ^:private metric-series-bucket-order [:none :1m :5m :15m :1h])
(def ^:private scalar-aggregate-order
  [:count :sum :min :max :avg :p50 :p95 :p99])
(def ^:private histogram-aggregate-order [:count :sum :avg])

(def ^:private scalar-metric-aggregates
  {:count "count() AS count"
   :sum "sum(Value) AS sum"
   :min "min(Value) AS min"
   :max "max(Value) AS max"
   :avg "avg(Value) AS avg"
   :p50 "quantileTDigest(0.50)(Value) AS p50"
   :p95 "quantileTDigest(0.95)(Value) AS p95"
   :p99 "quantileTDigest(0.99)(Value) AS p99"})

(def ^:private histogram-aggregates
  {:count "sum(Count) AS count"
   :sum "sum(Sum) AS sum"
   :avg "if(sum(Count) = 0, 0.0, sum(Sum) / sum(Count)) AS avg"})

(defn supported-metric-series
  "Return the closed choices accepted by metric-series. Percentiles summarize
  stored gauge or sum sample values with ClickHouse's bounded-memory t-digest;
  they do not reconstruct OTLP histogram bucket quantiles."
  []
  {:metric-kinds [:gauge :sum :histogram]
   :group-by metric-series-group-order
   :buckets metric-series-bucket-order
   :aggregates {:gauge scalar-aggregate-order
                :sum scalar-aggregate-order
                :histogram histogram-aggregate-order}})

(defn supported-cumulative-counter-series
  "Return the closed choices accepted by cumulative-counter-series. The
  required provenance is deliberately data, not an inferred default."
  []
  {:metric-kinds [:sum]
   :temporalities [:cumulative]
   :monotonic-values [true]
   :group-by metric-series-group-order
   :buckets metric-series-bucket-order
   :aggregates cumulative-counter-aggregates})

(defn supported-cumulative-histogram-series
  "Return the closed choices accepted by cumulative-histogram-series. The
  required cumulative temporality is explicit rather than inferred."
  []
  {:metric-kinds [:histogram]
   :temporalities [:cumulative]
   :group-by metric-series-group-order
   :buckets metric-series-bucket-order
   :aggregates cumulative-histogram-aggregates
   :quantiles histogram-quantiles})

(def ^:private metric-source
  "(SELECT TimeUnix, ServiceName, MetricName, MetricUnit, ScopeName,
           ResourceAttributes, Attributes FROM otel_metrics_gauge
     UNION ALL
     SELECT TimeUnix, ServiceName, MetricName, MetricUnit, ScopeName,
           ResourceAttributes, Attributes FROM otel_metrics_sum
     UNION ALL
     SELECT TimeUnix, ServiceName, MetricName, MetricUnit, ScopeName,
           ResourceAttributes, Attributes FROM otel_metrics_histogram) AS telemetry_metrics")

(def ^:private signals
  {:spans
   {:source "otel_traces"
    :time-nanos "toUnixTimestamp64Nano(Timestamp)"
    :field-order [:service-name :span-name :span-kind :status-code :scope-name
                  :http-request-method :http-response-status-code
                  :deployment-environment]
    :fields {:service-name "ServiceName"
             :span-name "SpanName"
             :span-kind "SpanKind"
             :status-code "StatusCode"
             :scope-name "ScopeName"
             :http-request-method "SpanAttributes['http.request.method']"
             :http-response-status-code "SpanAttributes['http.response.status_code']"
             :deployment-environment
             "ResourceAttributes['deployment.environment.name']"}}

   :logs
   {:source "otel_logs"
    :time-nanos "toUnixTimestamp64Nano(Timestamp)"
    :field-order [:service-name :severity-text :event-name :scope-name
                  :deployment-environment]
    :fields {:service-name "ServiceName"
             :severity-text "SeverityText"
             :event-name "EventName"
             :scope-name "ScopeName"
             :deployment-environment
             "ResourceAttributes['deployment.environment.name']"}}

   :metrics
   {:source metric-source
    ;; The pinned ClickStack-shaped metric tables store DateTime seconds, not
    ;; DateTime64. Convert that exact stored second to the common nanos unit.
    :time-nanos "toInt64(toUnixTimestamp(TimeUnix)) * 1000000000"
    :field-order [:service-name :metric-name :metric-unit :scope-name
                  :deployment-environment]
    :fields {:service-name "ServiceName"
             :metric-name "MetricName"
             :metric-unit "MetricUnit"
             :scope-name "ScopeName"
             :deployment-environment
             "ResourceAttributes['deployment.environment.name']"}}})

(defn- fail! [type message data]
  (throw (ex-info message
                  (assoc data
                         :attribute-explorer/error true
                         :type type))))

(defn- signal-config! [signal]
  (or (get signals signal)
      (fail! ::unsupported-signal
             "attribute explorer signal must be :spans, :logs, or :metrics"
             {:signal signal :supported-signals [:spans :logs :metrics]})))

(defn supported-fields
  "Return the closed field allowlist, either for every signal or for one signal.
  An unsupported signal throws the same structured error as top-values."
  ([]
   (into {} (map (fn [[signal config]]
                   [signal (:field-order config)])) signals))
  ([signal]
   (:field-order (signal-config! signal))))

(defn- validate-instant! [name value]
  (when-not (and (integer? value)
                 (<= 0 value 9223372036854775807))
    (fail! ::invalid-time
           "attribute explorer times must be non-negative signed Int64 epoch nanoseconds"
           {:parameter name :value value
            :minimum 0 :maximum 9223372036854775807}))
  value)

(defn- validate-fields! [config fields]
  ;; A vector makes validation itself bounded: a caller cannot hand us an
  ;; unrealized or infinite sequence that must be traversed to find the cap.
  (when-not (vector? fields)
    (fail! ::invalid-fields
           "attribute explorer :fields must be a non-empty vector"
           {:fields fields :maximum max-field-count}))
  (when (or (empty? fields) (> (count fields) max-field-count))
    (fail! ::invalid-fields
           "attribute explorer :fields must contain between 1 and 8 entries"
           {:fields fields :actual (count fields) :minimum 1
            :maximum max-field-count}))
  (when-not (= (count fields) (count (set fields)))
    (fail! ::duplicate-fields
           "attribute explorer :fields must not contain duplicates"
           {:fields fields}))
  (doseq [field fields]
    (when-not (contains? (:fields config) field)
      (fail! ::unsupported-field
             "attribute explorer field is not supported for this signal"
             {:field field :supported-fields (:field-order config)})))
  fields)

(defn- validate-positive-cap! [parameter value maximum]
  (when-not (and (integer? value) (<= 1 value maximum))
    (fail! ::invalid-bound
           "attribute explorer bound must be a positive integer within its hard cap"
           {:parameter parameter :value value :minimum 1 :maximum maximum}))
  value)

(defn- validate-typed-span-request! [connection descriptor-set options]
  (when (nil? connection)
    (fail! ::invalid-connection
           "typed span values require the installed target connection"
           {:connection connection}))
  (when-not (map? options)
    (fail! ::invalid-request
           "typed span values request must be a map" {:request options}))
  (when-let [unknown (seq (remove typed-span-request-keys (keys options)))]
    (fail! ::unsupported-request-key
           "typed span values request contains unsupported keys"
           {:keys (vec (sort-by str unknown))}))
  (when-not (= :spans (:signal options))
    (fail! ::unsupported-typed-signal
           "typed attribute query selection supports spans only"
           {:signal (:signal options) :supported-signals [:spans]}))
  (let [requested (:keys options)
        fields (attribute-projection/confirmed-span-fields
                descriptor-set connection)
        by-key (into {} (map (juxt :key identity) fields))]
    (when-not (and (vector? requested)
                   (<= 1 (count requested) max-field-count)
                   (every? #(and (string? %) (<= 1 (count %) max-text-length)
                                 (not (str/blank? %)))
                           requested))
      (fail! ::invalid-typed-keys
             "typed span keys must be a bounded non-empty string vector"
             {:keys requested :maximum max-field-count}))
    (when-not (= (count requested) (count (set requested)))
      (fail! ::duplicate-typed-keys
             "typed span keys must not contain duplicates" {:keys requested}))
    (doseq [key requested]
      (when-not (contains? by-key key)
        (fail! ::unknown-typed-key
               "typed span key is not approved by this descriptor capability"
               {:key key :approved-keys (mapv :key fields)})))
    (let [start (validate-instant! :start-unix-nano
                                   (:start-unix-nano options))
          end (validate-instant! :end-unix-nano (:end-unix-nano options))]
      (when-not (< start end)
        (fail! ::invalid-time-window
               "typed span values require a non-empty increasing window"
               {:start-unix-nano start :end-unix-nano end}))
      (when (> (- end start) max-time-range-nanos)
        (fail! ::time-range-too-large
               "typed span values time window exceeds the 24 hour hard cap"
               {:actual (- end start) :maximum max-time-range-nanos}))
      {:fields (mapv by-key requested)
       :start start :end end
       :limit (validate-positive-cap! :limit (:limit options) max-result-limit)
       :text-length
       (validate-positive-cap! :max-text-length
                               (get options :max-text-length default-text-length)
                               max-text-length)})))

(defn- validate-metric-name! [value]
  (when-not (and (string? value)
                 (<= 1 (count value) max-text-length)
                 (not (str/blank? value)))
    (fail! ::invalid-metric-name
           "metric series requires a nonblank metric name of at most 256 characters"
           {:reason :invalid-metric-name :maximum max-text-length}))
  value)

(defn- validate-closed-vector!
  [parameter values allowed maximum]
  (when-not (vector? values)
    (fail! ::invalid-series-vector
           "metric series selections must be bounded vectors"
           {:parameter parameter :value values :maximum maximum}))
  (when (> (count values) maximum)
    (fail! ::invalid-series-vector
           "metric series selection exceeds its hard cap"
           {:parameter parameter :actual (count values) :maximum maximum}))
  (when-not (= (count values) (count (set values)))
    (fail! ::duplicate-series-choice
           "metric series selections must not contain duplicates"
           {:parameter parameter :value values}))
  (let [allowed-set (set allowed)]
    (doseq [value values]
      (when-not (contains? allowed-set value)
        (fail! ::unsupported-series-choice
               "metric series selection contains an unsupported choice"
               {:parameter parameter :value value
                :supported allowed}))))
  values)

(defn- metric-aggregate-map [metric-kind]
  (if (= :histogram metric-kind)
    histogram-aggregates scalar-metric-aggregates))

(defn- validate-cumulative-counter-request! [connection options]
  (when (nil? connection)
    (fail! ::invalid-connection
           "cumulative counter series requires a connection"
           {:connection connection}))
  (when-not (map? options)
    (fail! ::invalid-request
           "cumulative counter series request must be a map"
           {:request options}))
  (when-let [unknown (seq (remove cumulative-counter-request-keys
                                  (keys options)))]
    (fail! ::unsupported-request-key
           "cumulative counter series request contains unsupported keys"
           {:keys (vec (sort-by str unknown))}))
  (let [{:keys [metric-kind temporality monotonic? metric-name
                start-unix-nano end-unix-nano limit]
         :as options} options
        group-by (get options :group-by [])
        bucket (get options :bucket :none)
        aggregates (get options :aggregates cumulative-counter-aggregates)
        text-length (get options :max-text-length default-text-length)
        start (validate-instant! :start-unix-nano start-unix-nano)
        end (validate-instant! :end-unix-nano end-unix-nano)]
    (when-not (and (= :sum metric-kind)
                   (= :cumulative temporality)
                   (true? monotonic?))
      (fail! ::unsupported-counter-provenance
             "counter increase/rate requires :sum, :cumulative, monotonic true provenance"
             {:metric-kind metric-kind :temporality temporality
              :monotonic? monotonic?
              :required {:metric-kind :sum :temporality :cumulative
                         :monotonic? true}}))
    (validate-metric-name! metric-name)
    (when-not (< start end)
      (fail! ::invalid-time-window
             "cumulative counter time window must be non-empty and increasing"
             {:start-unix-nano start :end-unix-nano end}))
    (when (> (- end start) max-time-range-nanos)
      (fail! ::time-range-too-large
             "cumulative counter time window exceeds the 24 hour hard cap"
             {:actual (- end start) :maximum max-time-range-nanos}))
    (validate-closed-vector! :group-by group-by metric-series-group-order
                             max-field-count)
    (when-not (contains? metric-series-buckets bucket)
      (fail! ::unsupported-bucket
             "cumulative counter bucket is not supported"
             {:bucket bucket :supported metric-series-bucket-order}))
    (when (empty? aggregates)
      (fail! ::invalid-series-vector
             "cumulative counter series must select at least one aggregate"
             {:parameter :aggregates :value aggregates}))
    (validate-closed-vector! :aggregates aggregates
                             cumulative-counter-aggregates
                             (count cumulative-counter-aggregates))
    {:metric-kind metric-kind :temporality temporality :monotonic? monotonic?
     :metric-name metric-name :group-by group-by :bucket bucket
     :bucket-nanos (get metric-series-buckets bucket)
     :aggregates aggregates :start start :end end
     :limit (validate-positive-cap! :limit limit max-result-limit)
     :text-length (validate-positive-cap! :max-text-length text-length
                                          max-text-length)}))

(defn- validate-cumulative-histogram-request! [connection options]
  (when (nil? connection)
    (fail! ::invalid-connection
           "cumulative histogram series requires a connection"
           {:connection connection}))
  (when-not (map? options)
    (fail! ::invalid-request
           "cumulative histogram series request must be a map"
           {:request-kind :cumulative-histogram}))
  (when-let [unknown (seq (remove cumulative-histogram-request-keys
                                  (keys options)))]
    (fail! ::unsupported-request-key
           "cumulative histogram series request contains unsupported keys"
           {:keys (vec (sort-by str unknown))}))
  (let [{:keys [metric-kind temporality metric-name start-unix-nano
                end-unix-nano limit]
         :as options} options
        group-by (get options :group-by [])
        bucket (get options :bucket :none)
        aggregates (get options :aggregates cumulative-histogram-aggregates)
        text-length (get options :max-text-length default-text-length)
        start (validate-instant! :start-unix-nano start-unix-nano)
        end (validate-instant! :end-unix-nano end-unix-nano)]
    (when-not (and (= :histogram metric-kind)
                   (= :cumulative temporality))
      (fail! ::unsupported-histogram-provenance
             "histogram reconstruction requires explicit cumulative histogram provenance"
             {:metric-kind metric-kind :temporality temporality
              :required {:metric-kind :histogram :temporality :cumulative}}))
    (validate-metric-name! metric-name)
    (when-not (< start end)
      (fail! ::invalid-time-window
             "cumulative histogram time window must be non-empty and increasing"
             {:start-unix-nano start :end-unix-nano end}))
    (when (> (- end start) max-time-range-nanos)
      (fail! ::time-range-too-large
             "cumulative histogram time window exceeds the 24 hour hard cap"
             {:actual (- end start) :maximum max-time-range-nanos}))
    (validate-closed-vector! :group-by group-by metric-series-group-order
                             max-field-count)
    (when-not (contains? metric-series-buckets bucket)
      (fail! ::unsupported-bucket
             "cumulative histogram bucket is not supported"
             {:bucket bucket :supported metric-series-bucket-order}))
    (when (empty? aggregates)
      (fail! ::invalid-series-vector
             "cumulative histogram series must select at least one aggregate"
             {:parameter :aggregates}))
    (validate-closed-vector! :aggregates aggregates
                             cumulative-histogram-aggregates
                             (count cumulative-histogram-aggregates))
    {:metric-kind metric-kind :temporality temporality :metric-name metric-name
     :group-by group-by :bucket bucket
     :bucket-nanos (get metric-series-buckets bucket)
     :aggregates aggregates :start start :end end
     :limit (validate-positive-cap! :limit limit max-result-limit)
     :text-length (validate-positive-cap! :max-text-length text-length
                                          max-text-length)}))

(defn- validate-metric-series-request! [connection options]
  (when (nil? connection)
    (fail! ::invalid-connection
           "metric series requires a connection"
           {:connection connection}))
  (when-not (map? options)
    (fail! ::invalid-request
           "metric series request must be a map"
           {:request options}))
  (when-let [unknown (seq (remove metric-series-request-keys (keys options)))]
    (fail! ::unsupported-request-key
           "metric series request contains unsupported keys"
           {:keys (vec (sort-by str unknown))}))
  (let [{:keys [metric-kind metric-name start-unix-nano end-unix-nano limit]
         :as options} options
        group-by (get options :group-by [])
        bucket (get options :bucket :none)
        aggregates (get options :aggregates [:count])
        text-length (get options :max-text-length default-text-length)
        table (get metric-series-tables metric-kind)
        start (validate-instant! :start-unix-nano start-unix-nano)
        end (validate-instant! :end-unix-nano end-unix-nano)]
    (when-not table
      (fail! ::unsupported-metric-kind
             "metric series kind must be :gauge, :sum, or :histogram"
             {:metric-kind metric-kind
              :supported [:gauge :sum :histogram]}))
    (validate-metric-name! metric-name)
    (when-not (< start end)
      (fail! ::invalid-time-window
             "metric series time window must be non-empty and increasing"
             {:start-unix-nano start :end-unix-nano end}))
    (when (> (- end start) max-time-range-nanos)
      (fail! ::time-range-too-large
             "metric series time window exceeds the 24 hour hard cap"
             {:actual (- end start) :maximum max-time-range-nanos}))
    (validate-closed-vector! :group-by group-by metric-series-group-order
                             max-field-count)
    (when-not (contains? metric-series-buckets bucket)
      (fail! ::unsupported-bucket
             "metric series bucket is not supported"
             {:bucket bucket :supported [:none :1m :5m :15m :1h]}))
    (let [aggregate-map (metric-aggregate-map metric-kind)]
      (when (empty? aggregates)
        (fail! ::invalid-series-vector
               "metric series must select at least one aggregate"
               {:parameter :aggregates :value aggregates}))
      (validate-closed-vector! :aggregates aggregates
                               (if (= :histogram metric-kind)
                                 histogram-aggregate-order scalar-aggregate-order)
                               8)
      {:metric-kind metric-kind
       :metric-name metric-name
       :table table
       :group-by group-by
       :bucket bucket
       :bucket-nanos (get metric-series-buckets bucket)
       :aggregates aggregates
       :aggregate-map aggregate-map
       :start start
       :end end
       :limit (validate-positive-cap! :limit limit max-result-limit)
       :text-length (validate-positive-cap! :max-text-length text-length
                                            max-text-length)})))

(def ^:private metric-time-nanos
  "toInt64(toUnixTimestamp(TimeUnix)) * 1000000000")

(defn- metric-series-query
  [{:keys [metric-kind table group-by bucket-nanos aggregates aggregate-map]}]
  (let [bucket-select (when bucket-nanos
                        (str "intDiv(" metric-time-nanos
                             ", ?) * ? AS bucketstart"))
        group-selects
        (mapv (fn [field]
                (let [{:keys [expression alias]}
                      (get metric-series-group-fields field)]
                  (str "leftUTF8(toString(" expression "), ?) AS " alias)))
              group-by)
        aggregate-selects (mapv aggregate-map aggregates)
        selects (concat (when bucket-select [bucket-select])
                        group-selects aggregate-selects)
        groups (concat (when bucket-select ["bucketstart"])
                       (map (comp :alias metric-series-group-fields) group-by))]
    (str "SELECT " (str/join ",\n       " selects) "\n"
         "FROM " table "\n"
         "WHERE " metric-time-nanos " >= ?\n"
         "  AND " metric-time-nanos " < ?\n"
         "  AND MetricName = ?\n"
         ;; Count and Sum may be added across delta histogram points. Adding
         ;; cumulative snapshots double-counts earlier observations and needs
         ;; reset-aware stream differencing, which this bounded API does not do.
         (when (= :histogram metric-kind)
           "  AND AggregationTemporality = 1\n")
         (when (seq groups)
           (str "GROUP BY " (str/join ", " groups) "\n"))
         ;; ClickHouse otherwise emits one synthetic row for an ungrouped
         ;; aggregate over an empty input. Its values vary by aggregate (zero
         ;; for some functions, NULL for others), which is not a usable series.
         "HAVING count() > 0\n"
         (when (seq groups)
           (str "ORDER BY " (str/join " ASC, " groups) " ASC\n"))
         "LIMIT ?")))

(defn- metric-series-params
  [{:keys [bucket-nanos group-by text-length start end metric-name limit]}]
  (vec (concat (when bucket-nanos [bucket-nanos bucket-nanos])
               (repeat (count group-by) text-length)
               [start end metric-name limit])))

(defn- finite-number? [value]
  (and (number? value)
       (let [n (double value)]
         (and (= n n) (not= n ##Inf) (not= n ##-Inf)))))

(defn- normalize-metric-series-row
  [{:keys [group-by bucket-nanos aggregates]} row]
  (when-not (map? row)
    (fail! ::invalid-series-row
           "metric series result row must be a map" {:row row}))
  (let [group-values
        (into {}
              (map (fn [field]
                     (let [{:keys [result-key output-key]}
                           (get metric-series-group-fields field)
                           value (get row result-key)]
                       (when-not (and (string? value)
                                      (<= (count value) max-text-length))
                         (fail! ::invalid-series-row
                                "metric series group value is invalid"
                                {:field field :value value}))
                       [output-key value])))
              group-by)
        bucket-value (when bucket-nanos (:bucketstart row))
        aggregate-values
        (into {}
              (map (fn [aggregate]
                     (let [value (get row aggregate)]
                       (when-not (and (finite-number? value)
                                      (or (not= aggregate :count)
                                          (and (integer? value) (<= 0 value))))
                         (fail! ::invalid-series-row
                                "metric series aggregate value is invalid"
                                {:aggregate aggregate :value value}))
                       [aggregate (if (= aggregate :count)
                                    value (double value))])))
              aggregates)]
    (when (and bucket-nanos
               (not (and (integer? bucket-value)
                         (<= 0 bucket-value 9223372036854775807))))
      (fail! ::invalid-series-row
             "metric series bucket value is invalid"
             {:bucket-start-unix-nano bucket-value}))
    (cond-> (merge group-values aggregate-values)
      bucket-nanos (assoc :bucket-start-unix-nano bucket-value))))

(def ^:private counter-stream-result-keys
  [:streamservice :streammetricunit :streamscopename :streamscopeversion
   :streamresourceschemaurl :streamscopeschemaurl :streamresourceattributes
   :streamscopeattributes :streamattributes])

(defn- cumulative-counter-query [{:keys [group-by]}]
  (let [group-selects
        (mapv (fn [field]
                (let [{:keys [expression alias]}
                      (get metric-series-group-fields field)]
                  (str "leftUTF8(toString(" expression "), ?) AS " alias)))
              group-by)]
    (str "SELECT "
         (when (seq group-selects)
           (str (str/join ", " group-selects) ",\n       "))
         "toInt64(toUnixTimestamp(StartTimeUnix)) * 1000000000 AS starttimenano,\n"
         "       toInt64(toUnixTimestamp(TimeUnix)) * 1000000000 AS timenano,\n"
         "       ServiceName AS streamservice, MetricUnit AS streammetricunit,\n"
         "       ScopeName AS streamscopename, ScopeVersion AS streamscopeversion,\n"
         "       ResourceSchemaUrl AS streamresourceschemaurl,\n"
         "       ScopeSchemaUrl AS streamscopeschemaurl,\n"
         "       ResourceAttributes AS streamresourceattributes,\n"
         "       ScopeAttributes AS streamscopeattributes,\n"
         "       Attributes AS streamattributes,\n"
         "       Value AS value, AggregationTemporality AS aggregationtemporality,\n"
         "       IsMonotonic AS ismonotonic\n"
         "FROM otel_metrics_sum\n"
         "WHERE " metric-time-nanos " >= ?\n"
         "  AND " metric-time-nanos " < ?\n"
         "  AND MetricName = ?\n"
         "  AND AggregationTemporality = 2\n"
         "  AND IsMonotonic = true\n"
         "LIMIT ?\n"
         "SETTINGS max_result_bytes = " max-counter-source-bytes
         ", max_rows_to_read = " max-counter-scan-rows
         ", max_bytes_to_read = " max-counter-scan-bytes
         ", max_memory_usage = " max-counter-memory-bytes
         ", max_execution_time = " max-counter-query-seconds
         ", max_threads = 1")))

(defn- cumulative-counter-params
  [{:keys [group-by text-length start end metric-name]}]
  (vec (concat (repeat (count group-by) text-length)
               [start end metric-name (inc max-counter-source-points)])))

(defn- counter-row! [{:keys [group-by start end]} row]
  (when-not (map? row)
    (fail! ::invalid-counter-row
           "cumulative counter source row must be a map"
           {:reason :non-map-source-row}))
  (let [start-time (:starttimenano row)
        time (:timenano row)
        value (:value row)]
    (when-not (and (integer? start-time) (<= 0 start-time)
                   (integer? time) (<= start-time time)
                   (<= start time) (< time end)
                   (finite-number? value) (<= 0 (double value))
                   (= 2 (:aggregationtemporality row))
                   (true? (:ismonotonic row))
                   (every? map? (map row [:streamresourceattributes
                                          :streamscopeattributes
                                          :streamattributes]))
                   (every? string? (map row [:streamservice :streammetricunit
                                             :streamscopename
                                             :streamscopeversion
                                             :streamresourceschemaurl
                                             :streamscopeschemaurl]))
                   (every? string?
                           (map #(get row
                                      (:result-key
                                       (get metric-series-group-fields %)))
                                group-by)))
      (fail! ::invalid-counter-row
             "cumulative counter source row violates its stored provenance"
             {:reason :invalid-stored-provenance}))
    (assoc row :value (double value))))

(defn- counter-stream-key [row]
  (mapv row counter-stream-result-keys))

(defn- counter-projection [{:keys [group-by]} row]
  (into {}
        (map (fn [field]
               (let [{:keys [result-key output-key]}
                     (get metric-series-group-fields field)]
                 [output-key (get row result-key)])))
        group-by))

(defn- counter-interval!
  [projection reset? interval-start row increase]
  (let [interval-end (:timenano row)
        duration (- interval-end interval-start)]
    (when-not (pos? duration)
      (fail! ::zero-duration-counter-interval
             "a cumulative counter interval has no stored duration"
             {:interval-start-unix-nano interval-start
              :time-unix-nano interval-end :reset? reset?}))
    {:projection projection :interval-start interval-start
     :interval-end interval-end :increase increase :duration duration
     :reset? reset?}))

(defn- counter-stream-intervals [request rows]
  (let [rows (sort-by :timenano rows)
        projection (counter-projection request (first rows))]
    (loop [previous nil, remaining rows, intervals []]
      (if-let [row (first remaining)]
        (let [same-time? (and previous
                              (= (:timenano previous) (:timenano row)))]
          (when same-time?
            (fail! ::ambiguous-counter-order
                   "counter snapshots share a stored second and cannot be ordered exactly"
                   {:time-unix-nano (:timenano row)}))
          (if-not previous
            (let [reset-in-window? (>= (:starttimenano row) (:start request))
                  increase (:value row)
                  _ (when (and reset-in-window?
                               (= (:starttimenano row) (:timenano row))
                               (pos? increase))
                      (fail! ::zero-duration-counter-interval
                             "a positive reset value has no stored duration"
                             {:time-unix-nano (:timenano row)
                              :reset? true}))
                  intervals
                  (if (and reset-in-window?
                           (< (:starttimenano row) (:timenano row)))
                    (conj intervals
                          (counter-interval!
                           projection true (:starttimenano row)
                           row increase))
                    intervals)]
              (recur row (next remaining) intervals))
            (let [previous-start (:starttimenano previous)
                  current-start (:starttimenano row)
                  previous-value (:value previous)
                  current-value (:value row)]
              (when (< current-start previous-start)
                (fail! ::ambiguous-counter-reset
                       "counter start time moved backwards"
                       {:previous-start-unix-nano previous-start
                        :start-unix-nano current-start}))
              (when (and (> current-start previous-start)
                         (< current-start (:timenano previous)))
                (fail! ::ambiguous-counter-reset
                       "counter reset epoch overlaps the preceding stored snapshot"
                       {:previous-time-unix-nano (:timenano previous)
                        :start-unix-nano current-start}))
              (when (and (= current-start previous-start)
                         (< current-value previous-value))
                (fail! ::unproven-counter-reset
                       "monotonic counter decreased without a new stored start time"
                       {:start-unix-nano current-start
                        :time-unix-nano (:timenano row)}))
              (let [reset? (> current-start previous-start)
                    interval-start (if reset? current-start (:timenano previous))
                    increase (if reset? current-value
                                 (- current-value previous-value))
                    _ (when (and reset?
                                 (= interval-start (:timenano row))
                                 (pos? increase))
                        (fail! ::zero-duration-counter-interval
                               "a positive reset value has no stored duration"
                               {:time-unix-nano (:timenano row)
                                :reset? true}))
                    intervals
                    (if (< interval-start (:timenano row))
                      (conj intervals
                            (counter-interval!
                             projection reset? interval-start row increase))
                      intervals)]
                (recur row (next remaining) intervals)))))
        intervals))))

(defn- ensure-unambiguous-projections! [request rows]
  (doseq [[projection projected-rows]
          (group-by #(counter-projection request %) rows)]
    (let [streams (set (map counter-stream-key projected-rows))]
      (when (> (count streams) 1)
        (fail! ::ambiguous-counter-projection
               "selected dimensions collapse distinct OTEL counter streams"
               {:stream-count (count streams)
                :group-by (:group-by request)}))))
  rows)

(defn- interval-bucket! [{:keys [bucket-nanos]} interval]
  (when bucket-nanos
    (let [bucket-start (* (quot (dec (:interval-end interval)) bucket-nanos)
                          bucket-nanos)
          bucket-end (+ bucket-start bucket-nanos)]
      (when (< (:interval-start interval) bucket-start)
        (fail! ::counter-interval-crosses-bucket
               "counter interval crosses a requested bucket boundary"
               {:interval-start-unix-nano (:interval-start interval)
                :time-unix-nano (:interval-end interval)
                :bucket-start-unix-nano bucket-start
                :bucket-end-unix-nano bucket-end}))
      bucket-start)))

(defn- summarize-counter-intervals [request intervals]
  (let [groups
        (group-by (fn [interval]
                    [(interval-bucket! request interval)
                     (:projection interval)])
                  intervals)]
    (->> groups
         (map (fn [[[bucket-start projection] xs]]
                (let [increase (reduce + 0.0 (map :increase xs))
                      duration (reduce + 0 (map :duration xs))
                      reset-count (count (filter :reset? xs))
                      measures (cond-> {}
                                 (some #{:increase} (:aggregates request))
                                 (assoc :increase increase)
                                 (some #{:rate} (:aggregates request))
                                 (assoc :rate (/ increase
                                                 (/ (double duration)
                                                    1000000000.0))))]
                  (cond-> (merge projection measures
                                 {:metric-kind :sum
                                  :temporality :cumulative
                                  :monotonic? true
                                  :interval-count (count xs)
                                  :reset-count reset-count
                                  :observed-duration-nanos duration})
                    bucket-start
                    (assoc :bucket-start-unix-nano bucket-start)))))
         (sort-by (juxt #(get % :bucket-start-unix-nano 0)
                        #(pr-str (select-keys % (map (comp :output-key
                                                          metric-series-group-fields)
                                                    (:group-by request))))))
         (take (:limit request))
         vec)))

(def ^:private histogram-stream-result-keys
  [:streamservice :streammetricdescription :streammetricunit
   :streamscopename :streamscopeversion :streamscopedroppedattrcount
   :streamresourceschemaurl
   :streamscopeschemaurl :streamresourceattributes :streamscopeattributes
   :streamattributes])

(def ^:private histogram-source-types
  {:starttimetype "DateTime" :timetype "DateTime" :counttype "UInt64"
   :sumtype "Float64" :bucketcountstype "Array(UInt64)"
   :explicitboundstype "Array(Float64)" :mintype "Float64"
   :maxtype "Float64" :temporalitytype "Int32" :flagstype "UInt32"
   :scopedroppedattrcounttype "UInt32"})

(defn- cumulative-histogram-query [{:keys [group-by]}]
  (let [group-selects
        (mapv (fn [field]
                (let [{:keys [expression alias]}
                      (get metric-series-group-fields field)]
                  (str "leftUTF8(toString(" expression "), ?) AS " alias)))
              group-by)]
    (str "SELECT "
         (when (seq group-selects)
           (str (str/join ", " group-selects) ",\n       "))
         "toInt64(toUnixTimestamp(StartTimeUnix)) * 1000000000 AS starttimenano,\n"
         "       toInt64(toUnixTimestamp(TimeUnix)) * 1000000000 AS timenano,\n"
         "       ServiceName AS streamservice,\n"
         "       MetricDescription AS streammetricdescription,\n"
         "       MetricUnit AS streammetricunit, ScopeName AS streamscopename,\n"
         "       ScopeVersion AS streamscopeversion,\n"
         "       ScopeDroppedAttrCount AS streamscopedroppedattrcount,\n"
         "       ResourceSchemaUrl AS streamresourceschemaurl,\n"
         "       ScopeSchemaUrl AS streamscopeschemaurl,\n"
         "       ResourceAttributes AS streamresourceattributes,\n"
         "       ScopeAttributes AS streamscopeattributes,\n"
         "       Attributes AS streamattributes,\n"
         "       Count AS count, Sum AS sum, BucketCounts AS bucketcounts,\n"
         "       ExplicitBounds AS explicitbounds, Min AS min, Max AS max,\n"
         "       AggregationTemporality AS aggregationtemporality, Flags AS flags,\n"
         "       toTypeName(StartTimeUnix) AS starttimetype,\n"
         "       toTypeName(TimeUnix) AS timetype, toTypeName(Count) AS counttype,\n"
         "       toTypeName(Sum) AS sumtype,\n"
         "       toTypeName(BucketCounts) AS bucketcountstype,\n"
         "       toTypeName(ExplicitBounds) AS explicitboundstype,\n"
         "       toTypeName(Min) AS mintype, toTypeName(Max) AS maxtype,\n"
         "       toTypeName(AggregationTemporality) AS temporalitytype,\n"
         "       toTypeName(Flags) AS flagstype,\n"
         "       toTypeName(ScopeDroppedAttrCount) AS scopedroppedattrcounttype\n"
         "FROM otel_metrics_histogram\n"
         "WHERE " metric-time-nanos " >= ?\n"
         "  AND " metric-time-nanos " < ?\n"
         "  AND MetricName = ?\n"
         "  AND AggregationTemporality = 2\n"
         "LIMIT ?\n"
         "SETTINGS max_result_bytes = " max-histogram-source-bytes
         ", max_rows_to_read = " max-histogram-scan-rows
         ", max_bytes_to_read = " max-histogram-scan-bytes
         ", max_memory_usage = " max-histogram-memory-bytes
         ", max_execution_time = " max-histogram-query-seconds
         ", max_threads = 1")))

(defn- cumulative-histogram-params
  [{:keys [group-by text-length start end metric-name]}]
  (vec (concat (repeat (count group-by) text-length)
               [start end metric-name (inc max-histogram-source-points)])))

(defn- strictly-increasing-finite? [values]
  (and (every? finite-number? values)
       (every? (fn [[left right]] (< (double left) (double right)))
               (partition 2 1 values))))

(defn- histogram-row! [{:keys [group-by start end]} row]
  (when-not (map? row)
    (fail! ::invalid-histogram-row
           "cumulative histogram source row must be a map"
           {:reason :non-map-source-row}))
  (let [start-time (:starttimenano row)
        time (:timenano row)
        observation-count (:count row)
        sum (:sum row)
        bucket-counts (:bucketcounts row)
        explicit-bounds (:explicitbounds row)
        minimum (:min row)
        maximum (:max row)]
    (when-not (= histogram-source-types
                 (select-keys row (keys histogram-source-types)))
      (fail! ::unsupported-histogram-schema
             "cumulative histogram columns differ from the pinned schema"
             {:reason :source-column-types}))
    (when-not (and (integer? start-time) (<= 0 start-time)
                   (integer? time) (<= start-time time)
                   (<= start time) (< time end)
                   (integer? observation-count) (<= 0 observation-count)
                   (finite-number? sum)
                   (vector? bucket-counts)
                   (every? #(and (integer? %) (<= 0 %)) bucket-counts)
                   (vector? explicit-bounds)
                   (= (count bucket-counts) (inc (count explicit-bounds)))
                   (strictly-increasing-finite? explicit-bounds)
                   (finite-number? minimum) (finite-number? maximum)
                   (or (zero? observation-count)
                       (<= (double minimum) (double maximum)))
                   (= observation-count (reduce + 0 bucket-counts))
                   (or (pos? observation-count) (zero? (double sum)))
                   (= 2 (:aggregationtemporality row))
                   (= 0 (:flags row))
                   (integer? (:streamscopedroppedattrcount row))
                   (<= 0 (:streamscopedroppedattrcount row) 4294967295)
                   (every? map? (map row [:streamresourceattributes
                                          :streamscopeattributes
                                          :streamattributes]))
                   (every? string? (map row [:streamservice
                                             :streammetricdescription
                                             :streammetricunit :streamscopename
                                             :streamscopeversion
                                             :streamresourceschemaurl
                                             :streamscopeschemaurl]))
                   (every? string?
                           (map #(get row
                                      (:result-key
                                       (get metric-series-group-fields %)))
                                group-by)))
      (fail! ::invalid-histogram-row
             "cumulative histogram source row violates its stored contract"
             {:reason :invalid-stored-histogram}))
    (assoc row
           :sum (double sum)
           :min (double minimum)
           :max (double maximum)
           :explicitbounds (mapv double explicit-bounds))))

(defn- histogram-stream-key [row]
  (mapv row histogram-stream-result-keys))

(defn- histogram-projection [{:keys [group-by]} row]
  (into {}
        (map (fn [field]
               (let [{:keys [result-key output-key]}
                     (get metric-series-group-fields field)]
                 [output-key (get row result-key)])))
        group-by))

(defn- ensure-histogram-boundaries! [rows]
  (let [boundary-count (count (set (map :explicitbounds rows)))]
    (when (> boundary-count 1)
      (fail! ::histogram-boundary-change
             "explicit histogram boundaries changed within one OTEL stream"
             {:boundary-schema-count boundary-count})))
  rows)

(defn- histogram-interval!
  [projection bounds reset? interval-start row count sum bucket-counts]
  (let [interval-end (:timenano row)
        duration (- interval-end interval-start)]
    (when-not (pos? duration)
      (fail! ::zero-duration-histogram-interval
             "a nonempty cumulative histogram interval has no stored duration"
             {:interval-start-unix-nano interval-start
              :time-unix-nano interval-end :reset? reset?}))
    (when-not (and (integer? count) (<= 0 count)
                   (finite-number? sum)
                   (= count (reduce + 0 bucket-counts))
                   (every? #(and (integer? %) (<= 0 %)) bucket-counts))
      (fail! ::invalid-histogram-interval
             "differenced cumulative histogram interval is invalid"
             {:reason :invalid-difference :reset? reset?}))
    {:projection projection :explicit-bounds bounds
     :interval-start interval-start :interval-end interval-end
     :count count :sum (double sum) :bucket-counts bucket-counts
     :duration duration :reset? reset?}))

(defn- histogram-stream-intervals [request rows]
  (let [rows (->> rows (sort-by :timenano) vec ensure-histogram-boundaries!)
        projection (histogram-projection request (first rows))
        bounds (:explicitbounds (first rows))]
    (loop [previous nil, remaining rows, intervals []]
      (if-let [row (first remaining)]
        (do
          (when (and previous (= (:timenano previous) (:timenano row)))
            (fail! ::ambiguous-histogram-order
                   "histogram snapshots share a stored second and cannot be ordered exactly"
                   {:time-unix-nano (:timenano row)}))
          (if-not previous
            (let [reset-in-window? (>= (:starttimenano row) (:start request))
                  nonempty? (pos? (:count row))
                  _ (when (and reset-in-window?
                               (= (:starttimenano row) (:timenano row))
                               nonempty?)
                      (fail! ::zero-duration-histogram-interval
                             "a nonempty reset histogram has no stored duration"
                             {:time-unix-nano (:timenano row) :reset? true}))
                  intervals (if (and reset-in-window?
                                     (< (:starttimenano row) (:timenano row)))
                              (conj intervals
                                    (histogram-interval!
                                     projection bounds true (:starttimenano row)
                                     row (:count row) (:sum row)
                                     (:bucketcounts row)))
                              intervals)]
              (recur row (next remaining) intervals))
            (let [previous-start (:starttimenano previous)
                  current-start (:starttimenano row)
                  reset? (> current-start previous-start)]
              (when (< current-start previous-start)
                (fail! ::ambiguous-histogram-reset
                       "histogram start time moved backwards"
                       {:previous-start-unix-nano previous-start
                        :start-unix-nano current-start}))
              (when (and reset? (< current-start (:timenano previous)))
                (fail! ::ambiguous-histogram-reset
                       "histogram reset epoch overlaps the preceding stored snapshot"
                       {:previous-time-unix-nano (:timenano previous)
                        :start-unix-nano current-start}))
              (when-not reset?
                (when (or (< (:count row) (:count previous))
                          (some true? (map < (:bucketcounts row)
                                           (:bucketcounts previous))))
                  (fail! ::unproven-histogram-reset
                         "cumulative histogram count or bucket decreased without a new stored start time"
                         {:time-unix-nano (:timenano row)
                          :start-unix-nano current-start}))
                (when (and (pos? (:count previous)) (pos? (:count row))
                           (or (> (:min row) (:min previous))
                               (< (:max row) (:max previous))))
                  (fail! ::invalid-histogram-extrema
                         "cumulative histogram extrema moved inward within one epoch"
                         {:time-unix-nano (:timenano row)})))
              (let [interval-start (if reset? current-start
                                       (:timenano previous))
                    count (if reset? (:count row)
                              (- (:count row) (:count previous)))
                    sum (if reset? (:sum row)
                            (- (:sum row) (:sum previous)))
                    bucket-counts
                    (if reset? (:bucketcounts row)
                        (mapv - (:bucketcounts row) (:bucketcounts previous)))
                    nonempty? (pos? count)
                    _ (when (and (= interval-start (:timenano row)) nonempty?)
                        (fail! ::zero-duration-histogram-interval
                               "a nonempty reset histogram has no stored duration"
                               {:time-unix-nano (:timenano row) :reset? reset?}))
                    intervals (if (< interval-start (:timenano row))
                                (conj intervals
                                      (histogram-interval!
                                       projection bounds reset? interval-start row
                                       count sum bucket-counts))
                                intervals)]
                (recur row (next remaining) intervals)))))
        intervals))))

(defn- ensure-unambiguous-histogram-projections! [request rows]
  (doseq [[_ projected-rows] (group-by #(histogram-projection request %) rows)]
    (let [streams (set (map histogram-stream-key projected-rows))]
      (when (> (count streams) 1)
        (fail! ::ambiguous-histogram-projection
               "selected dimensions collapse distinct OTEL histogram streams"
               {:stream-count (count streams)
                :group-by (:group-by request)}))))
  rows)

(defn- histogram-interval-bucket! [{:keys [bucket-nanos]} interval]
  (when bucket-nanos
    (let [bucket-start (* (quot (dec (:interval-end interval)) bucket-nanos)
                          bucket-nanos)
          bucket-end (+ bucket-start bucket-nanos)]
      (when (< (:interval-start interval) bucket-start)
        (fail! ::histogram-interval-crosses-bucket
               "histogram interval crosses a requested bucket boundary"
               {:interval-start-unix-nano (:interval-start interval)
                :time-unix-nano (:interval-end interval)
                :bucket-start-unix-nano bucket-start
                :bucket-end-unix-nano bucket-end}))
      bucket-start)))

(defn- bounded-histogram-quantile
  [{:keys [quantile numerator denominator]} bounds bucket-counts]
  (let [total (reduce + 0 bucket-counts)]
    (when (pos? total)
      (let [rank-numerator (* numerator total)
            rank (* quantile (double total))
            bucket-index
            (loop [index 0, cumulative 0]
              (let [next-cumulative (+ cumulative (nth bucket-counts index))]
                ;; Bucket choice is exact even when UInt64 totals exceed the
                ;; 53-bit integer precision of a double.
                (if (or (>= (* denominator next-cumulative) rank-numerator)
                        (= index (dec (count bucket-counts))))
                  index
                  (recur (inc index) next-cumulative))))
            below (reduce + 0 (take bucket-index bucket-counts))
            in-bucket (nth bucket-counts bucket-index)
            lower (when (pos? bucket-index) (nth bounds (dec bucket-index)))
            upper (when (< bucket-index (count bounds)) (nth bounds bucket-index))
            finite-bucket? (and (some? lower) (some? upper))
            width (when finite-bucket? (- upper lower))
            _ (when (and finite-bucket? (not (finite-number? width)))
                (fail! ::invalid-histogram-boundary-width
                       "explicit histogram bucket width is not finite"
                       {:bucket-index bucket-index}))
            estimate (when finite-bucket?
                       (+ lower (* width
                                   (/ (double (- rank-numerator
                                                 (* denominator below)))
                                      (double (* denominator in-bucket))))))
            error (when finite-bucket?
                    (max (- estimate lower) (- upper estimate)))]
        {:quantile quantile :rank rank
         :rank-numerator rank-numerator :rank-denominator denominator
         :estimate estimate :lower-bound lower :upper-bound upper
         :lower-inclusive? false :upper-inclusive? (some? upper)
         :lower-unbounded? (nil? lower) :upper-unbounded? (nil? upper)
         :bucket-observation-count in-bucket
         :absolute-error-bound error
         :interpolation :uniform-within-explicit-bucket}))))

(defn- summarize-histogram-intervals [request intervals]
  (let [groups (group-by (fn [interval]
                           [(histogram-interval-bucket! request interval)
                            (:projection interval)])
                         intervals)]
    (->> groups
         (map (fn [[[bucket-start projection] xs]]
                (let [bounds (:explicit-bounds (first xs))
                      observation-count (reduce + 0 (map :count xs))
                      sum (reduce + 0.0 (map :sum xs))
                      _ (when-not (finite-number? sum)
                          (fail! ::invalid-histogram-aggregate
                                 "reconstructed histogram sum is not finite"
                                 {:interval-count (count xs)}))
                      bucket-counts (reduce (fn [acc counts] (mapv + acc counts))
                                            (vec (repeat (inc (count bounds)) 0))
                                            (map :bucket-counts xs))
                      reset-count (count (filter :reset? xs))
                      duration (reduce + 0 (map :duration xs))
                      measures
                      (into {}
                            (map (fn [aggregate]
                                   [aggregate
                                    (case aggregate
                                      :count observation-count
                                      :sum sum
                                      :avg (when (pos? observation-count)
                                             (/ sum (double observation-count)))
                                      (bounded-histogram-quantile
                                       (get histogram-quantile-presets aggregate)
                                       bounds bucket-counts))]))
                            (:aggregates request))]
                  (cond-> (merge projection measures
                                 {:metric-kind :histogram
                                  :temporality :cumulative
                                  :explicit-bounds bounds
                                  :interval-count (count xs)
                                  :reset-count reset-count
                                  :observed-duration-nanos duration})
                    bucket-start
                    (assoc :bucket-start-unix-nano bucket-start)))))
         (sort-by (juxt #(get % :bucket-start-unix-nano 0)
                        #(pr-str (select-keys % (map (comp :output-key
                                                          metric-series-group-fields)
                                                    (:group-by request))))))
         (take (:limit request))
         vec)))

(defn- validate-request! [connection options]
  (when (nil? connection)
    (fail! ::invalid-connection
           "attribute explorer requires a connection"
           {:connection connection}))
  (when-not (map? options)
    (fail! ::invalid-request
           "attribute explorer request must be a map"
           {:request options}))
  (let [{:keys [signal fields start-unix-nano end-unix-nano limit]
         :or {fields nil}} options
        text-length (get options :max-text-length default-text-length)
        config (signal-config! signal)
        start (validate-instant! :start-unix-nano start-unix-nano)
        end (validate-instant! :end-unix-nano end-unix-nano)]
    (when-not (< start end)
      (fail! ::invalid-time-window
             "attribute explorer time window must be non-empty and increasing"
             {:start-unix-nano start :end-unix-nano end}))
    (let [range-nanos (- end start)]
      (when (> range-nanos max-time-range-nanos)
        (fail! ::time-range-too-large
               "attribute explorer time window exceeds the 24 hour hard cap"
               {:actual range-nanos :maximum max-time-range-nanos})))
    {:signal signal
     :config config
     :fields (validate-fields! config fields)
     :start start
     :end end
     :limit (validate-positive-cap! :limit limit max-result-limit)
     :text-length (validate-positive-cap! :max-text-length text-length
                                          max-text-length)}))

(defn- distribution-query [{:keys [source time-nanos fields]} field]
  (let [expression (get fields field)]
    (str "SELECT leftUTF8(toString(" expression "), ?) AS value, count() AS count\n"
         "FROM " source "\n"
         "WHERE " time-nanos " >= ?\n"
         "  AND " time-nanos " < ?\n"
         "  AND notEmpty(toString(" expression "))\n"
         "GROUP BY value\n"
         "ORDER BY count DESC, value ASC\n"
         "LIMIT ?")))

(defn top-values
  "Return top value distributions for allowlisted telemetry fields.

  options requires :signal, :fields (a non-empty vector), :start-unix-nano,
  :end-unix-nano, and :limit. The time window is [start,end), at most 24 hours.
  :max-text-length defaults to 128 and is capped at 256 UTF-8 characters.

  The result is a vector of {:signal keyword, :field keyword, :value string,
  :count integer} maps. There can be at most fields*limit rows. Long values are
  deliberately grouped into displayed prefix buckets."
  [connection options]
  (let [{:keys [signal config fields start end limit text-length]}
        (validate-request! connection options)]
    (context/with-instrumentation-suppressed
      (vec
       (mapcat
        (fn [field]
          (mapv #(assoc % :signal signal :field field)
                (jdbc/fetch connection
                            [(distribution-query config field)
                             text-length start end limit]
                            {:max-rows limit})))
        fields)))))

(defn- checked-typed-column [column]
  (when-not (and (string? column)
                 (<= (count column) 63)
                 (re-matches safe-typed-column column))
    (fail! ::invalid-typed-column
           "typed span capability contains an invalid physical column"
           {:column column}))
  (str "`" column "`"))

(defn- typed-span-values-query [{:keys [physical]}]
  (let [value-column (checked-typed-column (:value-column physical))
        status-column (checked-typed-column (:status-column physical))]
    (when (= value-column status-column)
      (fail! ::invalid-typed-column
             "typed value and status columns must be distinct" {}))
    (str "SELECT value, typedstatus, count() AS count\n"
         "FROM (\n"
         "  SELECT leftUTF8(multiIf(" status-column " IN (2, 3),\n"
         "                              toString(" value-column "),\n"
         "                              " status-column " IN (0, 4),\n"
         "                              SpanAttributes[?], ''), ?) AS value,\n"
         "         " status-column " AS typedstatus\n"
         "  FROM otel_traces\n"
         "  WHERE toUnixTimestamp64Nano(Timestamp) >= ?\n"
         "    AND toUnixTimestamp64Nano(Timestamp) < ?\n"
         ")\n"
         "WHERE notEmpty(value)\n"
         "GROUP BY value, typedstatus\n"
         "ORDER BY count DESC, value ASC, typedstatus ASC\n"
         "LIMIT ?")))

(defn typed-span-values
  "Return bounded distributions for installer-approved span attribute keys.

  Typed statuses 2/3 read the physical value. Historical or invalid rows fall
  back to the generic SpanAttributes map using a bound logical-key parameter;
  absent or unknown statuses produce no value. Physical identifiers and the
  only table name come from library-owned data."
  [connection descriptor-set options]
  (let [{:keys [fields start end limit text-length]}
        (validate-typed-span-request! connection descriptor-set options)]
    (context/with-instrumentation-suppressed
      (vec
       (mapcat
        (fn [{:keys [key] :as field}]
          (let [rows (jdbc/fetch
                      connection
                      [(typed-span-values-query field)
                       key text-length start end limit]
                      {:max-rows limit})]
            (when-not (and (vector? rows) (<= (count rows) limit))
              (fail! ::invalid-typed-result
                     "typed span value result exceeds its request bound"
                     {:key key :actual (when (vector? rows) (count rows))
                      :maximum limit}))
            (mapv
             (fn [{:keys [value typedstatus count]}]
               (when-not (and (string? value)
                              (integer? count) (not (neg? count))
                              (contains? #{0 2 3 4} typedstatus))
                 (fail! ::invalid-typed-result
                        "typed span value row has invalid status or shape"
                        {:key key :typed-status typedstatus}))
               {:attribute-key key :count count :signal :spans
                :source (if (contains? #{2 3} typedstatus)
                          :typed :generic-fallback)
                :typed-status typedstatus :value value})
             rows)))
        fields)))))

(defn metric-series
  "Return one bounded aggregate series for an exact metric name.

  The request selects one physical metric kind, a half-open window no larger
  than 24 hours, optional allowlisted dimensions, an optional fixed time
  bucket, 1–8 allowlisted aggregates, and a result limit no larger than 100.
  All caller strings and numbers are JDBC parameters; tables, expressions,
  aliases, aggregate functions, and grouping clauses come from closed maps.

  Gauge and sum series support :count, :sum, :min, :max, :avg, :p50, :p95,
  and :p99. Percentiles use ClickHouse quantileTDigest over stored scalar sample
  values. Delta histogram series support :count, :sum, and :avg over their
  observation Count and Sum. Cumulative histogram snapshots are excluded
  because correct interval aggregation requires reset-aware differencing; this
  API also does not claim to reconstruct quantiles from OTLP histogram buckets."
  [connection options]
  (let [{:keys [limit] :as request}
        (validate-metric-series-request! connection options)
        sqlvec (into [(metric-series-query request)]
                     (metric-series-params request))]
    (context/with-instrumentation-suppressed
      (let [rows (jdbc/fetch connection sqlvec {:max-rows limit})]
        (when-not (and (vector? rows) (<= (count rows) limit))
          (fail! ::invalid-series-result
                 "metric series result exceeds its bounded request"
                 {:actual (when (vector? rows) (count rows))
                  :maximum limit}))
        (mapv #(normalize-metric-series-row request %) rows)))))

(defn cumulative-counter-series
  "Return bounded increase/rate rows for stored cumulative monotonic OTEL sums.

  The request must explicitly state :metric-kind :sum, :temporality
  :cumulative, and :monotonic? true. It otherwise uses metric-series' exact
  metric name, dimensions, fixed buckets, window, text, and result limits, with
  :aggregates restricted to :increase and :rate.

  Each delta is derived only from an exactly ordered pair in one complete OTEL
  stream identity. A new StartTimeUnix is a reset and contributes the new
  cumulative value from that stored start. Decreases without a new start,
  duplicate stored timestamps, overlapping reset epochs, projections that
  collapse distinct streams, and intervals crossing a requested bucket all
  fail instead of being approximated. The first in-window snapshot is omitted
  when its start predates the window because its boundary increase is unknown.

  :rate is increase per second over the returned observed intervals. Results
  retain kind/temporality/monotonic provenance plus :interval-count,
  :reset-count, and :observed-duration-nanos. Metric timestamps in the pinned
  ClickStack schema have whole-second precision, which is also the ordering and
  duration precision of this operation."
  [connection options]
  (let [{:keys [limit] :as request}
        (validate-cumulative-counter-request! connection options)
        sqlvec (into [(cumulative-counter-query request)]
                     (cumulative-counter-params request))]
    (context/with-instrumentation-suppressed
      (let [source-rows (jdbc/fetch connection sqlvec
                                    {:max-rows (inc max-counter-source-points)})]
        (when-not (vector? source-rows)
          (fail! ::invalid-series-result
                 "cumulative counter source result must be a vector"
                 {:actual (type source-rows)}))
        (when (> (count source-rows) max-counter-source-points)
          (fail! ::counter-source-too-large
                 "cumulative counter source exceeds its hard point cap"
                 {:actual (count source-rows)
                  :maximum max-counter-source-points}))
        (let [rows (mapv #(counter-row! request %) source-rows)]
          (ensure-unambiguous-projections! request rows)
          (summarize-counter-intervals
           request
           (mapcat #(counter-stream-intervals request %)
                   (vals (group-by counter-stream-key rows)))))))))

(defn cumulative-histogram-series
  "Return bounded interval aggregates and quantiles for cumulative OTEL explicit
  histograms.

  The request must explicitly state :metric-kind :histogram and :temporality
  :cumulative. Snapshots are differenced only inside one exact resource, scope,
  instrument, and attribute identity. A new StartTimeUnix begins a reset epoch.
  Boundary changes, duplicate stored seconds, overlapping epochs, unexplained
  count/bucket decreases, collapsed projections, and bucket-crossing intervals
  fail closed. Sum is differenced arithmetically and may decrease when newly
  observed values are negative. The first point is omitted when its epoch predates the
  requested window because its boundary delta is unknown.

  :p50/:p95/:p99 return a map containing the selected explicit-bucket interval,
  a uniform-within-that-bucket interpolation, and its worst-case absolute error
  bound. An implicit -Inf or +Inf edge is represented by a nil bound and an
  unbounded flag; its estimate and numeric error are nil. A zero-observation
  result has nil :avg and quantiles. No scalar samples are used or invented."
  [connection options]
  (let [{:keys [limit] :as request}
        (validate-cumulative-histogram-request! connection options)
        sqlvec (into [(cumulative-histogram-query request)]
                     (cumulative-histogram-params request))]
    (context/with-instrumentation-suppressed
      (let [source-rows
            (jdbc/fetch connection sqlvec
                        {:max-rows (inc max-histogram-source-points)})]
        (when-not (vector? source-rows)
          (fail! ::invalid-series-result
                 "cumulative histogram source result must be a vector"
                 {:reason :non-vector-source-result}))
        (when (> (count source-rows) max-histogram-source-points)
          (fail! ::histogram-source-too-large
                 "cumulative histogram source exceeds its hard point cap"
                 {:actual (count source-rows)
                  :maximum max-histogram-source-points}))
        (let [rows (mapv #(histogram-row! request %) source-rows)]
          (ensure-unambiguous-histogram-projections! request rows)
          (summarize-histogram-intervals
           request
           (mapcat #(histogram-stream-intervals request %)
                   (vals (group-by histogram-stream-key rows)))))))))
