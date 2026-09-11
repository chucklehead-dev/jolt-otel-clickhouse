(ns otel.exporter.chdb
  "Direct Jolt OTel exporter for an embedded/in-process chDB database."
  (:require [db.jdbc]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [jdbc.chdb :as chdb]
            [jdbc.chdb.durable :as durable]
            [jdbc.core :as jdbc]
            [otel.context :as context]
            [otel.exporter.chdb.attribute-projection :as attribute-projection]
            [otel.exporter.chdb.json :as fast-json]
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

(defn- metric-timestamp [nanos]
  ;; The pinned ClickStack collector stores metric timestamps as DateTime,
  ;; whose wire value is whole Unix seconds. The pdata zero value remains the
  ;; absent-start sentinel; subsecond precision is intentionally truncated.
  (quot nanos 1000000000))

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

(defn- span-row [span typed-projector]
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
     (link-columns links)
     (if typed-projector
       (typed-projector (:attributes span))
       {}))))

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

;; The compiled writers below encode the INSERT payload -- the transport. chDB
;; decodes it and stores the decoded values, so the payload's own escaping
;; choices are invisible downstream and it can take the fast path.
;;
;; `value-string`, `EventsJSON` and `LinksJSON` above are different: their output
;; is the string ClickHouse stores. Its exact bytes are data at rest that queries
;; and existing rows depend on, so those stay on clojure.data.json.
(def ^:private log-insert
  (fast-json/compile-insert "otel_logs" schema/clickstack-log-insert-columns))

(def ^:private base-trace-columns
  ;; span-row carries the migration-v1 EventsJSON/LinksJSON columns alongside
  ;; the v2 nested Events.*/Links.* ones. Both are real columns on otel_traces
  ;; and both have always been written, so the compiled spec is the schema's
  ;; insert list plus those two. write-rows fails closed if span-row and this
  ;; list ever disagree.
  (into (vec schema/clickstack-trace-insert-columns) ["EventsJSON" "LinksJSON"]))

(defn- trace-insert-spec
  "Compile the trace insert for this exporter's actual row shape.

  A typed span projector merges installer-confirmed physical value/status
  columns into every row, so the compiled spec has to carry them too: the
  column set is fixed once the projector is built, but it is not knowable at
  namespace load time."
  [typed-columns]
  (fast-json/compile-insert
   "otel_traces"
   (into base-trace-columns typed-columns)))

(def ^:private trace-insert (trace-insert-spec nil))

(def ^:private max-insert-bytes (* 8 1024 1024))

(defn- oversize?
  "UTF-8 is at most three bytes per Java char, so a statement short enough by
  that bound cannot exceed the limit and needs no encoding pass. Only a
  borderline batch pays for the byte-array copy."
  [^String statement]
  (and (> (* 3 (.length statement)) max-insert-bytes)
       (> (alength (.getBytes statement "UTF-8")) max-insert-bytes)))

(defn- insert-json-rows!
  "Insert one SDK-bounded batch through chDB's ordinary query API.

  Not the streaming-insert API: on libchdb 26.7 a `chdb_stream_insert` leaves
  ClickHouse's `current_thread` thread-local pointing at a destroyed
  ThreadStatus, which the engine reports as a Fatal exactly once per stream.
  It reproduces single-threaded with no data appended, on 26.7.0 and on
  26.7.2-rc.2 alike, and is suppressed unless stderr is a terminal. The query
  API does not share that path."
  [connection compiled rows durable?]
  (let [payload (fast-json/write-rows compiled rows)]
    (when (oversize? payload)
      (throw (ex-info "chDB telemetry export batch exceeds 8 MiB"
                      {:bytes (alength (.getBytes payload "UTF-8"))
                       :limit max-insert-bytes})))
    (context/with-instrumentation-suppressed
      (if durable?
        ;; The Durable writer serializes statements into its WAL and replays
        ;; them, so it takes the whole statement. Only the ordinary driver can
        ;; be handed the rows as data.
        (jdbc/execute! connection (str (:statement compiled)
                                       " FORMAT JSONEachRow\n" payload))
        (chdb/insert-rows! connection (:table compiled) (:columns compiled)
                           payload)))))

(defn- temporality-code [value]
  (case value :delta 1 :cumulative 2 0))

(def ^:private empty-metric-exemplars
  {"Exemplars.FilteredAttributes" []
   "Exemplars.TimeUnix" []
   "Exemplars.Value" []
   "Exemplars.SpanId" []
   "Exemplars.TraceId" []})

