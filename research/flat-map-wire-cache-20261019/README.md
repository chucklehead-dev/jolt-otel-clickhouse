# Bounded encoded-map reuse on stock Jolt

Experimental, opt-in; actual ~25k p50/~20k p99 Durable target remains unmet.
No default/app pin, protocol, acknowledgement or upstream compiler change.

## Implementation and correctness

Prototype commit `c6618f8` adds a per-record-encoding weak-root cache for complete
ordinary String->String pmap encodings (at least eight entries). Limits are 64
insertions, 128KiB fragment bytes plus bounded metadata, and 4KiB/fragment.
Eligibility piggybacks on the original walk; chunk-crossing maps decline.
Every hit checks the current byte budget and scalar-child nesting depth. Stored
bytes are independent copies, never caller raw JSON or mutable output aliases.
Immutable source/runtime layout contracts remain trusted. Scalar/nested
encoders do not use this cache. New calls, including reentry, have fresh state.

Final implementation `81da9abe69bb8ef82ad57d7510ebda4318a9f466` specializes
plain/cached Scheme entry points from one macro body. This removes unused
cache bookkeeping from plain paths. Exporter flag
`:experimental-span-flat-map-cache? true` separately opts in, requires the
explicit codec/alias and rejects unavailable support before storage. It composes
with SDK `:experimental-span-payload-cache? true`; both default false. Prior
traversal, nested-text and private prepared-WAL improvements remain cumulative.

Library/traversal tests:19/186. Exporter+library+cache+traversal:34/240, all pass.
Tests count real map walks/inserts in a private lexical kernel, exercise budgets,
shallow-warmed/deep-hit limits, exact/chunk-boundary bytes, unsupported shapes,
source effects after failure, fresh output/reentry, strict options and missing
capability. Early test-source syntax errors were corrected before the green
gates; no correctness oracle was weakened. No models/exhaustive checks changed.

Both independent Claude source reviews completed with no concrete correctness
defect. First reviews the frozen prototype; second covers macro specialization
and exporter integration. The second agent attempted an out-of-fence private
plan write, but its tool was disabled and the attempt was denied; it made no
source edits. Its unnecessary docs-guide call was not used as review evidence.
Future reviews should specify `--tools Read Glob Grep` and an empty strict MCP
configuration as well as the existing permissions to avoid ambient tools.

## Allocation regression found and fixed

Same-process scalar probe, identical strings, 5,000 calls per sample:

| Encoder | Estimated bytes/call |
| --- | ---: |
| Pre-cache baseline | 2,112 |
| Initial dynamic-mode prototype, cache disabled | 2,304 |
| Macro-specialized plain encoder | 2,112 |

The additional192B/call was unused cache state/helper allocation. Specialization
restores the baseline instead of accepting a default-path regression. All
source variants remain available by their exact commits/worktrees in the script.

## Rich full-wire encoding component

64 observations,16/cell,5k rows. SDK normalization cache is enabled in BOTH
arms; only wire-map reuse differs. Full8,647,780-byte digests and changed-event
controls match across shared/unshared shapes. No native/Durable I/O here.

| Final specialized encoder | Wire cache off | Wire cache on |
| --- | ---: | ---: |
| Shared median |134.88ms|114.15ms|
| Shared estimated allocation/row |9,868B|9,874B|
| Unshared median |191.08ms|197.50ms|
| Unshared maximum of16 |254.40ms|402.16ms|

Shared median improves15.4%; unshared median regresses3.4%. Keep unshared and
upper-percentile controls, and keep the option off by default. Tiny sample
quantiles are not population tails. Initial dynamic-prototype results are also
retained, not silently represented as final source measurements.

## Actual SDK / local Durable ABBA

Official stock Jolt0.8.23, libchdb26.9.0, pinned Chez10.4.1. One exporter/worker,
one writer. Both arms retain SDK cache, nested bytes and private prepared WAL.
Four fresh processes,32 measured5k callbacks plus3 warmups each:

| Arm | Confirmed window rows/s | Estimated allocation/row |
| --- | ---: | ---: |
| Off |13,720|17,212B|
| On |13,798|17,218B|
| On |14,949|17,207B|
| Off |10,710|17,232B|

Pooled12,030→14,350 (+19.3%) versus28.1% baseline drift. No qualified throughput
win or target/tail claim. The final focused gate ran around the transition into
arm1, a potential additional interference source; retain rather than discard
that arm. Raw callback p50/p90/p95/p99, reciprocal batch rates, CPU and allocation
are retained; these are not end-to-end span latency SLOs.

All700k physical rows independently recovered with the older reader and checked
for full values/multiplicities. Stores removed only after terminal children,
successful validator, copied/cmp receipts and source/binary stability checks.

## Refreshed cumulative phase diagnostic

One additional on-arm reuses existing coarse instrumentation (single-lane
context only),32 measured callbacks, and independently recovers another175k
rows. Includes instrumentation overhead; do NOT compare its10.28k window rate
against ordinary arms as a regression/win. No native databases are retained.

| Median per5k | Time | Total across32 |
| --- | ---: | ---: |
| Projection/encoding |191.86ms|6.147s|
| Caller WAL preparation |47.49ms|1.626s|
| Native execution + confirmation |218.89ms|6.992s|
| Whole compact operation |498.10ms|15.297s|

These top-level sequential phases account for about40.2%,10.6%,45.7% and the
remaining3.5%. Native-stream, WAL publication, digest and head-commit timings
are nested children of the confirmation envelope: **do not add them again**.
All four quantiles are in the profile summary; snapshots/CPU ticks are coarse
and non-atomic. Host/runtime variance and observer effects remain unresolved.

Conditional Amdahl calculation for THIS diagnostic: removing all encoding while
holding the remaining measured cost fixed yields at most15.297/(15.297-6.147)
=1.67x. A15% encoding gain would give roughly6% total under those assumptions.
This is NOT an architectural ceiling or extrapolation to normal uninstrumented
rates. It explains why more isolated encoding fixes need not double throughput.

Next: a matched already-encoded/prepared SQL native/persistence baseline, then
measure useful preparation/writer overlap. The driver already exposes buffered
admission/local-execution tickets, but those are NOT publication receipts;
do not weaken SDK success/flush/cleanup semantics to obtain a faster number.
Check existing models/contracts before any production pipelining change.

## Hosted gate and custody

[Run39348237061](https://github.com/chucklehead-dev/jolt-otel-clickhouse/actions/runs/39348237061)
at exact runtime head81da9ab is SUCCESS. Actual logs confirm official archive
and binary SHA checks, separate traversal5/42 and codec/cache14/144. This is
isolated stock-library CI, not full collector/native/S3/app qualification.

This packet contains only small sources, diffs, scalar receipts, reviews and
summaries, not binaries or database files. Scripts name existing fixed worktrees
and fixtures; this is not a standalone benchmark package. All five owned stores
(875k rows) were validated then retired. No PR, merge or default selection.
