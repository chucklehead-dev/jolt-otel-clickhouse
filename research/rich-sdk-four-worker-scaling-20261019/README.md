# Four-worker resource sweep, closed-data bound and Oscope follow-through

Previous goal turn was progress (rich two-worker ABBA +70%, full recovery).
This turn extends that topology/resource experiment, tests a closed-data
counterfactual and opens a tested/reviewed Oscope pin PR. Goal remains active:
25k/20k targets, actual app capture and S3/Rust qualification are incomplete.

## Six fresh 1/2/4/4/2/1 arms, fixed total queued capacity

Selected graph unchanged: checksum-pinned hosted runtime7098/Chez10.4.1,
exporter2c/SDK7878/nativeJSON217/driver3d, explicit native26.9.0. No product source
overlay, compiler rebuild, default/pin update or model change in these runs.
5000rich source records per round,32measured rounds,3warmups on exporter0only,
625traces/eight siblings each, fixed lifetime trace affinity. Same160kmeasured
rows/arm; distinct serial exporter/SDK worker owners share one borrowed Durable
connection. All operations still await ordinary confirmed publication.

Total queued capacity20000for all layouts; per-lane capacity20k/10k/5k. Batch cap
5000fits each queue. This compares bounded topology choices, not a pure worker-
count intervention: at4workers there is no additional queue headroom above one
batch, while1/2have more headroom. Actual partial batches and extra publications
are retained, not forced to full5000by weakening a gate or slowing arrivals.
Saturated producer waits for space; no open-loop arrival acceptance claim.

| Arm | Workers | Rows/s | CPU us/row | Effective cores | Callback p50 /p90 /p95 /p99 ms | Process HWM MiB |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| A1 | 1 | 5548.8 | 224.3 | 1.24 | 872.9 /987.7 /1065.2 /1105.0 | 903.6 |
| B1 | 2 | 16340.4 | 132.7 | 2.17 | 538.0 /643.0 /735.9 /736.2 | 1195.1 |
| C1 | 4 | 20325.0 | 130.9 | 2.66 | 857.0 /987.6 /1029.3 /1082.5 | 1386.7 |
| C2 | 4 | 18799.8 | 147.1 | 2.76 | 881.2 /1037.2 /1056.7 /1227.4 | 1344.3 |
| B2 | 2 | 15305.4 | 141.9 | 2.17 | 599.6 /711.6 /872.1 /922.1 | 1157.6 |
| A2 | 1 | 10875.5 | 110.6 | 1.20 | 433.6 /512.5 /606.1 /646.0 | 910.1 |

**The one-worker controls drift by1.96x.** No pooled causal lane-speedup is
qualified. In particular, don't divide the fastest4-worker arm by the first
slow control or multiply older10.4kby a prior ratio. Source graph is fixed;
the cause of the between-window cost change is not established. Calling-carrier
CPU≈wall is not proof of descheduling, clock/frequency or GC as that cause.
The independent OS counters also show roughly doubled CPU time/row in A1
versus A2; GC CPU is0.90/0.49seconds versus35.88/17.70total CPU seconds, so it
does not by itself account for the entire difference. No retry-until-fast,
discarded arm or changed acceptance threshold. Raw counters remain.

Four workers reach18.8–20.3k aggregate rows/s but use more memory and have worse
callback tails than the later single-worker control. This does not meet25k/20k,
per-request or sustained-tail targets. The4-worker callback medians have4464/
4472rows, maxima5000, minima56;2-worker medians5000/minima200. Request/stream
counts32/35,35/38,38/41,39/42,34/37,32/35(measured/native) are verified explicitly.
Fragmentation is an observed cost, not a correctness failure to conceal.

### Resource/validation scope

Linux/proc/self/stat fields14/15cover process user/system ticks including native
threads; field20is thread count. Two coarse snapshots bracket the wall-clock
window, tick resolution100Hz, non-atomic/quantized and with small adjacent
bookkeeping overhead. Do not treat derived cores as an exact scheduler trace
or assume the readings can only overestimate. /proc/self/status RSS is resident
memory at the two samples. HWM covers process lifetime including setup/warmups,
not a reset measurement-only peak. /usr/bin/time files cover whole invocations,
not just160krow windows. Preflight2tests/25assertions exercises the actual sampler
form read from the frozen source, all lane domains, missing IDs and duplicate/
round-robin negative controls. Main's post-validator additionally checks BOTH
OS samples, EACH tick delta and exact arithmetic, with negative-tick/missing-
before-RSS mutations rejected without rewriting real execution receipts.

