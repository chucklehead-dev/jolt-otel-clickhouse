# Stock-Jolt preparation-cost follow-up: two ruled-out targets

Research only; **no product/runtime/driver/codec defaults changed**. Based on
exporter `0af1008185cf84577298d3a0a0b2db87b97bddd5`, official stock Jolt 0.8.23,
Chez 10.4.1 and the published dependency pins at that head. All local commands
used `/home/chuck/ai-src/tools/jolt-with-chez-10.4.1`.

## Ordinary metadata JSON reading is not the large remaining cost

`stock-metadata-profile-20261020.clj` wraps actual `clojure.data.json/read-str`
calls in the existing real SDK/local Durable nested-fragment-enabled workload.
The observer records bounded scalar aggregates, not input JSON. It preserves
the original return/exception, includes observer overhead, startup and close,
and is **not** a throughput or allocation-site qualification.

- 217 reads, 293,816 characters total, largest input 4,875 characters.
- Whole-process read elapsed time: **109.87ms**.
- Confirmed ingestion window: **13,099.93ms**, 160k measured rows.
- Whole-process read time / measured window: **0.839%**. This deliberately
  over-includes startup/close. It is elapsed time, not exclusive CPU attribution
  or a formal ceiling on indirect GC effects. Nonetheless these reads do not
  explain the large remaining throughput gap on this workload.
- The independent older implementation recovered **175,000 rows** (three
  warmups plus 32 callbacks), checking full field values and multiplicities.
- Writer, reader and validator terminated successfully; copied receipts were
  compared before retiring the exact owned `/tmp/stock-metadata-profile.ianBSKwT`
  store (about 0.75GiB). Only small scripts/logs/receipts remain.

Do not compare this instrumented single run's 12,214 rows/s with another run
as a performance regression or improvement. The ordinary metadata reader stays
unchanged. Extracting a decoder may still have independent API value, but is
not justified here as the next throughput fix.
This run enqueues span maps through the SDK; it does not measure large OTLP
HTTP request-body parsing. Decoder work may still matter for that separate
Oscope ingestion surface; the metadata result does not rule it out there.

## Checked clean-run WAL scan: keep the existing implementation

`stock-wal-run-screen-20261020.clj` compares the actual dependency's
`owned_ascii_wal.ss` with a lexically generated candidate. It does not install
or replace the driver kernel. Candidate source is retained in each EDN report.
On already-private ASCII input, aligned checked u32 loads conservatively find
runs of bytes >=48 excluding backslash. Runs use checked `bytevector-copy!`;
fallback keeps the original escape logic and 12-byte output reserve. No new
unchecked operations, protocol change, mutation or native/storage I/O is added.
The repeated-byte masks are endian-independent; the fast path requires their
constants to fit fixnums. It is not a general UTF-8 scanner.

Correctness controls compare **every output byte**, chunk limits and sizes,
small JSON roundtrips, all 128 ASCII characters at four alignments, minimum
buffer and 64KiB output/input boundary cases with clean/control/escaped input.
Large cases use direct array equality against the existing exact encoder;
the measured outputs also require the full reference SHA256 outside timing.
These controls are not a new formal proof or Durable recovery qualification
of the candidate. The native recovery above uses the unchanged real driver.

24 alternating observations per arm/shape; p50 below, full p90/p95/p99 and
resource counters retained. Sample p99 is a maximum, not a population tail.

| Component shape | Existing p50 | V2 candidate p50 | V3 existing / candidate p50 |
| --- | ---: | ---: | ---: |
| Mixed JSON-like 5k rows, 840,050 bytes |2.398ms|2.864ms|2.425 / 2.710ms|
| Long clean text, 800,000 bytes |2.124ms|0.915ms|2.210 / 0.921ms|
| Escape-heavy, 800,000 bytes |2.603ms|3.607ms|2.593 / 2.633ms|

V2 tests for clean runs before the original scalar dispatch; V3 moves the test
into its nonescaped branch. V3 substantially reduces the escape-heavy penalty,
but mixed JSON-like median is still about **12% slower** (V2 about 19%). A clean
string best case is insufficient justification to change the WAL encoder.
Both candidates are **rejected for product adoption**. Keep the existing
implementation and prior accumulated optimizations.

This is a synthetic component screen, not the actual rich 8.6MB application
statement, a native floor, cross-runtime comparison, S3 result or SDK throughput
claim. Because it loses on mixed input, no expensive Durable ABBA was launched
for it. No model logic/exhaustive check was added or rerun.

### Harness correction, not a hidden successful retry

The first invocation terminated with timeout exit124 at 150s, no result/log.
Its source hash was
`93dcbfd8f06fdcbfbea20a92fdffbd0d0eff19d3e7df7ba981b80c3710426517`.
It built persistent vectors of both large outputs and parsed every large JSON
scalar during preflight. After authoritative terminal status, V2 replaced that
comparison with direct `Arrays/equals`, retained small roundtrips and added
progress markers. V2 and V3 both completed successfully. No timeout is counted
as a correctness pass; the two preflight changes were not separately profiled,
so this is not a quantified diagnosis of the initial timeout's exact cost.

## What changes next

Do not spend the next performance slice accelerating metadata parsing or
shipping this clean-run scanner. The independent codec/traversal layering and
the previous nested-scratch/cache improvement remain intact. Prioritize the
larger measured projection/encoding/WAL preparation costs, or bounded ordered
preparation/publication overlap after explicit Revision B type/scope approval.
The ~25k p50 / ~20k p99 confirmed-row objective remains unmet; no new
concurrency or acknowledgement behavior was implemented in this packet.

## Reproduction

From the named exporter base worktree, use the stock binary at
`/home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt`,
`JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0`, `-Srepro`, and paths `src`, `test`, `bench`.
Run the component script with a new absolute report path. For the metadata
run, reproduce the exact existing SDK harness environment/fixture paths in
`research/nested-fragments-20261019/run-durable.sh`, select arm `on`, invoke the
profile script with a fresh exact owned store, then complete the older reader
and validator gates before deleting only that store. Source hashes and all
small metadata receipts accompany this document. Scripts depend on the named
workspace fixtures; they are not a standalone benchmark distribution.
