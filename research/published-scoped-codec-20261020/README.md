# Published nested-scope codec integration

Base: exporter stack `219f02a416d1a7a4b781e2e2b5f7e713bdacfa4d`.
The canonical main comparison starts at `0830f303d2a6be8b0ca1f2e01695bdb2ddac2c30`.
That branch's only main-only commit is a merge of an already shared parent;
the previously accepted singleton-event optimization is preserved.

This slice advances the optional codec Git subproject pin from `05fa8b8` to
`0af1008185cf84577298d3a0a0b2db87b97bddd5`, which includes vector leaf traversal,
row-view library APIs and nested-fragment scopes/scratch reuse. It enables no
default flags, does not adopt row views in production and adds no concurrency
or model logic. Root SDK/driver pins already belong to the accumulated stack.
Documentation now distinguishes published stock-Jolt support from application
standalone qualification and an upstream-only ecosystem.

## Local validation

- Official Jolt 0.8.23 / mandatory Chez 10.4.1 wrapper; source published-component
  gate **52 tests / 392 assertions**, no failures/errors. Exact Git resource
  provenance checks reject altered coordinates/paths before running the gate.
  Includes actual nested-scope, leaf, row-view and exporter ownership tests.
- Actual earlier/current Git dependency control: the earlier component rejects
  the requested nested-cache option through the public exporter constructor,
  **zero storage opens**. The current component loads the complete capability.
  No mocked missing Var or checkout-local codec source in these controls.
- `actionlint` passes the updated stock workflow, which also runs both real
  old/current dependency controls. No new exhaustive model run was requested.
- One actual SDK/local Durable functional gate used the **published codec**,
  without the checkout-local codec alias or writer-side SDK/driver local roots.
  All four codec/traversal source resources were asserted to come from the
  exact `0af1008/.../incubator/codec/src` Git coordinate before storage work.
- 35 confirmed 5k-row callbacks (3 warmup +32 measured), and **175,000 rows
  independently recovered** through the pinned older implementation, with
  every physical field and multiplicity checked. Validator/source stability
  checks and copied/cmp receipts passed before retiring the exact owned
  `/tmp/published-scoped-durable.EqewyxDD` store. No database/native binary kept.

This is a functional dependency-integration gate, not another paired benchmark.
The single observed window was about15.38k rows/s; do not compare it against
older runs as a speedup. Raw p50/p90/p95/p99 callback samples and allocation
counters remain in `durable/writer.edn`. No target/population-tail, S3, actual
Oscope/Langfuse ingestion, full app AOT or Rust-relative qualification is added.
The ~25k p50 / ~20k p99 throughput goal remains unmet.

`run-durable.sh` pins the official binary/native checksums and older reader
refs, uses committed fixture scripts and a new exact owned store, and retires
only after successful independent validation. It has fixed workspace paths and
is not a standalone benchmark distribution. Failure paths preserve the store.
No unchanged performance/model runs are needed for subsequent prose edits.
