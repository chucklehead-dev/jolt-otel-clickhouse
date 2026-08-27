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

(def migrations
  "Ordered migration registry. Entries are append-only once released. New
  migrations must use a consecutive version and idempotent statements."
  [{:version 1
    :name "initial-otel-tables"
    :statements [traces-ddl logs-ddl gauge-ddl sum-ddl histogram-ddl]}])

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
