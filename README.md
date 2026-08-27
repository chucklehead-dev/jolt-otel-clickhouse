# jolt-otel-clickhouse

Direct in-process export from `jolt-otel` to embedded chDB. The initial schema
uses ClickStack's correlation and search column names (`TraceId`, `SpanId`,
`ParentSpanId`, `ServiceName`, `SpanName`, `Duration`, `Body`, and attribute
maps), so the demo and later ClickStack integration share the same query model.

```clojure
(def exporter (otel.exporter.chdb/exporter {:db-spec "chdb:telemetry.chdb"}))
(def telemetry (otel.sdk/init! {:service-name "agent"
                                :exporter exporter
                                :logs? true}))
```

The exporter implements span, log, and metric exporter protocols. Metrics use
ClickStack's `otel_metrics_gauge`, `otel_metrics_sum`, and
`otel_metrics_histogram` table names. It uses
chDB's streaming insert API, returns `false` instead of throwing into observed
application code, and exposes `last-error` for diagnostics. Pass an existing
`:connection` when the application UI also queries the database; that keeps
connection ownership with the application.

The first schema keeps span events and links as JSON strings and omits collector
columns that Jolt does not yet emit (exemplars and scope attributes). A later
full ClickStack compatibility gate will migrate those to the collector's exact
`Nested` layout; the important source names and correlation columns are already
aligned.
