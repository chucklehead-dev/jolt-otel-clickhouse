# Changelog

## Unreleased

- Add an opt-in ordinary transport ABBA benchmark with explicit provenance,
  bounded child processes and complete fresh-reader reconciliation. Refs #27.

- Add an opt-in, local-only Linux native gate for typed Durable span/log
  exports and fresh-reader WAL recovery, preserving canonical tests and
  requiring explicit runtime/library provenance. Refs #27.

- Fail closed before exporter schema writes unless the actual loaded chDB
  package reports the qualified 26.7.3 timestamp wire. Compatible library
  overrides and Durable connections receive the same check; no bypass option
  is provided, and rejected versions are not included in error data.

- Encode DateTime64(9) span/log/event timestamps as exact integer nanoseconds
  for pinned chDB package 26.7.3 / SQL engine 26.7.2.1, rejecting values outside
  0..Int64-max before insertion. This fixes shared ordinary and Durable
  early-epoch JSON wires; observed log time still feeds the existing Timestamp
  fallback, without adding a column. Whole-second metric DateTime fields and
  Durable classification/WAL/barrier algorithms are unchanged. Requalify the
  timestamp wire before upgrading to 26.8, whose integer semantics differ.

- Send ordinary telemetry batches through the driver's row-data API with
  ordered schema-owned columns, all-row key and numeric validation, maintained
  JSON encoding, and an exact 8 MiB UTF-8 bound. Active installer capabilities
  supply additive typed columns. Durable exports retain materialized SQL and
  their existing checkpoint/WAL acknowledgement protocol and require explicit
  `:durable? true`. Startup rejects non-chDB ordinary contexts before schema
  writes; ordinary metric batches validate every type before the first insert,
  without promising rollback of native execution failures. Physical UInt64
  duration/count/bucket domains remain supported. Refs #27.

- Add capability-bound typed log-record filters and six-way availability
  coverage for every scalar type supported by the current promotion manifest.
  Exact schema bindings and the installed connection authorize library-owned,
  bounded SQL; historical generic values remain visible only in coverage and
  never satisfy typed predicates. Refs #41, #8.

- Promote explicitly approved log-record attributes into typed columns on
  `otel_logs` while retaining `LogAttributes`. A native gate compares direct
  export with the same canonical record sent through a real loopback OTLP/HTTP
  socket and proves exact signed-Int64 readback plus capability-free status.

- Treat resource, scope, and span attributes as location-qualified fields under
  one confirmed `otel_traces` schema authority. The same logical key may be
  promoted independently at all three locations; export, direct/OTLP ingestion,
  saved bindings, discovery, filters, aggregates, and coverage retain the
  location. Existing `ResourceAttributes` and `SpanAttributes` maps remain
  unchanged, while scope history is reported unavailable because ClickStack's
  trace table has no generic scope-attribute map.

- Pin the merged OTel closed-exporter configuration and background-export
  failure retention fixes, with a resolved-classpath guard for the reviewed
  dependency revision.

- Validate immutable typed descriptor evidence once when its private,
  connection-bound capability is minted. Typed query and projection hot paths
  retain issuer and exact target checks without repeatedly walking the captured
  registry catalog and manifest.

- Expose capability-bound typed span coverage independently of filtered trace
  retrieval. The coverage-only operation requires the exact logical schema
  binding, runs one bounded library-owned query, and returns conserved counts
  for valid, present-empty, absent, invalid, historical fallback-present, and
  historical unavailable rows without retrieving or materializing traces.

- Pin the merged OTel log-body and independent-pipeline contracts. Direct SDK
  scalar and structured bodies are canonical AnyValues before export; maps and
  arrays retain JSON text in ClickStack's existing `Body String` column, byte
  strings use base64, explicit empty values use the empty string, and malformed
  bodies retain OTel's readable fallback. Attribute maps use the same pdata
  string projection for canonical empty, byte, map, and array values.

