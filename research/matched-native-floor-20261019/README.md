# Matched prepared-input native/persistence baseline

This is research, not an SDK throughput or production pipeline result. Runtime
source stays at exporter `b56b072f4470b5af1c7b01a6c5a471c53f6876b3` (runtime
implementation81da9ab), driver3314b79, SDK26186ce. Official stock Jolt0.8.23,
Chez10.4.1, libchdb26.9.0. No runtime/model/protocol/app pin changes.

## What is measured

The SDK arm delegates the existing rich single-worker workload, with the SDK
normalization cache, wire-map cache, nested byte encoder and private prepared
WAL all selected. It observes one genuine prepared statement without hashing
inside the timed loop. Its35 actual confirmed inserts remain3 warmups+32 measured
5k-row callbacks. Full SDK queue/count/order/shutdown controls are retained.

The native-only arm captures its statement from a genuine, confirmed exporter
warmup, retires that borrowed exporter, then performs2 direct warmups and32 timed
serial confirmed requests using the same immutable statement. Identity/protocol
checks prove WAL was prepared before timing;35 actual native stream calls and
175k rows are required. Classification, native execution, WAL append/publication,
verification, head commit and acknowledgement are not bypassed. Repeated encoding,
WAL preparation and SDK queue/dispatch are excluded. No SDK statistics are invented.

Native requests reuse one frozen SQL/WAL object, not32 independently generated
batches. This is an intentionally optimistic input-preparation baseline, not a
solution for dynamic telemetry or a Rust comparison.

## Matched input and independent verification

All five captured statements have exactly the same bytes:

- SQL bytes: **8,648,398** per5k rows.
- SHA256: `862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39`.
- Prepared WAL bytes: **10,508,410**.
- Each store independently recovered by the older reader, which checks full
  physical fields, exact nanosecond timestamps, typed fields and multiplicity:
  5,000 groups,35 copies,175,000 total rows. Total **875,000 rows verified**.
- Aggregate matching checks compare SQL hashes and SQL/WAL byte counts across
  processes. A safe in-memory changed-hash mutant is rejected before writing
  an aggregate report; neither receipts nor persisted data are modified.

## Review-found control mismatches and correction

Initial SDK/native/native/SDK runs are retained, not relabeled as corrected:

| Original run | Confirmed window rows/s |
| --- | ---: |
| SDK |15,519|
| Native-only |32,604|
| Native-only |32,283|
| SDK |14,433|

Independent review found that native-only bypassed the exporter's suppression
wrapper and only SDK paid per-call statement observation. Corrected V2 routes
every native call through the real exporter confirmed helper AND the identical
observer body. It asserts35 observed calls/35 streams, and records the explicit
1 exporter+2 direct warmup split. A followup review confirms both fixes.

V2 is **one additional native-only run**, not a repeated ABBA; SDK controls above
were not rerun. The helper's source returns the confirmed publication receipt,
and actual runtime status/count/old-reader gates pass, resolving the followup
reviewer's source-only caveats. No population-tail claim is made.

## Corrected native-only result

Confirmed window throughput: **32,366 rows/s**. Estimated managed allocation:
**2,476B/row**, versus~17,206–17,211B/row through the SDK controls. Whole-process
CPU is~42.25µs/row versus81–87.1µs/row. These are separate non-atomic snapshots.

| Batch latency statistic | ms per5k | Reciprocal batch rows/s |
| --- | ---: | ---: |
| p50 |150.92|33,130|
| p90 |171.20|29,206|
| p95 |182.70|27,367|
| p99 (maximum of32) |200.30|24,963|

These are observed small-sample batch statistics, NOT qualified population p99,
end-to-end span latency, actual SDK/app throughput, S3 performance or a proven
architectural ceiling. The~25k p50/~20k p99 end-state goal remains unmet.

## Resource accounting and next implementation direction

Corrected native window averages154.5ms/batch. SDK control windows average322.2
and346.4ms/batch. Their difference167.7–192.0ms includes encoding/preparation,
SDK/producer/oracle work, scheduling/interference differences, not only JSON.

Under a simplified two-stage model with fixed observed costs, enough CPU and
no ordering/scheduling/interference penalty, overlapping those stages would
yield about26.0–29.8k rows/s. This is **a calculation, not a measured pipeline**;
do not infer pipelined span/batch latency or delivery guarantees from it. There
is observed backend headroom for25k on this local workload, not a guarantee.

Next implementation: split private preparation from publication while keeping
the current synchronous behavior/gates first. Then design bounded ordered
lookahead, respecting one SDK owner and original publication order. Return SDK
success/update exported counts only after confirmed publication. Pending work
must remain bounded and be settled before exporter/consumer release. Existing
buffered `:admitted` or `:executed-local` tickets do NOT satisfy that contract.
Map concurrency/lifecycle changes to existing models; obtain approval of any
new Quint type/scope sketch before adding model logic. Do not bypass policy,
sealed-spool, schema-plan, classification, lease/CAS or failure handling gates.
Current compact-schema confirmation is startup-only, not concurrent-DDL proof.

## Small follow-up probe: a suspected copy is not present

The remaining~2.5KB/row allocation suggested checking whether the writer's
BufferedOutputStream forced private WAL chunks onto the copying fallback.
An isolated stock-Jolt probe disproves that hypothesis: raw and buffered streams
are the same builtin object, and both take the synchronous borrow fast path.
No buffered-stream bypass patch is justified. The small script/output are kept.
The local POSIX copy helper already uses native FD read/write with a64KiB buffer;
despite its name, it is not the Linux copy_file_range syscall. Further tuning
would need a measured isolated case and short-write/failure controls, not guesswork.

## S3 / DSL constraint

This payload needs~420.3Mbps of WAL upload alone at25k rows/s. The user's10Mbps
DSL upload gives an ideal~595rows/s bound before requests, verification downloads
or other overhead. S3 throughput qualification therefore needs faster hosted
networking; none is claimed here. Local numbers are not network-independent.

## Custody

Both original and corrected writers/readers/validators completed successfully.
All five owned synthetic stores were removed only after copied/cmp receipts,
independent validation and source/binary checks. No database/native binaries are
retained in this small packet. No unrelated processes were stopped, and no
unchanged model/exhaustive checks were run. Source review used read-only tools;
private plan writes were unavailable/denied, and no source was edited by Claude.

Scripts retain exact fixed worktree/fixture paths, plus the shared runner and
older reader named by the earlier packet. This is not a standalone benchmark
distribution. No new CI was needed for research-only files; prior isolated
library CI does not qualify these native measurements or future pipelining.
