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

(defn ensure-schema! [conn]
  (jdbc/execute! conn traces-ddl)
  (jdbc/execute! conn logs-ddl)
  conn)
