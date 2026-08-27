(ns otel.exporter.chdb
  "Direct Jolt OTel exporter for an embedded/in-process chDB database."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb.schema :as schema]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(defn- key-string [k]
  (cond (string? k) k (keyword? k) (subs (str k) 1) :else (str k)))

(defn- value-string [v]
  (cond
    (nil? v) ""
    (or (sequential? v) (map? v)) (json/write-str v)
    :else (str v)))

(defn- attrs [m]
  (into {} (map (fn [[k v]] [(key-string k) (value-string v)])) (or m {})))

(defn- service-name [resource fallback]
  (let [attributes (:attributes resource)]
    (cond
      (contains? attributes "service.name")
      (value-string (get attributes "service.name"))

      (contains? attributes :service.name)
      (value-string (get attributes :service.name))

      :else fallback)))

(defn- timestamp [nanos]
  (let [seconds (quot nanos 1000000000)
        remainder (mod nanos 1000000000)]
    (format "%d.%09d" seconds remainder)))

(defn- trace-state-string [state]
  (cond
    (nil? state) ""
    (string? state) state
    (sequential? state)
    (str/join "," (map (fn [[k v]] (str k "=" v)) state))
    :else (str state)))

(defn- otel-enum-string [value fallback]
  ;; Matches pdata SpanKind.String()/StatusCode.String() used by the pinned
  ;; collector exporter (for example Server, Client, Ok, Error, Unset).
  (str/capitalize (name (or value fallback))))

