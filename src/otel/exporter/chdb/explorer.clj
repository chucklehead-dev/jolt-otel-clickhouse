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
            [otel.context :as context]))

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

(def max-counter-source-points
  "Largest raw cumulative-counter snapshot set accepted by one request."
  10000)

(def max-counter-source-bytes
  "Largest chDB result payload accepted while loading counter snapshots (64 MiB)."
  67108864)

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

(defn- validate-metric-name! [value]
  (when-not (and (string? value)
                 (<= 1 (count value) max-text-length)
                 (not (str/blank? value)))
    (fail! ::invalid-metric-name
           "metric series requires a nonblank metric name of at most 256 characters"
           {:metric-name value :maximum max-text-length}))
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
         ", max_threads = 1")))

(defn- cumulative-counter-params
  [{:keys [group-by text-length start end metric-name]}]
  (vec (concat (repeat (count group-by) text-length)
               [start end metric-name (inc max-counter-source-points)])))

(defn- counter-row! [{:keys [group-by start end]} row]
  (when-not (map? row)
    (fail! ::invalid-counter-row
           "cumulative counter source row must be a map" {:row row}))
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
             {:row row :window [start end]}))
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
              :time-unix-nano interval-end :increase increase
              :reset? reset? :projection projection}))
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
                   {:time-unix-nano (:timenano row) :projection projection}))
          (if-not previous
            (let [reset-in-window? (>= (:starttimenano row) (:start request))
                  increase (:value row)
                  _ (when (and reset-in-window?
                               (= (:starttimenano row) (:timenano row))
                               (pos? increase))
                      (fail! ::zero-duration-counter-interval
                             "a positive reset value has no stored duration"
                             {:time-unix-nano (:timenano row)
                              :increase increase :projection projection}))
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
                        :start-unix-nano current-start
                        :projection projection}))
              (when (and (> current-start previous-start)
                         (< current-start (:timenano previous)))
                (fail! ::ambiguous-counter-reset
                       "counter reset epoch overlaps the preceding stored snapshot"
                       {:previous-time-unix-nano (:timenano previous)
                        :start-unix-nano current-start
                        :projection projection}))
              (when (and (= current-start previous-start)
                         (< current-value previous-value))
                (fail! ::unproven-counter-reset
                       "monotonic counter decreased without a new stored start time"
                       {:previous-value previous-value :value current-value
                        :start-unix-nano current-start
                        :projection projection}))
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
                                :increase increase :projection projection}))
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
               {:projection projection :stream-count (count streams)
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
