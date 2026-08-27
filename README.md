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

An exporter-owned connection can keep telemetry isolated in a logical database
on the application's one active physical chDB path:

```clojure
(otel.exporter.chdb/exporter
 {:db-spec {:vendor "chdb"
            :name "/var/lib/my-app/chdb"
            :database "otel"}})
```

The chDB driver validates, creates, and selects `:database` when it opens the
connection. Schema migrations and exports continue to use the fixed unqualified
names `otel_schema_migrations`, `otel_traces`, `otel_logs`, and
`otel_metrics_*`; they therefore live in the selected database without an
unsafe table-prefix option. Multiple logical databases may use those same
names on the one physical path without colliding.

When supplying an application-owned `:connection`, select its logical database
in that connection's dbspec. The exporter uses its current database naturally
and never issues `USE`; shutdown also leaves the shared connection open.

The exporter implements span, log, and metric exporter protocols. Metrics use
ClickStack's `otel_metrics_gauge`, `otel_metrics_sum`, and
`otel_metrics_histogram` table names. It sends each SDK-bounded batch through
chDB's ordinary query API with `FORMAT JSONEachRow` and an 8 MiB safety limit.
libchdb 26.7's streaming-insert API corrupts ClickHouse `ThreadStatus` nesting
for this multi-signal exporter. Export returns `false` instead of throwing into
observed application code and exposes `last-error` for diagnostics. Pass an existing
`:connection` when the application UI also queries the database; that keeps
connection ownership with the application.

When the exporter owns its connection and logs are enabled, declare all active
signals: `{:signals #{:spans :metrics :logs}}`. Shutdown is tracked per signal,
so the SDK's metric shutdown cannot disable a later log/span batch drain. An
export attempt for a signal omitted from this set returns `false` and records a
descriptive `last-error` instead of failing later against a closed connection.

The first schema keeps span events and links as JSON strings and omits collector
columns that Jolt does not yet emit (exemplars and scope attributes). A later
full ClickStack compatibility gate will migrate those to the collector's exact
`Nested` layout; the important source names and correlation columns are already
aligned.

## Schema migrations

Exporter startup runs `otel.exporter.chdb.schema/migrate!`. Migration v1 owns
the five existing OTel tables, so databases created by earlier releases are
adopted without rebuilding or deleting them. Applied migrations are recorded in
`otel_schema_migrations` with a consecutive version, stable name, SHA-256 of
the ordered SQL statements, and UTC application timestamp. Reopening a database
is idempotent; a changed name/checksum, duplicate version, gap, or database from
a newer migration plan fails startup with diagnostic `ex-data` instead of
silently mutating history.

Migration history is local to the connection's selected logical database. Each
logical database is therefore independently initialized and validated.

chDB has no transactions. Each migration must therefore contain only
idempotent statements. The runner records a version after every statement has
succeeded; a statement or registry-write failure reports its phase, version,
name, checksum, and statement context, leaves the migration unrecorded, and can
be retried on the next open. The bootstrap `CREATE TABLE IF NOT EXISTS
otel_schema_migrations` is the only operation outside the versioned registry.
Keep application startup for one database serialized; cross-process migration
locking is not part of this initial foundation.
