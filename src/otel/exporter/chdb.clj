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

(def ^:private max-insert-bytes (* 8 1024 1024))

(def ^:private untyped-span-shape
  {:name "shape" :start-time-unix-nano 0 :end-time-unix-nano 0
   :events [] :links [] :attributes {} :resource {:attributes {}}})

;; Each accessor is selected once at construction. There is no per-row column
;; name lookup/case dispatch and no materialized outer physical row map.
(def ^:private untyped-span-accessors
  {"Timestamp" #(timestamp (:start-time-unix-nano %))
   "TraceId" #(or (get-in % [:span-context :trace-id]) "")
   "SpanId" #(or (get-in % [:span-context :span-id]) "")
   "ParentSpanId" #(or (:parent-span-id %) "")
   "TraceState" #(trace-state-string (get-in % [:span-context :trace-state]))
   "SpanName" :name
   "SpanKind" #(otel-enum-string (:kind %) :internal)
   "ServiceName" #(service-name (:resource %) "unknown_service:jolt")
   "ResourceAttributes" #(attrs (get-in % [:resource :attributes]))
   "ScopeName" #(or (get-in % [:scope :name]) "")
   "ScopeVersion" #(or (get-in % [:scope :version]) "")
   "SpanAttributes" #(attrs (:attributes %))
   "Duration" #(max 0 (- (:end-time-unix-nano %) (:start-time-unix-nano %)))
   "StatusCode" #(otel-enum-string (get-in % [:status :code]) :unset)
   "StatusMessage" #(or (get-in % [:status :description]) "")
   "EventsJSON" #(json/write-str (or (:events %) []))
   "LinksJSON" #(json/write-str (or (:links %) []))
   "Events.Timestamp" #(mapv (fn [e] (timestamp (:timestamp-unix-nano e))) (or (:events %) []))
   "Events.Name" #(mapv (fn [e] (or (:name e) "")) (or (:events %) []))
   "Events.Attributes" #(mapv (fn [e] (attrs (:attributes e))) (or (:events %) []))
   "Links.TraceId" #(mapv (fn [e] (or (get-in e [:span-context :trace-id]) "")) (or (:links %) []))
   "Links.SpanId" #(mapv (fn [e] (or (get-in e [:span-context :span-id]) "")) (or (:links %) []))
   "Links.TraceState" #(mapv (fn [e] (trace-state-string (get-in e [:span-context :trace-state]))) (or (:links %) []))
   "Links.Attributes" #(mapv (fn [e] (attrs (:attributes e))) (or (:links %) []))})

;; These columns are common enough in trace batches that retaining their
;; already-produced `data.json` bytes within one payload avoids repeatedly
;; rebuilding the same scalar attribute maps.  The cache is deliberately
;; batch-local: it neither retains telemetry after an acknowledged call nor
;; crosses exporter threads.
(def ^:private untyped-span-attribute-accessors
  {"ResourceAttributes" #(get-in % [:resource :attributes])
   "SpanAttributes" :attributes})

(def ^:private untyped-attribute-cache-max-entries 64)
(def ^:private untyped-attribute-cache-max-chars (* 1024 1024))

(def ^:dynamic ^:private *untyped-attribute-wire-cache* nil)

(defn- untyped-attribute-cache []
  {:entries (java.util.HashMap.)
   :chars (java.util.concurrent.atomic.AtomicLong. 0)})

(defn- cached-untyped-attrs-wire [source]
  ;; `data.json` emits map entries in the received map's iteration order.
  ;; Keep that ordered source sequence in the key rather than treating maps
  ;; with equal contents as wire-equivalent.  Cached values are exact output
  ;; from the maintained writer, never a second JSON implementation.
  (let [source (or source {})
        cache *untyped-attribute-wire-cache*]
    (if-not cache
      (json/write-str (attrs source))
      (let [entries (:entries cache)
            key (vec source)
            cached (.get entries key)]
        (if cached
          cached
          (let [encoded (json/write-str (attrs source))
                size (count encoded)
                chars (:chars cache)]
            (when (and (< (.size entries) untyped-attribute-cache-max-entries)
                       (<= (+ (.get chars) size) untyped-attribute-cache-max-chars))
              (.put entries key encoded)
              (.addAndGet chars size))
            encoded))))))

(defn- untyped-scalar? [x] (or (nil? x) (string? x) (boolean? x)
                         (and (integer? x) (<= -9223372036854775808 x 9223372036854775807))))
(defn- untyped-attributes? [x]
  (or (nil? x) (and (map? x) (every? string? (keys x)) (every? untyped-scalar? (vals x)))))
(defn- untyped-span-eligible? [span]
  ;; Narrow, pure shape check. Rejected shapes use the original whole-row path,
  ;; preserving its validation order and errors. No acceptance rules change.
  (and (map? span)
       (every? #(or (nil? %) (map? %)) [(:span-context span) (:scope span) (:resource span) (:status span)])
       (every? untyped-attributes? [(:attributes span) (get-in span [:resource :attributes])])
       (every? #(and (integer? %) (<= 0 % 9223372036854775807))
               [(:start-time-unix-nano span) (:end-time-unix-nano span)])
       (every? #(or (nil? %) (string? %))
               [(:name span) (:parent-span-id span) (get-in span [:span-context :trace-id])
                (get-in span [:span-context :span-id]) (get-in span [:span-context :trace-state])
                (get-in span [:scope :name]) (get-in span [:scope :version])
                (get-in span [:status :description])])
       (every? #(or (nil? %) (keyword? %)) [(:kind span) (get-in span [:status :code])])
       (or (nil? (:links span)) (and (vector? (:links span)) (empty? (:links span))))
       (or (nil? (:events span))
           (and (vector? (:events span))
                (every? #(and (map? %) (integer? (:timestamp-unix-nano %))
                              (<= 0 (:timestamp-unix-nano %) 9223372036854775807)
                              (or (nil? (:name %)) (string? (:name %)))
                              (untyped-attributes? (:attributes %))) (:events span))))))

;; A confirmed typed projector may add physical columns, but it need not force
;; construction of the outer row map. Retain the projector's value/status rules
;; and derive the precise persistent-map iteration order from the same row
;; constructor used by the fallback. If its keys overlap a base column or the
;; shape cannot be compiled, startup keeps the original row-map path.
(defn- compile-untyped-span-encoder
  ([] (compile-untyped-span-encoder nil))
  ([typed-projector]
   (let [order (vec (keys (span-row untyped-span-shape typed-projector)))
        base-columns (set (keys untyped-span-accessors))
        typed-shape (when typed-projector (typed-projector untyped-span-shape))]
    (when (and (= (set order) (into base-columns (keys typed-shape)))
               (empty? (filter base-columns (keys typed-shape))))
      (let [empty-links-wire
            {"LinksJSON" (json/write-str (json/write-str []))
             "Links.TraceId" (json/write-str [])
             "Links.SpanId" (json/write-str [])
             "Links.TraceState" (json/write-str [])
             "Links.Attributes" (json/write-str [])}
            slots (mapv (fn [index column]
                          (let [value-at (get untyped-span-accessors column)
                                attribute-at (get untyped-span-attribute-accessors column)
                                write-value
                                (cond
                                  (contains? empty-links-wire column)
                                  (let [wire (get empty-links-wire column)]
                                    (fn [out _ _] (.write out wire)))

                                  attribute-at
                                  (fn [out span _]
                                    (.write out (cached-untyped-attrs-wire (attribute-at span))))

                                  value-at
                                  (fn [out span _]
                                    (json/write (value-at span) out))

                                  :else
                                  (fn [out _ typed-values]
                                    (json/write (get typed-values column) out)))]
                            [(str (if (zero? index) "{" ",") (json/write-str column) ":")
                             write-value])) (range) order)
            emit (reduce (fn [next [fragment write-value]]
                           (fn [out span typed-values]
                             (.append out fragment)
                             (write-value out span typed-values)
                             (next out span typed-values)))
                         (fn [out _ _] (.append out "}")) (reverse slots))]
        (fn [span]
          (if (untyped-span-eligible? span)
            (let [out (java.io.StringWriter.)
                  typed-values (when typed-projector (typed-projector span))]
              (emit out span typed-values)
              (.toString out))
            (json/write-str (span-row span typed-projector)))))))))

(defn- untyped-span-payload
  ([encoder spans] (untyped-span-payload encoder spans max-insert-bytes))
  ([encoder spans limit]
   ;; Do not use mapv/map here: even chunked map can evaluate later rows before
   ;; the current row's limit check. Only request next after acceptance.
   (binding [*untyped-attribute-wire-cache* (untyped-attribute-cache)]
     (loop [remaining limit rows (seq spans) out (StringBuilder.)]
       (if rows
         (let [encoded (encoder (first rows))
               size (inc (alength (.getBytes encoded "UTF-8")))]
           (when (> size remaining)
             (throw (ex-info "chDB telemetry export batch exceeds 8 MiB" {:limit limit})))
           (.append out encoded) (.append out "\n")
           (recur (- remaining size) (next rows) out))
         (.toString out))))))


(def ^:private untyped-span-encoder (delay (compile-untyped-span-encoder)))

(defn- compile-typed-span-encoder [projector]
  ;; A nil result is the intentional structural fallback (for example, a
  ;; promoted column collides with a base column). An exception is not an
  ;; eligibility result: fail construction instead of silently disabling the
  ;; fast path. Do not retain the cause, which may contain attribute values.
  (try
    (compile-untyped-span-encoder projector)
    (catch Throwable _
      (throw (ex-info "Typed span encoder compilation failed"
                      {:type ::typed-span-encoder-compilation-failed})))))

(defn- json-each-row-payload
  "Encode one batch as JSONEachRow with the maintained data.json defaults.

  The builder is deliberately local to this call: exporters may run concurrently
  and neither a reusable buffer nor a changed JSON option set is safe at this
  boundary. It retains data.json's per-row string encoding, but eliminates the
  intermediate sequence and final apply/str pass. Each full row is checked
  before it can be appended past the 8 MiB payload bound."
  [rows]
  (loop [remaining max-insert-bytes
         rows (seq rows)
         out (StringBuilder.)]
    (if-let [row (first rows)]
      (let [encoded (json/write-str row)
            bytes (inc (alength (.getBytes encoded "UTF-8")))]
        (when (> bytes remaining)
          (throw (ex-info "chDB telemetry export batch exceeds 8 MiB"
                          {:limit max-insert-bytes})))
        (.append out encoded)
        (.append out "\n")
        (recur (- remaining bytes) (next rows) out))
      (.toString out))))

(defn- insert-json-rows!
  "Insert one SDK-bounded batch through chDB's ordinary query API. libchdb
  26.7's streaming-insert API corrupts ClickHouse ThreadStatus nesting under
  long-lived multi-signal exporters; the query API does not share that path."
  [connection query rows]
  (let [payload (json-each-row-payload rows)]
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

(declare validate-ordinary-row!)

(defn- ordinary-payload [columns rows]
  ;; Check every row before encoding or entering the driver. Error data never
  ;; retains row values, attribute names, or the encoded telemetry payload.
  (let [rows (vec rows)]
    (doseq [row rows]
      (validate-ordinary-row! columns row))
    ;; The batch-local StringBuilder checks each complete maintained data.json
    ;; row plus LF in UTF-8 before appending, preserving the pre-overflow
    ;; rejection boundary while avoiding chunk sequencing/final concatenation.
    (json-each-row-payload rows)))

(defn- jolt-runtime?
  "The direct log writer is a Jolt-only implementation detail.

  JVM Clojure and Babashka keep the maintained map/data.json path even though
  both happen to provide StringBuilder.  The property is set by Jolt itself;
  checking it before forcing the delayed encoder keeps this a capability gate,
  not a host-specific change in JSON behavior."
  []
  (string? (System/getProperty "jolt.version")))

(def ^:private untyped-log-shape
  {:timestamp-unix-nano 0 :observed-time-unix-nano 0
   :attributes {} :resource {:attributes {}} :scope {:attributes {}}})

;; Accessors are selected once when the closed ClickStack log schema is
;; confirmed below.  The accepted path does not construct an intermediate
;; physical row map; unknown SDK shapes retain `log-row` and data.json.
(def ^:private untyped-log-accessors
  {"Timestamp" #(timestamp (log-timestamp-nanos %))
   "TraceId" #(or (:trace-id %) "")
   "SpanId" #(or (:span-id %) "")
   "TraceFlags" #(uint8 (:trace-flags %))
   "SeverityText" #(or (:severity-text %) "")
   "SeverityNumber" #(uint8 (:severity-number %))
   "ServiceName" #(service-name (:resource %) "")
   "Body" #(value-string (:body %))
   "ResourceSchemaUrl" #(or (get-in % [:resource :schema-url]) "")
   "ResourceAttributes" #(attrs (get-in % [:resource :attributes]))
   "ScopeSchemaUrl" #(or (get-in % [:scope :schema-url]) "")
   "ScopeName" #(or (get-in % [:scope :name]) "")
   "ScopeVersion" #(or (get-in % [:scope :version]) "")
   "ScopeAttributes" #(attrs (get-in % [:scope :attributes]))
   "LogAttributes" #(attrs (:attributes %))
   "EventName" #(or (:event-name %) "")})

;; Attribute maps recur heavily in collector log batches: resource and scope
;; maps are normally shared by every record, and application log attributes
;; often repeat too.  Preserve data.json as the only JSON authority, but keep
;; its exact already-produced wire text in a bounded cache for this one
;; payload.  In particular, map equality is not sufficient: data.json follows
;; the supplied map's iteration order, so the ordered entry vector is part of
;; the key.  The dynamic binding below gives the cache request-local lifetime;
;; it cannot retain telemetry across exporter calls or threads.
(def ^:private untyped-log-attribute-accessors
  {"ResourceAttributes" #(get-in % [:resource :attributes])
   "ScopeAttributes" #(get-in % [:scope :attributes])
   "LogAttributes" :attributes})

(def ^:private untyped-log-attribute-cache-max-entries 64)
(def ^:private untyped-log-attribute-cache-max-chars (* 1024 1024))

(def ^:dynamic ^:private *untyped-log-attribute-wire-cache* nil)

(defn- untyped-log-attribute-cache []
  {:entries (java.util.HashMap.)
   :chars (java.util.concurrent.atomic.AtomicLong. 0)})

(defn- utf8-byte-count [text]
  ;; This is deliberately byte based rather than `(count text)`: Jolt's
  ;; strings are not an UTF-8 byte container.  The direct batch renderer uses
  ;; this only for data.json-owned attribute wires; its scalar subset is ASCII
  ;; and has constant-time byte counts below.  Keeping this authority here
  ;; makes a future native byte counter an isolated substitution, not a wire
  ;; semantic change.
  (alength (.getBytes text "UTF-8")))

(defn- cached-untyped-log-attrs-entry [source]
  (let [source (or source {})
        cache *untyped-log-attribute-wire-cache*]
    (if-not cache
      (let [wire (json/write-str (attrs source))]
        {:wire wire :utf8-bytes (utf8-byte-count wire)})
      (let [entries (:entries cache)
            ;; Do not use `source` itself here: associative equality erases
            ;; insertion order, while JSON bytes must preserve it.
            key (vec source)
            cached (.get entries key)]
        (if cached
          cached
          (let [encoded (json/write-str (attrs source))
                size (count encoded)
                entry {:wire encoded :utf8-bytes (utf8-byte-count encoded)}
                chars (:chars cache)]
            (when (and (< (.size entries) untyped-log-attribute-cache-max-entries)
                       (<= (+ (.get chars) size) untyped-log-attribute-cache-max-chars))
              (.put entries key entry)
              (.addAndGet chars size))
            entry))))))

(defn- cached-untyped-log-attrs-wire [source]
  (:wire (cached-untyped-log-attrs-entry source)))

(defn- plain-json-string? [value]
  ;; data.json's default writer has the exact escaping authority.  This tiny
  ;; direct subset deliberately excludes every escaping/Unicode boundary,
  ;; including slash, so all such values continue through data.json.
  (and (string? value)
       (loop [index 0]
         (if (= index (.length value))
           true
           (let [code (int (.charAt value index))]
             (if (and (<= 32 code 126)
                      (not= code 34) (not= code 47) (not= code 92))
               (recur (inc index))
               false))))))

(defn- append-direct-log-scalar! [^StringBuilder out value]
  ;; Do not create a second general JSON encoder.  Dynamic attributes and any
  ;; string that needs escaping stay on the maintained public writer.
  (cond
    (nil? value) (.append out "null")
    (true? value) (.append out "true")
    (false? value) (.append out "false")
    (integer? value) (.append out (str value))
    (plain-json-string? value) (do (.append out (char 34))
                                  (.append out value)
                                  (.append out (char 34)))
    :else (json/write value out)))

(defn- batch-direct-log-scalar-bytes
  "Return an exact UTF-8 byte count for the direct scalar subset, or nil.
  Its strings are ASCII by admission and JSON numbers are ASCII by grammar."
  [value]
  (cond
    (nil? value) 4
    (true? value) 4
    (false? value) 5
    (integer? value) (count (str value))
    (plain-json-string? value) (+ 2 (count value))
    :else nil))

(defn- append-batch-direct-log-scalar! [^StringBuilder out value]
  (cond
    (nil? value) (.append out "null")
    (true? value) (.append out "true")
    (false? value) (.append out "false")
    (integer? value) (.append out (str value))
    :else (do (.append out (char 34)) (.append out value) (.append out (char 34)))))

(defn- untyped-log-eligible? [record]
  ;; Pure, conservative shape admission.  In particular, maps are accepted
  ;; only where `attrs` has its normal input; unusual application values take
  ;; the old row-construction route and therefore preserve its error/order.
  (and (map? record)
       (every? #(or (nil? %) (map? %)) [(:resource record) (:scope record)])
       (every? #(or (nil? %) (map? %))
               [(:attributes record)
                (get-in record [:resource :attributes])
                (get-in record [:scope :attributes])])
       (every? #(or (nil? %) (and (integer? %) (<= 0 % 9223372036854775807)))
               [(:timestamp-unix-nano record) (:observed-time-unix-nano record)])
       (every? #(or (nil? %) (string? %))
               [(:trace-id record) (:span-id record) (:severity-text record)
                (:event-name record) (get-in record [:resource :schema-url])
                (get-in record [:scope :schema-url]) (get-in record [:scope :name])
                (get-in record [:scope :version])])
       (every? #(or (nil? %) (integer? %))
               [(:trace-flags record) (:severity-number record)])))

(defn- log-key-fragments [order]
  (mapv (fn [index column]
          (str (if (zero? index) "{" ",") (json/write-str column) ":"))
        (range) order))

(defn- validate-ordinary-row! [columns row]
  (let [expected (set columns)]
    (when-not (and (map? row) (= expected (set (keys row)))
                   (every? string? (keys row))
                   (every? (fn [[column value]] (valid-row-value? column value)) row))
      (throw (ex-info "Invalid chDB telemetry row"
                      {:type ::invalid-ordinary-row}))))
  row)

(defn- compile-untyped-log-encoder []
  ;; Set equality is the schema fence; the legacy map construction order is the
  ;; byte-parity oracle. JSONEachRow is name-addressed, so insert-column order
  ;; need not equal data.json's map iteration order. A field addition/removal
  ;; returns nil and selects the generic path until consciously updated.
  (when (jolt-runtime?)
    (let [order (vec (keys (log-row untyped-log-shape nil)))]
      (when (= (set order) (set schema/clickstack-log-insert-columns))
        (when (= (set order) (set (keys untyped-log-accessors)))
          (let [fragments (log-key-fragments order)]
            (fn [record]
              (if-not (untyped-log-eligible? record)
                (let [row (log-row record nil)]
                  (validate-ordinary-row! order row)
                  (json/write-str row))
                (let [out (StringBuilder.)]
                  (loop [columns order fragments fragments]
                    (when-let [column (first columns)]
                      (let [attribute-at (get untyped-log-attribute-accessors column)
                            value (if attribute-at
                                    (cached-untyped-log-attrs-wire (attribute-at record))
                                    ((get untyped-log-accessors column) record))]
                        ;; Attribute wires come directly from `(attrs source)`
                        ;; and data.json, therefore represent the same valid
                        ;; Map(String,String) value the old accessor supplied.
                        ;; Other columns retain the existing explicit check.
                        (when (and (not attribute-at)
                                   (not (valid-row-value? column value)))
                          (throw (ex-info "Invalid chDB telemetry row"
                                          {:type ::invalid-ordinary-row})))
                        (.append out (first fragments))
                        (if attribute-at
                          (.append out value)
                          (append-direct-log-scalar! out value))
                        (recur (next columns) (next fragments)))))
                  (.append out \})
                  (.toString out))))))))))

(def ^:private untyped-log-encoder (delay (compile-untyped-log-encoder)))

(def ^:private generic-untyped-log-payload ::generic-untyped-log-payload)

(defn- compile-untyped-log-batch-encoder []
  ;; The row encoder above has an intentionally broader admitted subset: it
  ;; can delegate one scalar to data.json and then check the completed row.
  ;; This batch form has a stricter admission rule because it must know every
  ;; record's UTF-8 size *before* looking at the next record, without turning
  ;; each row into a final String.  Escaped/structured scalar values therefore
  ;; select the existing all-generic path for the whole batch.
  (when (jolt-runtime?)
    (let [order (vec (keys (log-row untyped-log-shape nil)))]
      (when (and (= (set order) (set schema/clickstack-log-insert-columns))
                 (= (set order) (set (keys untyped-log-accessors))))
        (let [fragments (log-key-fragments order)
              fragment-bytes (mapv utf8-byte-count fragments)
              emit (fn [records limit]
                     (loop [remaining limit
                   records (seq records)
                   out (StringBuilder.)]
              ;; Do not use `if-let`: false/nil raw records must choose the
              ;; generic route rather than ending the batch early.
              (if records
                (let [record (first records)]
                  (if-not (untyped-log-eligible? record)
                    generic-untyped-log-payload
                    (let [row-start-bytes 2 ; closing brace plus LF
                          row-bytes (volatile! row-start-bytes)
                          direct? (loop [columns order
                                         fragments fragments
                                         fragment-bytes fragment-bytes]
                                    (if-let [column (first columns)]
                                      (let [attribute-at (get untyped-log-attribute-accessors column)
                                            value (if attribute-at
                                                    (cached-untyped-log-attrs-entry
                                                     (attribute-at record))
                                                    ((get untyped-log-accessors column) record))]
                                        ;; No StringBuilder mutation happens
                                        ;; before the scalar's direct-admission
                                        ;; decision. A rejected field returns
                                        ;; the generic sentinel, and the
                                        ;; request-local builder is discarded.
                                        (if attribute-at
                                          (do (.append out (first fragments))
                                              (.append out (:wire value))
                                              (vswap! row-bytes + (first fragment-bytes)
                                                      (:utf8-bytes value))
                                              (recur (next columns) (next fragments)
                                                     (next fragment-bytes)))
                                          (let [scalar-bytes
                                                (batch-direct-log-scalar-bytes value)]
                                            (if (nil? scalar-bytes)
                                              false
                                              (do (.append out (first fragments))
                                                  (append-batch-direct-log-scalar! out value)
                                                  (vswap! row-bytes + (first fragment-bytes)
                                                          scalar-bytes)
                                                  (recur (next columns) (next fragments)
                                                         (next fragment-bytes)))))))
                                      true))]
                      (if-not direct?
                        generic-untyped-log-payload
                        (let [bytes @row-bytes]
                          ;; This is the pre-next-record boundary. The
                          ;; builder is request-local and has not reached a
                          ;; JDBC/Durable/WAL boundary at this point.
                          (when (> bytes remaining)
                            (throw (ex-info "chDB telemetry export batch exceeds 8 MiB"
                                            {:limit limit})))
                          (.append out \}) (.append out "\n")
                          (recur (- remaining bytes) (next records) out))))))
                (.toString out))))]
          (fn
            ([records] (emit records max-insert-bytes))
            ([records limit] (emit records limit))))))))

(def ^:private untyped-log-batch-encoder (delay (compile-untyped-log-batch-encoder)))

(defn- untyped-log-payload
  "Encode an all-eligible batch incrementally, preserving the
  no-later-record-after-overflow rule.

  A non-eligible raw record returns the generic payload sentinel before it is
  encoded. The caller then restarts the whole batch through the old log-row
  path: Durable retains its historical write semantics (which do not run
  ordinary-row validation), while ordinary transport retains its existing
  whole-batch validation. Do not pre-scan the input: that could observe a
  later record after an earlier direct row would overflow."
  [encoder records]
  ;; The selected encoder is Jolt-only; retain that boundary here as well so a
  ;; direct/private caller cannot accidentally change JVM or BB behavior.
  (binding [*untyped-log-attribute-wire-cache*
            (when (jolt-runtime?) (untyped-log-attribute-cache))]
    (loop [remaining max-insert-bytes records (seq records) out (StringBuilder.)]
      ;; Test sequence presence, not the record: raw SDK callers can supply a
      ;; falsey record, which must retain the ordinary `log-row` fallback rather
      ;; than terminating this batch and dropping subsequent records.
      (if records
        (let [record (first records)]
          (if-not (untyped-log-eligible? record)
            generic-untyped-log-payload
            (let [encoded (encoder record)
                  bytes (inc (alength (.getBytes encoded "UTF-8")))]
              (when (> bytes remaining)
                (throw (ex-info "chDB telemetry export batch exceeds 8 MiB"
                                {:limit max-insert-bytes})))
              (.append out encoded) (.append out "\n")
              (recur (- remaining bytes) (next records) out))))
        (.toString out)))))

(declare execute-durable-sql!)

(defn- insert-batch! [connection state table columns query rows]
  (if (:durable? @state)
    ;; Durable V1 records the exact materialized SQL.  Its execute and
    ;; publication acknowledgement are one writer request: splitting this
    ;; into JDBC execute! plus flush! would let another caller intervene.
    (execute-durable-sql!
     connection
     (str query " FORMAT JSONEachRow\n" (json-each-row-payload rows)))
    (let [payload (ordinary-payload columns rows)]
      (context/with-instrumentation-suppressed
        (chdb/insert-json-rows! connection table columns payload)))))

(defn- insert-untyped-log-records!
  "Jolt's closed untyped-log encoder is selected only after its schema fence.

  The generic `insert-batch!` remains the sole path for typed logs and for
  JVM/BB.  The selected Jolt path feeds the same exact JSONEachRow bytes into
  the same ordinary/Durable ownership boundaries; it only avoids physical row
  map construction for conservatively admitted SDK records."
  [connection state records]
  (let [snapshot @state
        typed-projector (:typed-log-projector snapshot)
        ;; This fixed writer owns precisely the untyped collector schema. A
        ;; constructed or mutated exporter state with a different ordered
        ;; column vector must retain insert-batch!'s generic validation and
        ;; query construction rather than pairing its payload with that state.
        encoder (when (and (not typed-projector)
                           (= (:log-insert-columns snapshot)
                              schema/clickstack-log-insert-columns))
                  @untyped-log-encoder)
        batch-encoder (when encoder @untyped-log-batch-encoder)]
    (if-not encoder
      (insert-batch! connection state "otel_logs"
                     (:log-insert-columns snapshot)
                     (selected-log-insert-query typed-projector)
                     (map #(log-row % typed-projector) records))
      (let [payload (if batch-encoder
                      (binding [*untyped-log-attribute-wire-cache*
                                (untyped-log-attribute-cache)]
                        (batch-encoder records))
                      (untyped-log-payload encoder records))
            query (selected-log-insert-query nil)]
        (if (= generic-untyped-log-payload payload)
          ;; Keep every non-admitted raw shape on the historical path. In
          ;; particular Durable deliberately serializes this path without the
          ;; ordinary transport's physical-row validation.
          (insert-batch! connection state "otel_logs"
                         (:log-insert-columns snapshot) query
                         (map #(log-row % nil) records))
          (if (:durable? snapshot)
            (execute-durable-sql!
             connection (str query " FORMAT JSONEachRow\n" payload))
            (context/with-instrumentation-suppressed
              (chdb/insert-json-rows! connection "otel_logs"
                                      (:log-insert-columns snapshot) payload))))))))

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
       "ScopeDroppedAttrCount" 0
       "ScopeSchemaUrl" (or (:schema-url scope) "")
       "ServiceName" (service-name resource "")
       "MetricName" (:name metric)
       "MetricDescription" (or (:description metric) "")
       "MetricUnit" (or (:unit metric) "")
       "Attributes" (attrs (:attributes point))
       "StartTimeUnix" (metric-timestamp (or (:start-time-unix-nano point) 0))
       "TimeUnix" (metric-timestamp (or (:time-unix-nano point) 0))
       "Flags" 0}
      (case (:type metric)
        :gauge (merge {"Value" (double (:value point))}
                      (if typed-projector (typed-projector typed-context) {}))
        :sum (merge {"Value" (double (:value point))
                     "AggregationTemporality" (temporality-code (:temporality metric))
                     "IsMonotonic" (boolean (:monotonic? metric))}
                    (if typed-projector (typed-projector typed-context) {}))
        :histogram (merge {"Count" (:count point)
                           "Sum" (double (:sum point))
                           "BucketCounts" (:bucket-counts point)
                           "ExplicitBounds" (:explicit-bounds metric)
                           "Min" (double (or (:min point) 0.0))
                           "Max" (double (or (:max point) 0.0))
                           "AggregationTemporality" (temporality-code (:temporality metric))}
                          (if typed-projector (typed-projector typed-context) {})))))))

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

(def ^:private durable-phase-names
  [:payload-built :atomic-execute-and-flush-returned])

(defn durable-phase-receipts
  "Create an opt-in, caller-owned aggregate sink for the acknowledged untyped
  Durable span path. Supply the returned atom as :durable-phase-receipts to
  `exporter`. `:atomic-execute-and-flush-returned` is one combined chDB writer
  boundary, not invented per-native-execute and per-publication timings. Each
  phase contains only a completed count, total elapsed nanoseconds, and span
  count; payloads, rows, and attribute values are never retained. Omit the
  option (the default) to avoid timing and aggregation."
  []
  (atom (zipmap durable-phase-names
                (repeat {:count 0 :nanos 0 :spans 0}))))

(defn- record-durable-phase! [receipts phase elapsed-nanos span-count]
  ;; Diagnostics must not change the acknowledgement result, including when a
  ;; caller installs a problematic atom watch. The sink receives aggregates
  ;; only, never payload text or SDK values.
  (when receipts
    (try
      (swap! receipts
             (fn [snapshot]
               (update snapshot phase
                       (fn [current]
                         (let [current (or current {:count 0 :nanos 0 :spans 0})]
                           {:count (inc (:count current))
                            :nanos (+ (:nanos current) elapsed-nanos)
                            :spans (+ (:spans current) span-count)})))))
      (catch Throwable _ nil))))

(defn- execute-durable-sql! [connection sql]
  (let [result
        (context/with-instrumentation-suppressed
          (durable/execute-and-flush! connection sql))]
    (when-not (contains? confirmed-durable-statuses (:status result))
      (throw (ex-info "Durable atomic execution did not confirm publication"
                      {:type ::durable-atomic-execution-unconfirmed
                       :status (:status result)})))
    result))

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

(defn- complete-batch!
  ([connection state wrote?]
   (when (and wrote? (not (:durable? @state)))
     (persistence-barrier! connection state true))
   true)
  ([connection state wrote? receipts span-count]
   (when (and wrote? (not (:durable? @state)))
     (let [started (System/nanoTime)]
       (persistence-barrier! connection state true)
       (record-durable-phase! receipts :atomic-execute-and-flush-returned
                              (- (System/nanoTime) started) span-count)))
   true))

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
        (let [receipts
              (when (seq spans)
                (let [snapshot @state
                      encoder (when (and (:durable? snapshot)
                                         (or (nil? (:typed-span-projector snapshot))
                                             (:typed-span-encoder snapshot)))
                                (or (:typed-span-encoder snapshot)
                                    @untyped-span-encoder))
                      receipts (when encoder (:durable-phase-receipts snapshot))]
                  (if encoder
                    (if receipts
                      (let [span-count (count spans)
                            payload-start (System/nanoTime)
                            payload (untyped-span-payload encoder spans)]
                        (record-durable-phase! receipts :payload-built
                                               (- (System/nanoTime) payload-start) span-count)
                        (let [execute-start (System/nanoTime)]
                          (execute-durable-sql!
                           connection
                           (str "insert into otel_traces FORMAT JSONEachRow\n" payload))
                          ;; chDB performs native execution and persistence
                          ;; inside one writer request.  Report that combined
                          ;; boundary; separate timings would be invented.
                          (record-durable-phase!
                           receipts :atomic-execute-and-flush-returned
                           (- (System/nanoTime) execute-start) span-count))
                        receipts)
                      ;; Keep the disabled default path free of clocks,
                      ;; aggregation, or diagnostic callbacks.
                      (let [payload (untyped-span-payload encoder spans)]
                        (execute-durable-sql!
                         connection
                         (str "insert into otel_traces FORMAT JSONEachRow\n" payload))
                        nil))
                    (do
                      (insert-batch! connection state "otel_traces"
                                     (:span-insert-columns @state) "insert into otel_traces"
                                     (map #(span-row % (:typed-span-projector @state))
                                          spans))
                      nil))))]
          (if receipts
            (complete-batch! connection state true receipts (count spans))
            (complete-batch! connection state (boolean (seq spans)))))
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
          (insert-untyped-log-records! connection state records))
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
  when this exporter creates the schema, checkpoints it; every non-empty
  physical signal insert uses one `execute-and-flush!` writer request and
  returns true only after its WAL publication commits or reconciles.
  :persistence-barrier supplies the same post-batch contract for another
  persistence implementation and is mutually exclusive with :durable?.
  Ordinary connections must expose the chDB driver context; startup rejects
  other drivers before schema mutation. Durable connections must explicitly
  opt into :durable? true; they never fall back from the ordinary row-data API.
  :typed-span-descriptors, :typed-log-descriptors, :typed-gauge-descriptors,
  :typed-sum-descriptors, and :typed-histogram-descriptors accept only opaque
  capabilities returned in active `install-approved!` results. They project
  their respective attributes while the compatible generic maps remain
  unchanged. Gauge descriptors cover point, resource, and scope attributes on
  `otel_metrics_gauge`; sum descriptors cover point, resource, and scope
  attributes on `otel_metrics_sum`; histogram descriptors cover the same three
  locations on `otel_metrics_histogram`."
  ([] (exporter {}))
  ([{:keys [connection db-spec create-schema? signals durable?
            persistence-barrier typed-span-descriptors typed-log-descriptors
            typed-gauge-descriptors typed-sum-descriptors typed-histogram-descriptors
            durable-phase-receipts]
     :or {db-spec "chdb::memory:" create-schema? true
          signals #{:spans :metrics} durable? false}}]
   (when (and persistence-barrier (not (ifn? persistence-barrier)))
     (throw (ex-info ":persistence-barrier must be callable"
                     {:type ::invalid-persistence-barrier})))
   (when (and durable? persistence-barrier)
     (throw (ex-info "Choose :durable? or :persistence-barrier, not both"
                     {:type ::ambiguous-persistence-barrier})))
   (when (and durable-phase-receipts (not (atom? durable-phase-receipts)))
     (throw (ex-info ":durable-phase-receipts must be an atom"
                     {:type ::invalid-durable-phase-receipts})))
   (when (and (or typed-span-descriptors typed-log-descriptors typed-gauge-descriptors
                  typed-sum-descriptors typed-histogram-descriptors)
              (nil? connection))
     (throw (ex-info "Typed descriptors require their explicit install connection"
                     {:type ::typed-descriptors-require-connection})))
   (let [owned? (nil? connection)
         conn (or connection (jdbc/connection db-spec))
         typed-span-projector (when typed-span-descriptors
                                (attribute-projection/trace-projector
                                 typed-span-descriptors conn))
         typed-span-encoder (when typed-span-projector
                              (compile-typed-span-encoder typed-span-projector))
         typed-log-projector (when typed-log-descriptors
                               (attribute-projection/log-projector
                                typed-log-descriptors conn))
         typed-gauge-projector (when typed-gauge-descriptors
                                 (attribute-projection/gauge-projector
                                  typed-gauge-descriptors conn))
         typed-sum-projector (when typed-sum-descriptors
                               (attribute-projection/sum-projector
                                  typed-sum-descriptors conn))
         typed-histogram-projector (when typed-histogram-descriptors
                                     (attribute-projection/histogram-projector
                                      typed-histogram-descriptors conn))
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
         histogram-columns (vec (when typed-histogram-descriptors
                                  (typed-columns
                                   (attribute-projection/confirmed-histogram-fields
                                    typed-histogram-descriptors conn))))
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
                              :typed-span-encoder typed-span-encoder
                              :typed-log-projector typed-log-projector
                              :typed-metric-projectors
                              (cond-> {}
                                typed-gauge-projector (assoc :gauge typed-gauge-projector)
                                typed-sum-projector (assoc :sum typed-sum-projector)
                                typed-histogram-projector (assoc :histogram typed-histogram-projector))
                              :span-insert-columns span-columns
                              :log-insert-columns log-columns
                              :typed-metric-columns {:gauge gauge-columns
                                                     :sum sum-columns
                                                     :histogram histogram-columns}
                              :durable? durable?
                              :durable-phase-receipts durable-phase-receipts
                              :last-error nil}))
       (catch Throwable t
         ;; A failed ownership cleanup must not replace the startup failure.
         (when owned? (try (.close conn) (catch Throwable _ nil)))
         (throw t))))))

(defn last-error [exporter] (:last-error @(:state exporter)))
