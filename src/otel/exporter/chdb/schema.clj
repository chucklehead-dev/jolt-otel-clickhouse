(ns otel.exporter.chdb.schema
  "A compact embedded schema aligned with ClickStack's default OTel column
  names. Map types and correlation columns intentionally match ClickStack."
  (:require [jdbc.core :as jdbc]))

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

(defn ensure-schema! [conn]
  (jdbc/execute! conn traces-ddl)
  (jdbc/execute! conn logs-ddl)
  (jdbc/execute! conn gauge-ddl)
  (jdbc/execute! conn sum-ddl)
  (jdbc/execute! conn histogram-ddl)
  conn)