(defn- event-columns [events]
  {"Events.Timestamp" (mapv #(timestamp (:timestamp-unix-nano %)) events)
   "Events.Name" (mapv #(or (:name %) "") events)
   "Events.Attributes" (mapv #(attrs (:attributes %)) events)})

(defn- link-columns [links]
  {"Links.TraceId" (mapv #(or (get-in % [:span-context :trace-id]) "") links)
   "Links.SpanId" (mapv #(or (get-in % [:span-context :span-id]) "") links)
   "Links.TraceState" (mapv #(trace-state-string
                              (get-in % [:span-context :trace-state]))
                            links)
   "Links.Attributes" (mapv #(attrs (:attributes %)) links)})

(defn- uint8 [value]
  ;; pdata values are converted with Go's uint8 cast by the pinned exporter.
  (bit-and (or value 0) 0xff))

(defn- log-timestamp-nanos [record]
  ;; pdata falls back to ObservedTimestamp when Timestamp is its zero value.
  (let [event-time (or (:timestamp-unix-nano record) 0)]
    (if (zero? event-time)
      (or (:observed-time-unix-nano record) 0)
      event-time)))

(defn- span-row [span]
  (let [context (:span-context span)
        scope (:scope span)
        resource (:resource span)
        events (or (:events span) [])
        links (or (:links span) [])]
    (merge
     {"Timestamp" (timestamp (:start-time-unix-nano span))
      "TraceId" (or (:trace-id context) "")
      "SpanId" (or (:span-id context) "")
      "ParentSpanId" (or (:parent-span-id span) "")
      "TraceState" (trace-state-string (:trace-state context))
      "SpanName" (:name span)
      "SpanKind" (otel-enum-string (:kind span) :internal)
      "ServiceName" (service-name resource "unknown_service:jolt")
      "ResourceAttributes" (attrs (:attributes resource))
      "ScopeName" (or (:name scope) "")
      "ScopeVersion" (or (:version scope) "")
      "SpanAttributes" (attrs (:attributes span))
      "Duration" (max 0 (- (:end-time-unix-nano span) (:start-time-unix-nano span)))
      "StatusCode" (otel-enum-string (get-in span [:status :code]) :unset)
      "StatusMessage" (or (get-in span [:status :description]) "")
      "EventsJSON" (json/write-str events)
      "LinksJSON" (json/write-str links)}
     (event-columns events)
     (link-columns links))))

(defn- log-row [record]
  (let [scope (:scope record)
        resource (:resource record)]
    {"Timestamp" (timestamp (log-timestamp-nanos record))
     "TraceId" (or (:trace-id record) "")
     "SpanId" (or (:span-id record) "")
     "TraceFlags" (uint8 (:trace-flags record))
     "SeverityText" (or (:severity-text record) "")
     "SeverityNumber" (uint8 (:severity-number record))
     ;; The pinned collector's GetServiceName uses an empty missing-value
     ;; fallback, unlike the embedded span/metric compatibility default.
     "ServiceName" (service-name resource "")
     "Body" (value-string (:body record))
     "ResourceSchemaUrl" (or (:schema-url resource) "")
     "ResourceAttributes" (attrs (:attributes resource))
     "ScopeSchemaUrl" (or (:schema-url scope) "")
     "ScopeName" (or (:name scope) "")
     "ScopeVersion" (or (:version scope) "")
     "ScopeAttributes" (attrs (:attributes scope))
     "LogAttributes" (attrs (:attributes record))
     "EventName" (or (:event-name record) "")}))

(def ^:private log-insert-query
  (str "insert into otel_logs ("
       (str/join ", " schema/clickstack-log-insert-columns)
       ")"))

(defn- chunks [rows]
  (map #(str (json/write-str %) "\n") rows))

(def ^:private max-insert-bytes (* 8 1024 1024))

(defn- insert-json-rows!
  "Insert one SDK-bounded batch through chDB's ordinary query API. libchdb
  26.7's streaming-insert API corrupts ClickHouse ThreadStatus nesting under
  long-lived multi-signal exporters; the query API does not share that path."
  [connection query rows]
  (let [payload (apply str (chunks rows))
        size (alength (.getBytes payload "UTF-8"))]
    (when (> size max-insert-bytes)
      (throw (ex-info "chDB telemetry export batch exceeds 8 MiB"
                      {:bytes size :limit max-insert-bytes})))
    (jdbc/execute! connection (str query " FORMAT JSONEachRow\n" payload))))

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
      "ServiceName" (service-name resource "unknown_service:jolt")
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
      (insert-json-rows! connection
                         (str "insert into otel_metrics_" (name type))
                         (map #(dissoc % :_type) selected)))))

(defn- signal-open? [owned? expected-signals state signal]
  (cond
    (and owned? (not (contains? expected-signals signal)))
    (do (swap! state assoc :last-error
               (ex-info (str "OTel signal is not enabled for this exporter: " (name signal))
                        {:signal signal :expected-signals expected-signals}))
        false)

    (contains? (:closed-signals @state) signal) false
    :else true))

(defn- close-signal! [connection owned? expected-signals state signal]
  (let [[_ new] (swap-vals! state update :closed-signals conj signal)]
    (when (and owned?
               (not (:connection-closed? new))
               (every? (:closed-signals new) expected-signals))
      (.close connection)
      (swap! state assoc :connection-closed? true)))
  true)

(defrecord ChdbExporter [connection owned? expected-signals state]
  export/SpanExporter
  (export-spans! [_ spans]
    (if-not (signal-open? owned? expected-signals state :spans)
      false
      (try
        (when (seq spans)
          (insert-json-rows! connection "insert into otel_traces"
                             (map span-row spans)))
        true
        (catch Throwable e
          (swap! state assoc :last-error e)
          false))))
  (flush-exporter! [_] true)
  (shutdown-exporter! [_]
    (close-signal! connection owned? expected-signals state :spans))

  export/MetricExporter
  (export-metrics! [_ resource collected]
    (if-not (signal-open? owned? expected-signals state :metrics)
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
  (shutdown-metric-exporter! [_]
    (close-signal! connection owned? expected-signals state :metrics))

  logs/LogRecordExporter
  (export-logs! [_ records]
    (if-not (signal-open? owned? expected-signals state :logs)
      false
      (try
        (when (seq records)
          (insert-json-rows! connection log-insert-query
                             (map log-row records)))
        true
        (catch Throwable e
          (swap! state assoc :last-error e)
          false))))
  (shutdown-log-exporter! [_]
    (close-signal! connection owned? expected-signals state :logs)))

(defn exporter
  "Create a span+log+metric exporter. Supply :connection to share ownership
  with an application, or :db-spec for an exporter-owned one. A chDB map dbspec
  may include :database to create/select an isolated logical database. A shared
  :connection is used in its already selected database; the exporter never
  issues USE or qualifies table names. :signals declares enabled SDK signals so
  an owned connection closes after every pipeline; it defaults to the SDK
  defaults, spans+metrics. Export calls for an undeclared signal fail visibly
  through a false result and last-error. Unless :create-schema? is false,
  startup applies and validates the ordered schema migration registry in that
  selected database."
  ([] (exporter {}))
  ([{:keys [connection db-spec create-schema? signals]
     :or {db-spec "chdb::memory:" create-schema? true
          signals #{:spans :metrics}}}]
   (let [owned? (nil? connection)
         conn (or connection (jdbc/connection db-spec))]
     (try
       (when create-schema? (schema/ensure-schema! conn))
       (->ChdbExporter conn owned? (set signals)
                       (atom {:closed-signals #{}
                              :connection-closed? false
                              :last-error nil}))
       (catch Throwable t
         (when owned? (.close conn))
         (throw t))))))

(defn last-error [exporter] (:last-error @(:state exporter)))
