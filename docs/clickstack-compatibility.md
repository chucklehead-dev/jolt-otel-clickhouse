# ClickStack compatibility boundary

The initial schema was compared with the official OpenTelemetry Collector
ClickHouse exporter at commit `aad2838d6990eb031e7f4913269c3153cb5c2b4a`.
ClickStack infers its default sources from the collector's table/column names,
which is why this library already uses `otel_traces`, `otel_logs`,
`otel_metrics_gauge`, `otel_metrics_sum`, and `otel_metrics_histogram` plus the
canonical trace/log correlation columns.

This is **aligned, not yet drop-in schema compatible**. The initial embedded
tables deliberately omit collector fields Jolt does not emit and use
`EventsJSON`/`LinksJSON` instead of the collector's `Nested` event/link columns.
Metric exemplars, resource/scope schema URLs, flags, and scope attributes are
also incomplete. HyperDX can query a custom source when its expressions are
configured, but automatic default-schema inference is not a release claim yet.

The drop-in gate is mechanical:

1. Copy the collector's current column names and types into versioned embedded
   migrations, including nested fields and trace-ID timestamp lookup tables.
2. Teach the JSONEachRow encoder to populate every required nested/metric field;
   keep optional fields empty rather than changing their types.
3. Run the collector's insert-column lists against the embedded tables and
   compare `DESCRIBE TABLE` output.
4. Point an actual HyperDX/ClickStack source at a persistent chDB database and
   prove trace waterfalls, correlated logs, and the three metric kinds.
5. Treat later collector schema changes as explicit migrations, never as
   require-time DDL mutation.

The migration seam now exists: the current five tables are immutable migration
v1, and `otel_schema_migrations` records its name, computed SHA-256, and applied
time. Later ClickStack parity work must append consecutive, idempotent migration
entries rather than edit v1. This foundation intentionally does not yet add the
collector's missing columns, lookup tables, TTLs, or destructive retention.

An exporter-owned chDB map dbspec may select a logical `:database`. The fixed
OTel table names and migration registry are created inside that database, so
telemetry can coexist with application tables in another logical database on
the same physical chDB path. Application-owned connections retain their
already-selected database; the exporter does not change it or add raw table
prefix configuration.

For embedded workloads the current bounded `FORMAT JSONEachRow` query batches
avoid libchdb 26.7's broken streaming-insert `ThreadStatus` lifecycle. For a
server ClickHouse target, keep the same exporter protocol but add a separate
network driver/collector path; do not make the chDB FFI driver pretend to be a
remote ClickHouse client.
