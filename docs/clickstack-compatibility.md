# ClickStack compatibility boundary

The initial schema was compared with the official OpenTelemetry Collector
ClickHouse exporter at commit `aad2838d6990eb031e7f4913269c3153cb5c2b4a`.
ClickStack infers its default sources from the collector's table/column names,
which is why this library already uses `otel_traces`, `otel_logs`,
`otel_metrics_gauge`, `otel_metrics_sum`, and `otel_metrics_histogram` plus the
canonical trace/log correlation columns.

Migration v2 provides **bounded trace insert compatibility**. `otel_traces` now
contains all 22 columns in the pinned collector's trace insert, including the
seven parallel `Events.*`/`Links.*` arrays, and exports populate them. The
collector's `otel_traces_trace_id_ts` lookup table and aggregate materialized
view are also present. `EventsJSON`/`LinksJSON` remain additive compatibility
columns for the embedded viewer. The checked provenance manifest is
`fixtures/clickstack-traces-aad2838d.edn`.

Migration v3 provides **bounded log insert compatibility**. `otel_logs`
contains the pinned collector's 15 base insert columns plus its schema-detected
`EventName` feature, with matching column types. Export uses that explicit
16-column list and preserves resource/scope schema URLs and attributes,
correlation identifiers and flags, severity, structured pdata text bodies, and
event names. A zero event timestamp falls back to observed time, and the two
`UInt8` fields use the collector's cast semantics. Provenance is pinned in
`fixtures/clickstack-logs-aad2838d.edn`.

Migration v4 provides **bounded canonical metric insert compatibility** for
gauge, sum, and explicit histogram—the kinds the current jolt-otel SDK can
produce. All pinned collector insert columns exist, exports use the exact
per-kind column order, and resource/scope schema URLs survive. Scope attributes
are emitted when present. Because the canonical model has no point flags,
dropped scope-attribute count, or exemplars, those columns carry the truthful
defaults `0`, `0`, and five aligned empty arrays. Provenance and model-boundary
decisions are pinned in `fixtures/clickstack-metrics-aad2838d.edn`.

This is not full ClickStack parity. The v1 log schema still differs from the
pinned collector physically: it retains the v1 partition choice and lacks the
collector's skip indexes, materialized Kubernetes/deployment columns, TTL,
comments and complete MergeTree settings. Metric insert schemas have passed
their fixture/DESCRIBE gates, with two explicit type exceptions:
`StartTimeUnix` and `TimeUnix` remain the higher-precision `DateTime64(9)`.
Changing the former would discard existing subsecond data; the latter is in
each v1 sorting key and ClickHouse rejects changing that key column in place.
Matching them exactly requires rebuilding the tables. Metric partitions, order
expressions, skip indexes, TTL and settings also remain physically different.
The trace table retains its v1 partition/order/index choices; compatible insert
columns do not claim identical physical storage tuning.

The remaining drop-in gate is mechanical:

1. Extend the canonical metric model before attempting non-empty exemplars,
   non-zero point flags, dropped scope attributes, exponential histograms, or
   summaries. Do not synthesize those measurements in the exporter.
2. Decide whether exact physical metric tables justify a data-preserving table
   rebuild and swap; a lower-precision in-place conversion would lose start-time
   data, and append-only ALTER cannot change the v1 `TimeUnix` key type.
3. Validate actual ClickStack UI behavior through a network-facing ClickHouse
   endpoint or an explicit gateway/collector adapter. HyperDX cannot attach to
   a local embedded chDB directory, so pointing it at the directory is not a
   valid integration test.
4. Prove trace waterfalls, correlated logs, and all metric kinds end to end.
5. Treat later collector schema changes as explicit migrations, never as
   require-time DDL mutation.

The current five tables remain immutable migration v1. Migration v2 adds trace
Nested subcolumns and lookup objects; migration v3 modifies existing log column
types/codecs; migration v4 adds and normalizes the supported canonical metric
insert columns. None rebuilds v1 or uses destructive retention.
`otel_schema_migrations` records each name, computed SHA-256, and applied time.
Later parity work must append consecutive, idempotent migrations.

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
