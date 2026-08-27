(ns otel.exporter.chdb.schema
  "A compact embedded schema aligned with ClickStack's default OTel column
  names. Map types and correlation columns intentionally match ClickStack.

  Schema changes are ordered, checksummed migrations. chDB does not support
  transactions, so every migration statement must be idempotent: a failed
  migration is left unrecorded and is retried on the next open."
  (:require [clojure.string :as str]
            [jdbc.core :as jdbc]))

(def traces-ddl
  "CREATE TABLE IF NOT EXISTS otel_traces (
     Timestamp DateTime64(9), TraceId String, SpanId String, ParentSpanId String,
     TraceState String, SpanName String, SpanKind String, ServiceName String,
     ResourceAttributes Map(String, String), ScopeName String, ScopeVersion String,
     SpanAttributes Map(String, String), Duration UInt64, StatusCode String,
     StatusMessage String, EventsJSON String, LinksJSON String
   ) ENGINE=MergeTree ORDER BY (ServiceName, SpanName, Timestamp)")

(def logs-ddl
  "CREATE TABLE IF NOT EXISTS otel_logs (
     Timestamp DateTime64(9), TraceId String, SpanId String, TraceFlags UInt8,
     SeverityText String, SeverityNumber UInt8, ServiceName String, Body String,
     ResourceSchemaUrl String, ResourceAttributes Map(String, String),
     ScopeSchemaUrl String, ScopeName String, ScopeVersion String,
     ScopeAttributes Map(String, String), LogAttributes Map(String, String),
     EventName String
   ) ENGINE=MergeTree ORDER BY (toStartOfFiveMinutes(Timestamp), ServiceName, Timestamp)")