All6writers/readers/validators complete0; older runtime1ae/exporter473/SDK37/
JSON8d readers verify all175kphysical rows per arm, full values/types/typed
status, event ticks, trace grouping/multiplicity:1,050,000rows total. No drops or
failed exports; per-lane callbacks serial, cross-lane overlap observed, all trace
counts/order hold. Six rootsCeQpzsRJ/Dktlh03v/dsOyZMuI/0lRIi4mp/1Ad7t9xs/
Z3bZ0Tsk under/tmp/rich-sdk-scaling-20261019. were removed only after terminal
children, full validation and copied/cmp receipts. No bulk stores remain.

Claude75470 completed source-only review. Main accepts the queue-headroom,
fragmentation and metadata-scope cautions above. Native stream invocation counts
are NOT a cross-lane native-execution trace. Main independently inspected exact
driver3d: public execute-owned-and-flush! enqueues a request; writer.clj737worker
loop takes and synchronously executes one request before taking the next. This
is the existing serialized-owner contract, not bypassed raw native access.
No new runtime assertion of native interval non-overlap was added here; add
appropriate request/native trace/failure/settlement tests before a supported
pool mode. Do not confuse callback overlap with concurrent native-handle entry.
These results remain research, not product concurrency/tail/S3/Rust proof.

## Closed builtin-data counterfactual

Same compiled factory with a private flag switches between selected guarded
emission and deliberately closed builtin-data emission. Same escaping/buffers/
budgets and production row construction; no precomputed rows or attributes.
The closed side omits JSONWriter root/method/receiver checks and key callbacks.
A live-root control MUST yield0on the normal side andquotedprobeon closed side,
proving the semantic incompatibility. It is never a transparent backend change.

64samples,16perarm/mode; every timed output matches the FULL8,648,398bytes and
SHA256862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39.
An ordinary complete-row reference and changed-input output control also pass.

| Mode | Normal /closed p50 ms | Normal /closed p90 ms | Normal /closed p95=p99 ms | Normal /closed B/row p50 |
| --- | --- | --- | --- | --- |
| Lazy production rows | 234.846 /230.325 | 383.849 /265.849 | 386.490 /294.918 | 16380.2 /13033.6 |
| Prepared inside binding | 230.748 /215.125 | 365.542 /323.030 | 370.744 /355.320 | 16408.3 /13062.0 |

About20%less estimated allocation but only~2%lazy/~7%prepared median change,
not a demonstrated pipeline gain. This weakens the theory that dispatch guards
alone explain the remaining speed gap. It is NOT an architectural maximum or
ideal encoder: map normalization, nested Strings and the existing escape/sink
loops remain. No product mode, new capability API, upstream change or adoption.
Further useful work should target actual eager SDK-to-wire materialization,
with a full-value/digest oracle and all normal persistence gates, rather than
polish a hook-bypassing encoder and call it compatible. The earlier fragment
review's missing guards/adjudication remain in the preceding checkpoint.

## Oscope PR170: actual app dependency integration

https://github.com/chucklehead-dev/oscope/pull/170
branchdeps/singleton-event-exporter-20261019; head
04a5db20fef83d51800c248b5ee51bfa285ccb25, base57a8597e11d6d123137a5b67a3f0f2d5748bed9c,
tested treec4e0056eafffea981484bbf13b6540728bfe9b72. Exactly3files: root exporter
4a→2c, exact resolved-root test/new real old4amutation, changelog. SDK/driver/
ordinary/native JSON/profile/runtime/native defaults unchanged. No worker mode.

GLM85668 completed but its edit policy denieddeps/changelog and it restored
its test attempt; main verified the tree clean and applied the bounded diff.
No restriction bypass or external-agent qualification claim. Claude92136
source-only review completed without functional findings; main owns actual gates:
113tests/1589assertions across5root namespaces;3serial native fixtures embedded,
standalone,typed embedded with real stream/JSON companion and stable receipts.
Embedded/standalone include independent readers; typed is live typed query only.
Root tests are not separate minimal-profile167/live Samizdat/Langfuse qualification.

As of17:29UTC PR170 had4green checks and Linux/headless stillinprogress, all
at exacthead04a/base57a. Do not merge from this snapshot: refresh checks/head/
base/participants and use the guarded exact-head merge only when allgreen. No
approval blocker, no forced/manual green/restarted CI. Local heavy runs and
all external tasks in this slice are terminal. Further perf/integration remains.
