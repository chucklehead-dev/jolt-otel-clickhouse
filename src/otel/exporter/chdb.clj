(ns otel.exporter.chdb
  "Direct Jolt OTel exporter for an embedded/in-process chDB database."
  (:require [db.jdbc]
            [db.jdbc-shim :as jdbc-shim]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [jdbc.chdb :as chdb]
            [jdbc.chdb.native :as native]
            [jdbc.chdb.durable :as durable]
            [jdbc.core :as jdbc]
            [jdbc.proto :as jdbc-proto]
            [otel.any-value :as any]
            [otel.context :as context]
            [otel.exporter.chdb.attribute-projection :as attribute-projection]
            [otel.exporter.chdb.schema :as schema]
            [otel.otlp.any-value :as wire-any]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(defn- key-string [k]
  (cond (string? k) k (keyword? k) (subs (str k) 1) :else (str k)))

(defn- pdata-raw [v]
  (cond
    (any/empty-value? v) nil
    (any/bytes? v) (:bytesValue (wire-any/encode v))
    (map? v) (into (empty v)
                   (map (fn [[key value]] [key (pdata-raw value)]) v))
    (sequential? v) (mapv pdata-raw v)
    :else v))

(defn- canonical-value-string [v]
  (cond
    (any/empty-value? v) ""
    (any/bytes? v) (:bytesValue (wire-any/encode v))
    (or (sequential? v) (map? v)) (json/write-str (pdata-raw v))
    :else (str v)))

(defn- value-string [v]
  ;; Match the collector's pdata Value.AsString for every representable value.
  ;; Canonical special AnyValues are records and must be recognized before the
  ;; generic map branch. Invalid direct-SDK values retain OTel's established
  ;; readable fallback rather than acquiring a different JSON interpretation.
  (if (nil? v)
    ""
    (let [canonical (any/canonicalize v)]
      (if (:error canonical)
        (pr-str v)
        (canonical-value-string (:value canonical))))))

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
  ;; Pinned libchdb package 26.7.3 / SQL engine 26.7.2.1 reads JSONEachRow
  ;; integer DateTime64(9) values as raw nanosecond ticks (pre-26.8 semantics).
  ;; Preserve exact integers through maintained data.json, including epoch zero.
  ;; A future engine upgrade must requalify this wire; 26.8 changes integers.
  (when-not (and (integer? nanos) (<= 0 nanos 9223372036854775807))
    (throw (ex-info "Telemetry timestamp exceeds DateTime64 nanosecond domain"
                    {:type ::invalid-timestamp-nanos})))
  nanos)

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
       (typed-projector span)
       {}))))

(defn- log-row [record typed-projector]
  (let [scope (:scope record)
        resource (:resource record)]
    (merge
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
      "EventName" (or (:event-name record) "")}
     (if typed-projector (typed-projector record) {}))))

(def ^:private log-insert-query
  (str "insert into otel_logs ("
       (str/join ", " schema/clickstack-log-insert-columns)
       ")"))

(defn- selected-log-insert-query [typed-projector]
  ;; A confirmed typed projection adds installer-owned columns beyond the
  ;; pinned compatibility list. The unqualified table insert lets JSONEachRow
  ;; name those additive fields; omitted unrelated table columns keep defaults.
  (if typed-projector "insert into otel_logs" log-insert-query))

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
    (context/with-instrumentation-suppressed
      (jdbc/execute! connection (str query " FORMAT JSONEachRow\n" payload)))))

(defn- valid-json-value? [value]
  (cond
    (or (nil? value) (string? value) (boolean? value)) true
    (integer? value) (<= -9223372036854775808 value 9223372036854775807)
    (float? value) (and (= value value)
                        (<= -1.7976931348623157E308 value 1.7976931348623157E308))
    (map? value) (and (every? string? (keys value))
                     (every? valid-json-value? (vals value)))
    (sequential? value) (every? valid-json-value? value)
    :else false))

(defn- uint64? [value]
  (and (integer? value) (<= 0 value 18446744073709551615N)))