(def ^:private metric-inserts
  (into {}
        (map (fn [[kind columns]]
               [kind (fast-json/compile-insert
                      (get schema/metric-table-names kind) columns)]))
        schema/clickstack-metric-insert-columns))

(defn- metric-rows [resource collected]
  (for [{:keys [scope metrics]} collected
        metric metrics
        point (:data-points metric)]
    (merge
     empty-metric-exemplars
     {"ResourceAttributes" (attrs (:attributes resource))
      "ResourceSchemaUrl" (or (:schema-url resource) "")
      "ScopeName" (or (:name scope) "")
      "ScopeVersion" (or (:version scope) "")
      "ScopeAttributes" (attrs (:attributes scope))
      ;; The current SDK scope never drops accepted attributes.
      "ScopeDroppedAttrCount" 0
      "ScopeSchemaUrl" (or (:schema-url scope) "")
      "ServiceName" (service-name resource "")
      "MetricName" (:name metric)
      "MetricDescription" (or (:description metric) "")
      "MetricUnit" (or (:unit metric) "")
      "Attributes" (attrs (:attributes point))
      ;; Gauge start time is absent in the canonical SDK model and therefore
      ;; remains the pdata zero value rather than being fabricated from TimeUnix.
      "StartTimeUnix" (metric-timestamp
                       (or (:start-time-unix-nano point) 0))
      "TimeUnix" (metric-timestamp (or (:time-unix-nano point) 0))
      ;; No-recorded-value flags are not modeled; zero is the canonical default.
      "Flags" 0}
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

(defn- export-metric-type! [connection type rows durable?]
  (let [selected (filter #(= type (:_type %)) rows)]
    (when (seq selected)
      (insert-json-rows! connection
                         (get metric-inserts type)
                         (map #(dissoc % :_type) selected)
                         durable?))))

(defn- signal-open? [owned? expected-signals state signal]
  (cond
    (and owned? (not (contains? expected-signals signal)))
    (do (swap! state assoc :last-error
               (ex-info (str "OTel signal is not enabled for this exporter: " (name signal))
                        {:signal signal :expected-signals expected-signals}))
        false)

    (contains? (:closed-signals @state) signal) false
    :else true))

(def ^:private confirmed-durable-statuses #{:committed :reconciled})

(defn- persistence-barrier! [connection state publication-required?]
  (when-let [barrier (:persistence-barrier @state)]
    (let [result
          (context/with-instrumentation-suppressed
            (barrier connection))]
      (when-not result
        (throw (ex-info "Persistence barrier did not confirm completion"
                        {:type ::persistence-barrier-unconfirmed})))
      (when (and publication-required?
                 (:durable? @state)
                 (not (contains? confirmed-durable-statuses (:status result))))
        (throw (ex-info "Durable barrier did not confirm publication"
                        {:type ::durable-barrier-unconfirmed
                         :status (:status result)})))
      result)))

(defn- complete-batch! [connection state wrote?]
  (when wrote?
    (persistence-barrier! connection state true))
  true)

(defn- close-signal! [connection owned? expected-signals state signal]
  ;; Claim the terminal close in the same atomic transition that records the
  ;; last signal.  Marking the connection closed only after the external call
  ;; leaves a window where another signal can invoke .close a second time.
  (let [[old new]
        (swap-vals!
         state
         (fn [snapshot]
           (let [next (update snapshot :closed-signals conj signal)]
             (if (and owned?
                      (not (:connection-close-claimed? next))
                      (every? (:closed-signals next) expected-signals))
               (assoc next
                      :connection-close-claimed? true
                      :connection-close-status :closing)
               next))))
        claimed? (and (not (:connection-close-claimed? old))
                      (:connection-close-claimed? new))]
    (if-not claimed?
      ;; A shutdown racing the owner observes that close has been accepted.
      ;; Once it completes, every repeated shutdown returns its stable result.
      (if (contains? new :connection-close-result)
        (:connection-close-result new)
        true)
      (try
        (.close connection)
        (swap! state assoc
               :connection-closed? true
               :connection-close-status :closed
               :connection-close-result true)
        true
        (catch Throwable error
          ;; Closing an owned native handle is terminal even on failure: a
          ;; blind retry could double-free a resource that closed partially.
          ;; Keep the failed claim and expose the original error diagnostically.
          (swap! state assoc
                 :connection-closed? false
                 :connection-close-status :failed
                 :connection-close-result false
                 :connection-close-error error
                 :last-error error)
          false)))))

(defrecord ChdbExporter [connection owned? expected-signals state]
  export/SpanExporter
  (export-spans! [_ spans]
    (if-not (signal-open? owned? expected-signals state :spans)
      false
      (try
        (when (seq spans)
          (let [{:keys [typed-span-projector typed-span-insert durable?]} @state]
            (insert-json-rows! connection (or typed-span-insert trace-insert)
                               (map #(span-row % typed-span-projector) spans)
                               (boolean durable?))))
        (complete-batch! connection state (boolean (seq spans)))
        (catch Throwable e
          (swap! state assoc :last-error e)
          false))))
  (flush-exporter! [_]
    (try
      (persistence-barrier! connection state false)
      true
      (catch Throwable e
        (swap! state assoc :last-error e)
        false)))
  (shutdown-exporter! [_]
    (close-signal! connection owned? expected-signals state :spans))

  export/MetricExporter
  (export-metrics! [_ resource collected]
    (if-not (signal-open? owned? expected-signals state :metrics)
      false
      (try
        (let [rows (vec
                    (for [{:keys [scope metrics]} collected
                          metric metrics
                          point (:data-points metric)
                          :let [row (first
                                     (metric-rows
                                      resource
                                      [{:scope scope
                                        :metrics
                                        [(assoc metric :data-points [point])]}]))]]
                      (assoc row :_type (:type metric))))]
          (doseq [type [:gauge :sum :histogram]]
            (export-metric-type! connection type rows
                                 (boolean (:durable? @state))))
          (complete-batch! connection state (boolean (seq rows))))
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
          (insert-json-rows! connection log-insert (map log-row records)
                             (boolean (:durable? @state))))
        (complete-batch! connection state (boolean (seq records)))
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
  selected database. With :durable? true, startup requires a Durable writer and,
  when this exporter creates the schema, checkpoints it; every non-empty logical
  signal batch returns true only after its WAL flush commits or reconciles.
  :persistence-barrier supplies the same post-batch contract for another
  persistence implementation and is mutually exclusive with :durable?.
  :typed-span-descriptors accepts only the opaque capability returned in an
  active `install-approved!` result; the generic SpanAttributes map remains."
  ([] (exporter {}))
  ([{:keys [connection db-spec create-schema? signals durable?
            persistence-barrier typed-span-descriptors]
     :or {db-spec "chdb::memory:" create-schema? true
          signals #{:spans :metrics} durable? false}}]
   (when (and persistence-barrier (not (ifn? persistence-barrier)))
     (throw (ex-info ":persistence-barrier must be callable"
                     {:type ::invalid-persistence-barrier})))
   (when (and durable? persistence-barrier)
     (throw (ex-info "Choose :durable? or :persistence-barrier, not both"
                     {:type ::ambiguous-persistence-barrier})))
   (when (and typed-span-descriptors (nil? connection))
     (throw (ex-info "Typed span descriptors require their explicit install connection"
                     {:type ::typed-descriptors-require-connection})))
   (let [owned? (nil? connection)
         conn (or connection (jdbc/connection db-spec))
         typed-fields (when typed-span-descriptors
                        (attribute-projection/confirmed-span-fields
                         typed-span-descriptors conn))
         typed-projector (when typed-span-descriptors
                           (attribute-projection/span-projector
                            typed-span-descriptors conn))
         typed-insert (when (seq typed-fields)
                        (trace-insert-spec
                         (mapcat (fn [{:keys [physical]}]
                                   [(:value-column physical)
                                    (:status-column physical)])
                                 typed-fields)))
         barrier (if durable? durable/flush! persistence-barrier)]
     (try
       (when durable?
         (when-not (= :writer (durable/connection-role conn))
           (throw (ex-info "Durable telemetry export requires a writer connection"
                           {:type ::durable-writer-required}))))
       (when create-schema? (schema/ensure-schema! conn))
       ;; A full checkpoint makes the schema independently recoverable before
       ;; the exporter can acknowledge its first telemetry batch.
       (when (and durable? create-schema?)
         (let [result
               (context/with-instrumentation-suppressed
                 (durable/checkpoint! conn))]
           (when-not (contains? confirmed-durable-statuses (:status result))
             (throw (ex-info "Durable schema checkpoint was not confirmed"
                             {:type ::durable-checkpoint-unconfirmed
                              :status (:status result)})))))
       (->ChdbExporter conn owned? (set signals)
                       (atom {:closed-signals #{}
                              :connection-close-claimed? false
                              :connection-close-status :open
                              :connection-closed? false
                              :persistence-barrier barrier
                              :typed-span-projector typed-projector
                              :typed-span-insert typed-insert
                              :durable? durable?
                              :last-error nil}))
       (catch Throwable t
         (when owned? (.close conn))
         (throw t))))))

(defn last-error [exporter] (:last-error @(:state exporter)))
