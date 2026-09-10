# Changelog

## Unreleased

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