(defn- valid-row-value? [column value]
  ;; These are exporter-owned physical UInt64 domains, not promoted Int64
  ;; attributes. Preserve the full integer domain supported by JSONEachRow.
  (case column
    "Timestamp" (and (integer? value) (<= 0 value 9223372036854775807))
    "Events.Timestamp" (and (sequential? value)
                            (every? #(and (integer? %) (<= 0 % 9223372036854775807)) value))
    "Duration" (uint64? value)
    "Count" (uint64? value)
    "BucketCounts" (and (sequential? value) (every? uint64? value))
    (valid-json-value? value)))

(defn- ordinary-payload [columns rows]
  ;; Check every row before encoding or entering the driver. Error data never
  ;; retains row values, attribute names, or the encoded telemetry payload.
  (let [rows (vec rows)
        expected (set columns)]
    (doseq [row rows]
      (when-not (and (map? row) (= expected (set (keys row)))
                     (every? string? (keys row))
                     (every? (fn [[column value]] (valid-row-value? column value)) row))
        (throw (ex-info "Invalid chDB telemetry row"
                        {:type ::invalid-ordinary-row}))))
    ;; Remaining space stays in [0, 8 MiB]: no unchecked sum or multiplication
    ;; of an untrusted size, and no oversized concatenated payload allocation.
    (let [[parts _]
          (reduce (fn [[parts remaining] row]
                    (let [part (str (json/write-str row) "\n")
                          size (alength (.getBytes part "UTF-8"))]
                      (when (> size remaining)
                        (throw (ex-info "chDB telemetry export batch exceeds 8 MiB"
                                        {:limit max-insert-bytes})))
                      [(conj parts part) (- remaining size)]))
                  [[] max-insert-bytes] rows)]
      (apply str parts))))

(defn- insert-batch! [connection state table columns query rows]
  (if (:durable? @state)
    ;; Durable V1 records exact materialized SQL; its preparation, WAL and
    ;; acknowledgement protocol must not enter the ordinary transport.
    (insert-json-rows! connection query rows)
    (let [payload (ordinary-payload columns rows)]
      (context/with-instrumentation-suppressed
        (chdb/insert-json-rows! connection table columns payload)))))

(defn- typed-columns [fields]
  (vec (mapcat (fn [field]
                 [(get-in field [:physical :value-column])
                  (get-in field [:physical :status-column])]) fields)))

(defn- temporality-code [value]
  (case value :delta 1 :cumulative 2 0))

(def ^:private empty-metric-exemplars
  {"Exemplars.FilteredAttributes" []
   "Exemplars.TimeUnix" []
   "Exemplars.Value" []
   "Exemplars.SpanId" []
   "Exemplars.TraceId" []})

(defn- metric-rows
  ([resource collected] (metric-rows resource collected nil))
  ([resource collected typed-metric-projectors]
   (for [{:keys [scope metrics]} collected
         metric metrics
         point (:data-points metric)
         :let [typed-projector (get typed-metric-projectors (:type metric))
               typed-context {:resource resource :scope scope :point point}]]
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
       :gauge (merge {"Value" (double (:value point))}
                     (if typed-projector
                       (typed-projector typed-context)
                       {}))
       :sum (merge {"Value" (double (:value point))
                    "AggregationTemporality" (temporality-code (:temporality metric))
                    "IsMonotonic" (boolean (:monotonic? metric))}
                   (if typed-projector
                     (typed-projector typed-context)
                     {}))
       :histogram {"Count" (:count point)
                   "Sum" (double (:sum point))
                   "BucketCounts" (:bucket-counts point)
                   "ExplicitBounds" (:explicit-bounds metric)
                   "Min" (double (or (:min point) 0.0))
                   "Max" (double (or (:max point) 0.0))
                   "AggregationTemporality" (temporality-code (:temporality metric))})))))

(defn- metric-insert-columns [state type]
  (into (get schema/clickstack-metric-insert-columns type)
        (get-in @state [:typed-metric-columns type] [])))

(defn- metric-insert-query [type columns]
  (str "insert into " (get schema/metric-table-names type)
       " (" (str/join ", " columns) ")"))

