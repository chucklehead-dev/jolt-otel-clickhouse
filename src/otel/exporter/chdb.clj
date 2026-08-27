(ns otel.exporter.chdb
  "Direct Jolt OTel exporter for an embedded/in-process chDB database."
  (:require [clojure.data.json :as json]
            [jdbc.chdb :as chdb]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb.schema :as schema]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(defn- key-string [k]
  (cond (string? k) k (keyword? k) (subs (str k) 1) :else (str k)))

(defn- value-string [v]
  (if (or (sequential? v) (map? v)) (json/write-str v) (str v)))

(defn- attrs [m]
  (into {} (map (fn [[k v]] [(key-string k) (value-string v)])) (or m {})))

(defn- service-name [resource]
  (or (get (:attributes resource) "service.name")
      (get (:attributes resource) :service.name)
      "unknown_service:jolt"))

(defn- timestamp [nanos]
  (let [seconds (quot nanos 1000000000)
        remainder (mod nanos 1000000000)]
    (format "%d.%09d" seconds remainder)))

(defn- span-row [span]
  (let [context (:span-context span)
        scope (:scope span)
        resource (:resource span)]
    {"Timestamp" (timestamp (:start-time-unix-nano span))
     "TraceId" (or (:trace-id context) "")
     "SpanId" (or (:span-id context) "")
     "ParentSpanId" (or (:parent-span-id span) "")
     "TraceState" (value-string (or (:trace-state context) ""))
     "SpanName" (:name span)
     "SpanKind" (name (:kind span))
     "ServiceName" (service-name resource)
     "ResourceAttributes" (attrs (:attributes resource))
     "ScopeName" (or (:name scope) "")
     "ScopeVersion" (or (:version scope) "")
     "SpanAttributes" (attrs (:attributes span))
     "Duration" (max 0 (- (:end-time-unix-nano span) (:start-time-unix-nano span)))
     "StatusCode" (name (get-in span [:status :code] :unset))
     "StatusMessage" (or (get-in span [:status :description]) "")
     "EventsJSON" (json/write-str (:events span))
     "LinksJSON" (json/write-str (:links span))}))

(defn- log-row [record]
  (let [scope (:scope record)
        resource (:resource record)]
    {"Timestamp" (timestamp (or (:timestamp-unix-nano record)
                                (:observed-time-unix-nano record)))
     "TraceId" (or (:trace-id record) "")
     "SpanId" (or (:span-id record) "")
     "TraceFlags" (or (:trace-flags record) 0)
     "SeverityText" (or (:severity-text record) "")
     "SeverityNumber" (or (:severity-number record) 0)
     "ServiceName" (service-name resource)
     "Body" (value-string (:body record))
     "ResourceSchemaUrl" (or (:schema-url resource) "")
     "ResourceAttributes" (attrs (:attributes resource))
     "ScopeSchemaUrl" (or (:schema-url scope) "")
     "ScopeName" (or (:name scope) "")
     "ScopeVersion" (or (:version scope) "")
     "ScopeAttributes" (attrs (:attributes scope))
     "LogAttributes" (attrs (:attributes record))
     "EventName" (or (:event-name record) "")}))

(defn- chunks [rows]
  (map #(str (json/write-str %) "\n") rows))

(defn- temporality-code [value]
  (case value :delta 1 :cumulative 2 0))

(defn- metric-rows [resource collected]
  (for [{:keys [scope metrics]} collected
        metric metrics
        point (:data-points metric)]
    (merge
     {"ResourceAttributes" (attrs (:attributes resource))
      "ScopeName" (or (:name scope) "")
      "ScopeVersion" (or (:version scope) "")
      "ServiceName" (service-name resource)
      "MetricName" (:name metric)
      "MetricDescription" (or (:description metric) "")
      "MetricUnit" (or (:unit metric) "")
      "Attributes" (attrs (:attributes point))
      "StartTimeUnix" (timestamp (or (:start-time-unix-nano point)
                                     (:time-unix-nano point)))
      "TimeUnix" (timestamp (:time-unix-nano point))}
     (case (:type metric)
       :gauge {"Value" (double (:value point))}
       :sum {"Value" (double (:value point))
             "AggregationTemporality" (temporality-code (:temporality metric))
             "IsMonotonic" (boolean (:monotonic? metric))}
       :histogram {"Count" (:count point)
                   "Sum" (double (:sum point))
                   "BucketCounts" (:bucket-counts point)
                   "ExplicitBounds" (:explicit-bounds metric)
                   "Min" (double (or (:min point) 0.0))
                   "Max" (double (or (:max point) 0.0))
                   "AggregationTemporality" (temporality-code (:temporality metric))}))))

(defn- export-metric-type! [connection type rows]
  (let [selected (filter #(= type (:_type %)) rows)]
    (when (seq selected)
      (chdb/stream-insert! connection (str "insert into otel_metrics_" (name type))
                           (chunks (map #(dissoc % :_type) selected))))))

(defrecord ChdbExporter [connection owned? state]
  export/SpanExporter
  (export-spans! [_ spans]
    (if (:shutdown? @state)
      false
      (try
        (when (seq spans)
          (chdb/stream-insert! connection "insert into otel_traces"
                               (chunks (map span-row spans))))
        true
        (catch Throwable e
          (swap! state assoc :last-error e)
          false))))
  (flush-exporter! [_] true)
  (shutdown-exporter! [_]
    (let [[old _] (swap-vals! state assoc :shutdown? true)]
      (when (and owned? (not (:shutdown? old))) (.close connection)))
    true)

  export/MetricExporter
  (export-metrics! [_ resource collected]
    (if (:shutdown? @state)
      false
      (try
        (let [rows (for [{:keys [scope metrics]} collected
                         metric metrics
                         point (:data-points metric)
                         :let [row (first (metric-rows resource
                                                       [{:scope scope
                                                         :metrics [(assoc metric :data-points [point])]}]))]]
                     (assoc row :_type (:type metric)))]
          (doseq [type [:gauge :sum :histogram]]
            (export-metric-type! connection type rows)))
        true
        (catch Throwable e
          (swap! state assoc :last-error e)
          false))))
  (shutdown-metric-exporter! [this] (export/shutdown-exporter! this))

  logs/LogRecordExporter
  (export-logs! [_ records]
    (if (:shutdown? @state)
      false
      (try
        (when (seq records)
          (chdb/stream-insert! connection "insert into otel_logs"
                               (chunks (map log-row records))))
        true
        (catch Throwable e
          (swap! state assoc :last-error e)
          false))))
  (shutdown-log-exporter! [this] (export/shutdown-exporter! this)))

(defn exporter
  "Create a span+log exporter. Supply :connection to share ownership with an
  application, or :db-spec (default chdb::memory:) for an exporter-owned one."
  ([] (exporter {}))
  ([{:keys [connection db-spec create-schema?]
     :or {db-spec "chdb::memory:" create-schema? true}}]
   (let [owned? (nil? connection)
         conn (or connection (jdbc/connection db-spec))]
     (try
       (when create-schema? (schema/ensure-schema! conn))
       (->ChdbExporter conn owned? (atom {:shutdown? false :last-error nil}))
       (catch Throwable t
         (when owned? (.close conn))
         (throw t))))))

(defn last-error [exporter] (:last-error @(:state exporter)))
