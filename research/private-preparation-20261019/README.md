# Private preparation/publication seam

This is the first implementation slice of exporter #141. It separates compact
row materialization from publication, but calls both immediately and
synchronously under the existing exporter admission and dynamic bindings.
It is not a concurrent pipeline, public prepared-request API, retry token or
performance-target qualification. Published application dependency pins remain
unchanged.

## Preserved boundaries

- Initial snapshot and compact target/column-order validation precede encoding.
- Projection, validation and WAL preparation retain their existing order.
- Publication rechecks the snapshot and compact plan, then calls the unchanged
  confirmed Durable execution helper. Startup plan confirmation is not live
  cross-process DDL freshness.
- Preparation returns no acknowledgement; publication preserves the actual
  receipt or exception. No SDK counters or shutdown contracts change.
- The private capability's seal proves construction, not single use. Its sole
  production caller prepares and publishes once. A future retained/queued path
  needs an explicit lifecycle and cannot assume this is an idempotency token.

## Tests and review

Stock Jolt 0.8.23 / Chez 10.4.1, with the selected local-root SDK and driver:

- SDK: `26186ce79941e84e0cc67eb148dd8dd43b4e1880` (clean worktree).
- Driver: `3314b79e8a234fafdf5231439a0323c878a40040` (clean worktree).
- libchdb: 26.9.0; binary/source checksums are in `sources.sha256`.

- Preparation, exporter codec/cache, standalone codec/cache and traversal:
  **39 tests / 267 assertions**, green after the printing correction.
- Existing direct compact layout aggregate: **52 / 433**, green before the
  printing-only correction. This is not a second post-correction run.
- Claude source review completed, followed by a completed printing-delta review.
  Reviews are advisory; runtime tests and real persistence are separate gates.

The initial review incorrectly inferred that `Object/toString` makes `pr-str`
opaque. A stronger ordinary String-SQL test failed: the new deftype's default
printer exposed its SQL field. Explicit `print-method` and `print-dup` methods
now return a fixed representation, with exact assertions for owned-byte and
String paths. This affects presentation of this new private type only, not SQL,
attribute values, WAL bytes or replay. It is not data redaction.

The review also raised future double-publication and native-buffer concerns.
There is no retained public API here; the driver-owned statement is immutable
managed data and repeated execution has already been measured independently.
No stale native pointer was demonstrated. Future queue lifecycle work remains
necessary rather than being claimed solved by the seal.

## Actual SDK persistence check

One SDK arm reused the corrected V2 matched-input harness and its complete
SDK/queue/order/confirmed-receipt validator. This is a functional gate, not an
ABBA performance comparison. The writer completed 35 confirmed 5,000-row calls
(3 warmups and 32 measured). A separate older-stack reader recovered all
**175,000 rows**, checking complete physical fields, typed values, nanosecond
timestamps and multiplicity. Captured SQL hash and SQL/WAL sizes match the
previous native-floor dataset. Sources/binaries were checked before and after.

The single SDK window measured **14,424 confirmed rows/s**. This synchronous
refactor does not demonstrate a speedup. The adjacent machine-readable summary
retains p50/p90/p95/p99 batch timings, allocation and CPU; with only 32 samples,
p99 is the maximum, not a qualified population tail. Preparation and native
persistence still run serially.

| Batch statistic | Latency, ms | Reciprocal batch rows/s |
| --- | ---: | ---: |
| p50 | 318.72 | 15,688 |
| p90 | 384.49 | 13,004 |
| p95 | 448.46 | 11,149 |
| p99 / observed maximum | 634.65 | 7,878 |

Estimated managed allocation is 17,211 B/row; process CPU is 87.69 us/row.
Counters are non-atomic snapshots, not an allocation-site profile. The observed
maximum is worse than the earlier SDK controls; this unpaired functional run
does not establish its cause or prove performance equivalence.

Recompute these statistics and the fixed-dataset receipt checks without native
storage or a compiler run:

```sh
bb research/private-preparation-20261019/summarize.bb \
  research/private-preparation-20261019/sdk
```

This reads saved evidence, not fresh storage: it cannot independently prove
the old recovery run occurred or replace a new writer/reader gate.

Writer/reader receipts were copied and compared before retiring the exact
synthetic store, reclaiming its on-disk space. No database or native binary is
included in this packet. A first launcher attempt failed source-manifest path
validation before creating a store; the corrected absolute-path manifest
passed. The failed preflight is not counted as a writer run.

## Next bounded work

Continue productizing the stock-runtime traversal/byte-codec boundary rather
than waiting for compiler upstreaming. For actual overlap, the current writer
acknowledgement model omits prepared batches outside its queue and exporter
post-ack release. Draft an adjacent bounded type/scope sketch for approval
before model logic or concurrency changes. Preserve one SDK owner, ordered
publication, bounded byte/row/in-flight memory, confirmed-only success and
stable settlement before cleanup. Add failure/shutdown trace controls, then
measure the full SDK pipeline and independently recover its output. No
unchanged exhaustive checks were rerun here.