(defn- export-metric-type! [connection state type rows]
  (let [selected (filter #(= type (:_type %)) rows)]
    (when (seq selected)
      (let [columns (metric-insert-columns state type)]
        (insert-batch! connection state (get schema/metric-table-names type)
                         columns (metric-insert-query type columns)
                         (map #(dissoc % :_type) selected))))))

(defn- export-metric-rows! [connection state rows]
  (if (:durable? @state)
    (doseq [type [:gauge :sum :histogram]]
      (export-metric-type! connection state type rows))
    ;; Eagerly validate and encode every physical batch before the first driver
    ;; call. Native execution failures can still partially apply a logical
    ;; batch: this transport does not promise an atomic transaction/rollback.
    (let [prepared
          (vec (for [type [:gauge :sum :histogram]
                     :let [selected (vec (map #(dissoc % :_type)
                                               (filter #(= type (:_type %)) rows)))]
                     :when (seq selected)
                     :let [columns (metric-insert-columns state type)
                           payload (ordinary-payload columns selected)]]
                 [(get schema/metric-table-names type) columns payload]))]
      (context/with-instrumentation-suppressed
        (doseq [[table columns payload] prepared]
          (chdb/insert-json-rows! connection table columns payload))))))

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
          (insert-batch! connection state "otel_traces"
                             (:span-insert-columns @state) "insert into otel_traces"
                             (map #(span-row % (:typed-span-projector @state))
                                  spans)))
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
                                        [(assoc metric :data-points [point])]}]
                                      (:typed-metric-projectors @state)))]]
                      (assoc row :_type (:type metric))))]
          (export-metric-rows! connection state rows)
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
          (let [typed-projector (:typed-log-projector @state)]
            (insert-batch! connection state "otel_logs"
                               (:log-insert-columns @state)
                               (selected-log-insert-query typed-projector)
                               (map #(log-row % typed-projector) records))))
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
  Ordinary connections must expose the chDB driver context; startup rejects
  other drivers before schema mutation. Durable connections must explicitly
  opt into :durable? true; they never fall back from the ordinary row-data API.
  :typed-span-descriptors, :typed-log-descriptors, :typed-gauge-descriptors,
  and :typed-sum-descriptors accept only opaque
  capabilities returned in active `install-approved!` results. They project
  their respective attributes while the compatible generic maps remain
  unchanged. Gauge descriptors cover point, resource, and scope attributes on
  `otel_metrics_gauge`; sum descriptors cover point attributes on
  `otel_metrics_sum`. Sum resource/scope and all histogram descriptors remain
  intentionally unsupported."
  ([] (exporter {}))
  ([{:keys [connection db-spec create-schema? signals durable?
            persistence-barrier typed-span-descriptors typed-log-descriptors
            typed-gauge-descriptors typed-sum-descriptors]
     :or {db-spec "chdb::memory:" create-schema? true
          signals #{:spans :metrics} durable? false}}]
   (when (and persistence-barrier (not (ifn? persistence-barrier)))
     (throw (ex-info ":persistence-barrier must be callable"
                     {:type ::invalid-persistence-barrier})))
   (when (and durable? persistence-barrier)
     (throw (ex-info "Choose :durable? or :persistence-barrier, not both"
                     {:type ::ambiguous-persistence-barrier})))
   (when (and (or typed-span-descriptors typed-log-descriptors typed-gauge-descriptors
                  typed-sum-descriptors)
              (nil? connection))
     (throw (ex-info "Typed descriptors require their explicit install connection"
                     {:type ::typed-descriptors-require-connection})))
   (let [owned? (nil? connection)
         conn (or connection (jdbc/connection db-spec))
         typed-span-projector (when typed-span-descriptors
                                (attribute-projection/trace-projector
                                 typed-span-descriptors conn))
         typed-log-projector (when typed-log-descriptors
                               (attribute-projection/log-projector
                                typed-log-descriptors conn))
         typed-gauge-projector (when typed-gauge-descriptors
                                 (attribute-projection/gauge-projector
                                  typed-gauge-descriptors conn))
         typed-sum-projector (when typed-sum-descriptors
                               (attribute-projection/sum-projector
                                typed-sum-descriptors conn))
         span-columns (into (into schema/clickstack-trace-insert-columns
                                  ["EventsJSON" "LinksJSON"])
                            (when typed-span-descriptors
                              (typed-columns
                               (attribute-projection/confirmed-span-fields
                                typed-span-descriptors conn))))
         log-columns (into schema/clickstack-log-insert-columns
                           (when typed-log-descriptors
                             (typed-columns
                              (attribute-projection/confirmed-log-fields
                               typed-log-descriptors conn))))
         gauge-columns (vec (when typed-gauge-descriptors
                              (typed-columns
                               (attribute-projection/confirmed-gauge-fields
                                typed-gauge-descriptors conn))))
         sum-columns (vec (when typed-sum-descriptors
                            (typed-columns
                             (attribute-projection/confirmed-sum-fields
                              typed-sum-descriptors conn))))
         barrier (if durable? durable/flush! persistence-barrier)]
     (try
       (if durable?
         (when-not (= :writer (durable/connection-role conn))
           (throw (ex-info "Durable telemetry export requires a writer connection"
                           {:type ::durable-writer-required})))
         ;; The ordinary transport is chDB-specific. Validate its public driver
         ;; context before schema mutation; do not infer Durable by catching
         ;; failed probes or retry SQL after a rejected ordinary insertion.
         (jdbc-shim/driver-context (jdbc-proto/connection conn) :chdb))
       ;; Library overrides may satisfy the driver's compatibility minimum yet
       ;; change integer DateTime64 semantics. Fence the actual package before
       ;; any exporter DDL/checkpoint; neither ordinary nor Durable can bypass.
       (native/ensure-loaded!)
       (when-not (= "26.7.3" (native/chdb-version))
         (throw (ex-info "Unqualified chDB telemetry timestamp wire"
                         {:type ::unqualified-timestamp-wire})))
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
                              :typed-span-projector typed-span-projector
                              :typed-log-projector typed-log-projector
                              :typed-metric-projectors
                              (cond-> {}
                                typed-gauge-projector (assoc :gauge typed-gauge-projector)
                                typed-sum-projector (assoc :sum typed-sum-projector))
                              :span-insert-columns span-columns
                              :log-insert-columns log-columns
                              :typed-metric-columns {:gauge gauge-columns
                                                     :sum sum-columns}
                              :durable? durable?
                              :last-error nil}))
       (catch Throwable t
         ;; A failed ownership cleanup must not replace the startup failure.
         (when owned? (try (.close conn) (catch Throwable _ nil)))
         (throw t))))))

(defn last-error [exporter] (:last-error @(:state exporter)))
