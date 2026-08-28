(ns otel.exporter.chdb.explorer
  "Bounded, read-only distributions over the embedded ClickStack-shaped
  telemetry tables. Caller data is used only as query parameters; every table,
  timestamp column, and explored expression comes from a closed allowlist."
  ;; db.jdbc must load before jdbc.core is compiled: it installs Jolt's
  ;; java.sql class shims, including ResultSet. Own that ordering here so a
  ;; standalone explorer consumer needs no undocumented bootstrap require.
  (:require [db.jdbc]
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