(def metric-common
  "ResourceAttributes Map(String, String), ScopeName String, ScopeVersion String,
   ServiceName String, MetricName String, MetricDescription String, MetricUnit String,
   Attributes Map(String, String), StartTimeUnix DateTime64(9), TimeUnix DateTime64(9)")

(def gauge-ddl
  (str "CREATE TABLE IF NOT EXISTS otel_metrics_gauge (" metric-common
       ", Value Float64) ENGINE=MergeTree ORDER BY (ServiceName, MetricName, TimeUnix)"))

(def sum-ddl
  (str "CREATE TABLE IF NOT EXISTS otel_metrics_sum (" metric-common
       ", Value Float64, AggregationTemporality Int32, IsMonotonic Bool) "
       "ENGINE=MergeTree ORDER BY (ServiceName, MetricName, TimeUnix)"))

(def histogram-ddl
  (str "CREATE TABLE IF NOT EXISTS otel_metrics_histogram (" metric-common
       ", Count UInt64, Sum Float64, BucketCounts Array(UInt64), "
       "ExplicitBounds Array(Float64), Min Float64, Max Float64, "
       "AggregationTemporality Int32) "
       "ENGINE=MergeTree ORDER BY (ServiceName, MetricName, TimeUnix)"))

(def migration-table-ddl
  "CREATE TABLE IF NOT EXISTS otel_schema_migrations (
     Version UInt64, Name String, Checksum FixedString(64),
     AppliedAt DateTime64(9, 'UTC')
   ) ENGINE=MergeTree ORDER BY Version")

;; Migration v1 is immutable. ClickStack's collector writes Nested values as
;; seven parallel arrays; adding their physical subcolumns is the idempotent
;; way to adopt an existing v1 table without rebuilding it or dropping the
;; viewer-compatible JSON columns.
(def trace-events-timestamp-ddl
  "ALTER TABLE otel_traces ADD COLUMN IF NOT EXISTS `Events.Timestamp`
     Array(DateTime64(9)) CODEC(ZSTD(1))")

(def trace-events-name-ddl
  "ALTER TABLE otel_traces ADD COLUMN IF NOT EXISTS `Events.Name`
     Array(LowCardinality(String)) CODEC(ZSTD(1))")

(def trace-events-attributes-ddl
  "ALTER TABLE otel_traces ADD COLUMN IF NOT EXISTS `Events.Attributes`
     Array(Map(LowCardinality(String), String)) CODEC(ZSTD(1))")

(def trace-links-trace-id-ddl
  "ALTER TABLE otel_traces ADD COLUMN IF NOT EXISTS `Links.TraceId`
     Array(String) CODEC(ZSTD(1))")

(def trace-links-span-id-ddl
  "ALTER TABLE otel_traces ADD COLUMN IF NOT EXISTS `Links.SpanId`
     Array(String) CODEC(ZSTD(1))")

(def trace-links-trace-state-ddl
  "ALTER TABLE otel_traces ADD COLUMN IF NOT EXISTS `Links.TraceState`
     Array(String) CODEC(ZSTD(1))")

(def trace-links-attributes-ddl
  "ALTER TABLE otel_traces ADD COLUMN IF NOT EXISTS `Links.Attributes`
     Array(Map(LowCardinality(String), String)) CODEC(ZSTD(1))")

(def trace-id-ts-ddl
  "CREATE TABLE IF NOT EXISTS otel_traces_trace_id_ts (
     TraceId String CODEC(ZSTD(1)),
     Start DateTime CODEC(Delta, ZSTD(1)),
     End DateTime CODEC(Delta, ZSTD(1)),
     INDEX idx_trace_id TraceId TYPE bloom_filter(0.01) GRANULARITY 1
   ) ENGINE=MergeTree
     PARTITION BY toDate(Start)
     ORDER BY (TraceId, Start)
     SETTINGS index_granularity=8192, ttl_only_drop_parts=1")

(def trace-id-ts-mv-ddl
  "CREATE MATERIALIZED VIEW IF NOT EXISTS otel_traces_trace_id_ts_mv
     TO otel_traces_trace_id_ts
     AS SELECT TraceId, min(Timestamp) AS Start, max(Timestamp) AS End
       FROM otel_traces
       WHERE TraceId != ''
       GROUP BY TraceId")

;; Migration v3 keeps v1's viewer-facing columns and table identity, while
;; adopting the pinned collector's canonical log insert types and codecs. Each
;; MODIFY is independently retry-safe because chDB has no DDL transactions.
(def log-timestamp-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS Timestamp
     DateTime64(9) CODEC(Delta(8), ZSTD(1))")

(def log-trace-id-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS TraceId
     String CODEC(ZSTD(1))")

(def log-span-id-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS SpanId
     String CODEC(ZSTD(1))")

(def log-severity-text-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS SeverityText
     LowCardinality(String) CODEC(ZSTD(1))")

(def log-service-name-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS ServiceName
     LowCardinality(String) CODEC(ZSTD(1))")

(def log-body-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS Body
     String CODEC(ZSTD(1))")

(def log-resource-schema-url-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS ResourceSchemaUrl
     LowCardinality(String) CODEC(ZSTD(1))")

(def log-resource-attributes-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS ResourceAttributes
     Map(LowCardinality(String), String) CODEC(ZSTD(1))")

(def log-scope-schema-url-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS ScopeSchemaUrl
     LowCardinality(String) CODEC(ZSTD(1))")

(def log-scope-name-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS ScopeName
     String CODEC(ZSTD(1))")

(def log-scope-version-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS ScopeVersion
     LowCardinality(String) CODEC(ZSTD(1))")

(def log-scope-attributes-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS ScopeAttributes
     Map(LowCardinality(String), String) CODEC(ZSTD(1))")

(def log-attributes-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS LogAttributes
     Map(LowCardinality(String), String) CODEC(ZSTD(1))")

(def log-event-name-ddl
  "ALTER TABLE otel_logs MODIFY COLUMN IF EXISTS EventName
     String CODEC(ZSTD(1))")

(def clickstack-trace-insert-columns
  ["Timestamp" "TraceId" "SpanId" "ParentSpanId" "TraceState"
   "SpanName" "SpanKind" "ServiceName" "ResourceAttributes" "ScopeName"
   "ScopeVersion" "SpanAttributes" "Duration" "StatusCode" "StatusMessage"
   "Events.Timestamp" "Events.Name" "Events.Attributes" "Links.TraceId"
   "Links.SpanId" "Links.TraceState" "Links.Attributes"])

(def clickstack-log-base-insert-columns
  "The pinned collector's unconditional log insert columns, in order."
  ["Timestamp" "TraceId" "SpanId" "TraceFlags" "SeverityText"
   "SeverityNumber" "ServiceName" "Body" "ResourceSchemaUrl"
   "ResourceAttributes" "ScopeSchemaUrl" "ScopeName" "ScopeVersion"
   "ScopeAttributes" "LogAttributes"])

(def clickstack-log-insert-columns
  "The collector base insert plus its EventName schema feature. The embedded
  schema has always included EventName, so its exporter uses the complete fixed
  list rather than negotiating the optional column on every open."
  (conj clickstack-log-base-insert-columns "EventName"))

(def clickstack-log-insert-types
  {"Timestamp" "DateTime64(9)"
   "TraceId" "String"
   "SpanId" "String"
   "TraceFlags" "UInt8"
   "SeverityText" "LowCardinality(String)"
   "SeverityNumber" "UInt8"
   "ServiceName" "LowCardinality(String)"
   "Body" "String"
   "ResourceSchemaUrl" "LowCardinality(String)"
   "ResourceAttributes" "Map(LowCardinality(String),String)"
   "ScopeSchemaUrl" "LowCardinality(String)"
   "ScopeName" "String"
   "ScopeVersion" "LowCardinality(String)"
   "ScopeAttributes" "Map(LowCardinality(String),String)"
   "LogAttributes" "Map(LowCardinality(String),String)"
   "EventName" "String"})

(def migrations
  "Ordered migration registry. Entries are append-only once released. New
  migrations must use a consecutive version and idempotent statements."
  [{:version 1
    :name "initial-otel-tables"
    :statements [traces-ddl logs-ddl gauge-ddl sum-ddl histogram-ddl]}
   {:version 2
    :name "clickstack-trace-nested-and-lookup"
    :statements [trace-events-timestamp-ddl
                 trace-events-name-ddl
                 trace-events-attributes-ddl
                 trace-links-trace-id-ddl
                 trace-links-span-id-ddl
                 trace-links-trace-state-ddl
                 trace-links-attributes-ddl
                 trace-id-ts-ddl
                 trace-id-ts-mv-ddl]}
   {:version 3
    :name "clickstack-log-insert-types"
    :statements [log-timestamp-ddl
                 log-trace-id-ddl
                 log-span-id-ddl
                 log-severity-text-ddl
                 log-service-name-ddl
                 log-body-ddl
                 log-resource-schema-url-ddl
                 log-resource-attributes-ddl
                 log-scope-schema-url-ddl
                 log-scope-name-ddl
                 log-scope-version-ddl
                 log-scope-attributes-ddl
                 log-attributes-ddl
                 log-event-name-ddl]}])

(defn- migration-source [{:keys [statements]}]
  (str/join "\n-- jolt-otel-clickhouse migration statement --\n" statements))

(defn- migration-checksum [conn migration]
  (:checksum
   (jdbc/fetch-one
    conn
    ["select lower(hex(SHA256(?))) as checksum" (migration-source migration)])))

(defn- fail! [message data]
  (throw (ex-info message (assoc data :migration/error true))))

(defn- validate-plan! [plan]
  (let [versions (mapv :version plan)]
    (when-not (= versions (vec (range 1 (inc (count plan)))))
      (fail! "chDB migration plan must have consecutive versions starting at 1"
             {:type ::invalid-plan :versions versions})))
  plan)

(defn- applied-migrations [conn]
  (jdbc/fetch conn
              "select Version, Name, Checksum, AppliedAt
                 from otel_schema_migrations order by Version"))

(defn- validate-applied! [plan applied]
  (let [by-version (into {} (map (juxt :version identity)) plan)
        versions (mapv :version applied)]
    (when-not (= (count versions) (count (set versions)))
      (fail! "chDB migration registry contains duplicate versions"
             {:type ::duplicate-version :versions versions}))
    (doseq [{:keys [version name checksum]} applied]
      (let [expected (get by-version version)]
        (when-not expected
          (fail! "chDB database has an unknown migration version"
                 {:type ::unknown-version :version version
                  :name name :checksum checksum}))
        (when-not (and (= name (:name expected))
                       (= checksum (:checksum expected)))
          (fail! "chDB migration checksum or name drift detected"
                 {:type ::migration-drift :version version
                  :recorded-name name :expected-name (:name expected)
                  :recorded-checksum checksum
                  :expected-checksum (:checksum expected)}))))
    (when-not (= versions (vec (range 1 (inc (count versions)))))
      (fail! "chDB migration registry is not a consecutive prefix"
             {:type ::nonconsecutive-history :versions versions})))
  applied)

(defn- apply-migration! [conn {:keys [version name checksum statements]}]
  (doseq [[index statement] (map-indexed vector statements)]
    (try
      (jdbc/execute! conn statement)
      (catch Throwable cause
        (throw
         (ex-info
          (str "chDB migration " version " (" name ") failed at statement " (inc index))
          {:migration/error true :type ::migration-failed
           :phase :statement :version version :name name
           :checksum checksum :statement-index index
           :statement-number (inc index) :statement statement}
          cause)))))
  (try
    (jdbc/execute!
     conn
     ["insert into otel_schema_migrations
          (Version, Name, Checksum, AppliedAt) values (?, ?, ?, now64(9, 'UTC'))"
      version name checksum])
    (catch Throwable cause
      (throw
       (ex-info
        (str "chDB migration " version " (" name ") applied but could not be recorded")
        {:migration/error true :type ::migration-failed
         :phase :record :version version :name name :checksum checksum}
        cause)))))

(defn migrate!
  "Create the migration registry, validate its immutable history, and apply
  pending migrations in order. Returns conn. Because chDB has no transactions,
  failed migration DDL is deliberately not recorded and must be idempotent so a
  later call can retry it safely."
  [conn]
  (jdbc/execute! conn migration-table-ddl)
  (let [plan (->> (validate-plan! migrations)
                  (mapv #(assoc % :checksum (migration-checksum conn %))))
        applied (validate-applied! plan (applied-migrations conn))
        applied-versions (set (map :version applied))]
    (doseq [migration plan
            :when (not (contains? applied-versions (:version migration)))]
      (apply-migration! conn migration)))
  conn)

(defn ensure-schema!
  "Compatibility alias for migrate!."
  [conn]
  (migrate! conn))
