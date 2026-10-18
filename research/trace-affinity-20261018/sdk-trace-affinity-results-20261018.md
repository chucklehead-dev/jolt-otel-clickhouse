# Trace-affinity and paced SDK capture

Research only: no new product API/default, compiler change or codec contract.
Fixed trace hashing routes 625 traces, each with eight distinct sibling spans,
to one or two distinct serial SDK/exporter lanes sharing one Durable connection.
Source repeats 32 cycles of 5,000 spans; three direct warmups go to exporter 0.
Each arm has 160,000 measured spans and 175,000 independently recovered rows.

## Saturation comparison

| One / two / one lanes | Confirmed aggregate rows/s | Export calls | Callback p50/p90/p95/p99 ms | Managed B/row |
|---|---:|---:|---|---:|
| A1: one |28,945|32|154.789 /203.060 /217.007 /227.691|8,214|
| B: two |44,583|35|195.764 /252.864 /260.312 /264.266|8,269|
| A2: one |27,897|32|166.860 /206.095 /214.779 /273.173|8,215|

Two lanes retain higher capacity with trace affinity, about54–60% above the
controls in this window. Partial lane tails make callback sizes variable
(200–5,000 rows in B); do not divide all callback quantiles by 5,000 or claim
the per-request tail target. No matched Rust or sustained-tail qualification.
One hot trace necessarily stays on one lane: no single-session speedup claim.

## Open-loop nominal 20k spans/s arrivals

Source emits chunks of 100 on absolute 5ms deadlines. It never waits for
queue space in this mode; overflow fails the gate. Actual offered rates and
maximum lateness are measured, not assumed from the configured target.

| One / two / one lanes | Observed offered rows/s | Confirmed rows/s incl. final drain | Export calls | Callback p50/p90/p95/p99 ms | Managed B/row |
|---|---:|---:|---:|---|---:|
| A1: one |20,012|19,740|98|73.350 /124.775 /132.712 /172.947|10,374|
| B: two |20,007|19,543|160|98.237 /133.405 /149.778 /171.043|13,946|
| A2: one |20,012|19,787|84|77.089 /158.296 /177.763 /338.522|9,770|

All three deliver every offered span with zero failures/drops. Source lateness
maxima:2.337 /8.765 /5.060ms; capacity waits exactly zero. Callback batches are
300–3,500 /356–1,796 /400–5,000 rows. More lanes do not help this arrival rate:
they cause more smaller exports and ~34–43% more managed allocation per row.
Per-callback latency is not queue residence or span-end-to-confirmation latency.
The producer and verification overhead are included in global wall/counters.
Source arrival timing/shape is synthetic, not real agent or socket ingestion.

## Correctness and controls

All six arms pass full physical/typed equality and 35 copies of every record:
**1,050,000 rows**. Writer checks exact per-lane counts, one lane per trace,
per-trace sibling callback order and final count, every callback's literal
true export result, actual native stream calls, SDK statistics and empty queues.
Independent BB verification checks receipts; fresh reader uses stock JSON on
the qualified compiler. No admission-as-persistence, preencoded SQL reuse,
fsync/WAL/CAS weakening or measurement-time force-flush/shutdown.

Total queued capacity stays10k; extra in-flight batches/memory can differ.
All first callbacks remain measured, including cold SDK/second-exporter work.
No physical/global append-order or cross-process hash-stability guarantee.
Pure routing tests pass on BB and stock Jolt0.8.22 (3tests/653assertions), with
round-robin and fan-out mutants rejected by independent routing obligations.

Fresh-reader sort uses TraceId,SpanId, not TraceId alone now that traces have
siblings. Claude tail review identified two assumptions for own verification:
actual fixture sibling keys must be unique and sampled flags must not change
stored span fields. Both hold on the exact pinned fixture: a separate control
checks all5k keys, rejects a duplicate-key mutant, and checks5k sampled/unsampled
physical row pairs. The selected span schema does not persist trace flags;
this experiment does not claim that interoperability feature. No concrete
bugs found in the tail review's routing/pacing/accounting scope. Initial broad
review6517 ended137 without report and is NOT credited as review completion.
Successful narrower review15234 was source-only/advisory; no review of app/S3.

## Reproduction and cleanup

Frozen exporter67 /SDK37 /JSON8d /driver3d, fixture3fd test helpers,
qualified1ae runtime/pinned Chez10.4.1 and cached native26.9. Exact clean source
and native SHA guards are in run-sdk-trace-affinity-screen-20261018.sh.
Source/reader/helper snapshots remain unchanged from the measured packet;
standalone fixture controls were added afterwards without rerunning unchanged
native cohorts. Summarizer independently recomputes rates and all quantiles;
sample counts vary, so at160callbacks p99 is second-largest, not maximum.
No tail stability is inferred from these short correlated windows.

Terminal0 runs: saturated A1=65588,B=33530,A2=32629;
paced A1=2124,B=2088,A2=75253 (already polled terminal; later poll correctly
reported missing handle, no rerun). Each includes fresh reader, scalar copy/
comparison, BB verification and exact-owned-store cleanup. Generated stores
266+267+266+304+337+295MiB (~1.7GiB total across serial runs) removed after proof.
Small sources, review and scalar EDNs retained; no native stores or raw telemetry.

## Next implementation slice

Do not adopt two lanes as a default from this result. A separate bounded SDK
candidate now rechecks readiness/schedule after each live export, avoiding
immediate partial drain chains under continuous arrivals. Caller/retirement
drains stay complete. Tracked at casselc/otel#62; causal tests reproduce4failures
on oldsource and pass on candidate, with full SDK suite473/4109 green.
Matched native qualification and code review remain pending. This targets
consumer-induced small-batch costs, NOT the direct encoder/Rust relative gap.
