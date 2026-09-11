# Changelog

## Unreleased

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