- Add exact signed-Int64 equality and lower/upper-bound span filters to the
  capability-bound typed trace query. Numeric-looking historical or invalid
  fallback text remains excluded, while the existing count/min/max/average
  aggregate surface supplies the bounded numeric summaries used by viewers.

- Add capability-bound Boolean and string filters for typed span attributes.
  Boolean equality and exact/prefix/contains string predicates use only bound
  values and status-valid physical columns. Results include bounded trace
  summaries plus separate counts for valid, present-empty, absent, invalid,
  historical fallback-present, and historical unavailable rows; lossy legacy
  map text never enters a typed predicate.
- Add read-only acquisition of persisted active typed-span descriptors for
  restart and fresh-process consumers. Acquisition freshly verifies the exact
  physical table, then fences publication on unchanged catalog ETag, revision,
  and record generation without accepting a DDL effect or writing the catalog.
- Add a bounded capability- and connection-confirmed Int64 span aggregate
  query. Exact half-open range predicates and closed count/min/max/average
  recipes read only status-valid physical values; numeric-looking historical or
  invalid fallback text never participates. Query scans and results are bounded,
  and no sorting or skip index is generated.
- Pin OTel artifact discovery and add a bundle-backed v3 typed-manifest compiler.
  An explicit operator deployment binding consumes a validated canonical bundle;
  its digest and complete artifact identities are checksum-bound compact
  provenance. Existing v2 catalogs remain readable, bundle drift at one version
  conflicts, and only the separately invoked installer can authorize additive
  DDL.
- Pin the merged canonical OTel receiver record contract and qualify that direct
  export and real OTLP JSON receiver ingestion produce identical typed physical
  rows with the same descriptor capability and chDB connection.
- Qualify typed physical-schema evidence by canonical signal and table. Closed,
  bounded observations reject missing, duplicate, unknown, cross-signal, and
  wrong-table evidence before DDL or descriptor publication, while physical
  installation remains limited to span attributes on `otel_traces`.
- Version typed attribute manifests and reviewed fragments to v2 with an
  explicit closed signal, physical table, and location identity. Checksums,
  field IDs, registry planning, projection, and queries share that identity;
  legacy ambiguous v1 manifests fail closed and require operator-reviewed
  migration. Only span attributes on `otel_traces` are physically enabled.
- Add a capability- and connection-bound explorer query for approved typed span
  keys. Physical value/status columns are library-owned, caller values remain
  parameters, historical or invalid rows use the generic attribute map, and
  present-empty rows remain queryable. A native chDB round trip qualifies real
  installation, export, map fallback, and grouped selection for every status.
- Allow the span exporter to consume the installer-issued active descriptor
  capability and populate typed value/status columns. Generic SpanAttributes
  remain present, and export without a capability retains legacy behavior. A
  private issuer and exact process-local target identity prevent ordinary
  capability forgery or cross-connection reuse.
- Add a span-only typed-column installer with injectable DDL execution and
  physical-schema observation. It persists preparing before additive DDL and
  returns descriptors only after fresh observation and active-state CAS.
  Repairing an active or failed record first persists its new preparing
  generation, so a crash cannot leave DDL authorized only by stale state.
- Add a bounded, canonical EDN CAS store for typed-attribute registry catalogs
  over the jolt-chDB Durable object-backend contract. Opaque ETags prevent lost
  updates, record generations cannot skip, and ambiguous creates/replaces are
  accepted only when a reread proves the intended catalog was persisted.
- Add persistence-ready typed-attribute registry records and a deterministic
  span-column reconciliation planner. Records move through preparing, active,
  failed, and retired states using caller-enforced generation CAS values; this
  slice uses public Malli schemas for its closed data envelopes, emits data
  operations, but does not execute DDL.
- Resolve `jolt-crypto` once from canonical upstream revision `5effcc89` and
  exclude OTel's older compatibility-fork declaration. Dependency selection no
  longer depends on graph traversal order.
- Add a pure, deterministic compiler for operator-reviewed typed attribute
  manifests. It produces stable ClickHouse field descriptors without changing a
  database or authorizing schema changes from telemetry.
