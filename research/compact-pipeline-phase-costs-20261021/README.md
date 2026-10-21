# Current compact-pipeline phase costs

2026-10-21, exporter main `f0aa75dd595dff82290fb2c19389d2a6ad02c1e6` (remote
main refreshed before branching). No product source, compiler, SDK policy,
default, dependency pin or protocol/model changes. The diagnostic app explicitly
selects the same Git SDK6cd57c86, driver3314b79, JSON0f51b991 and canonical
crypto5effcc89 as the preceding public standalone workload. Official stock
Jolt0.8.23 /Chez10.4.1 /libchdb26.9.0; no unadopted value-reuse strategy.

## Result and decision

One instrumented default-release executable, actual synchronous confirmed local
Durable writes,3warmups+32measured5k callbacks. The independent older reader
verified every physical field/type/status/exact nanosecond and multiplicity:
175,000 rows,5,000 groups,625 traces,35 copies. Source/executable checksums,
resolved classpath, raw writer/reader/phase receipts and summary are in `results/`.

| Observed phase | Mean ms/5k | Callback time | Estimated managed B/row |
| --- | ---: | ---: | ---: |
| Normalization, row projection and JSON encoding together |245.00|46.1%|9,414|
| WAL preparation |41.27|7.8%|2,124|
| Preparation inclusive of both above |308.19|58.0%|13,276|
| Outside preparation (combined residual) |223.07|42.0%|not separately measured|

Mean measured callback531.26ms. Preparation also includes~21.92ms not attributed
to the first two phases: owned snapshot, validation, wrapping and probe overhead.
Do NOT sum inclusive preparation with its children. The owned-snapshot probe
was not observed, so neither its time nor its allocation is separately known.
The outside-preparation residual is NOT isolated native execution/publication:
it includes admission, dispatch/waits, final checks and probe overhead.

The largest observed CPU-side stage remains normalization/row construction/
encoding. It accounts for~60% of whole-window estimated allocation (9,414 of
15,709B/row); WAL preparation alone is not the main missing win. Next, separate
projection cost from encoding already-materialized rows on this same rich
fixture, preserving complete-byte equality and policy controls, then optimize
the larger part through the stock-Jolt traversal/codec library. A native row
view previously saved allocation without proving a median win; do not silently
promote it. Keep public Clojure data structures at the edge and use an explicit
native kernel underneath, not another dependency-wide JSONWriter replacement.

This diagnostic uses private Var wrappers and affects release optimization;
do NOT compare its absolute rate with another binary to claim a regression or
speedup. No paired observer-overhead control was run, so even the distribution
is diagnostic evidence, not an exact uninstrumented decomposition. Original
operation results/errors are forwarded. There are no per-row/field probes.
Runtime counter reads are separate snapshots, not allocation-site samples,
RSS bounds or a whole-process OS CPU profile. GC/counter costs can nest.
All raw32observations and descriptive p50/p90/p95/p99 are retained; sample p99
is the maximum, not population-tail qualification. Targets remain unmet.

## Probe mechanism and failed attempt

The wrapper around the existing private preparation function establishes a
serial ordinal/dynamic sample scope. Public codec and WAL functions are captured
by the original loader during exporter construction, preserving their real
operations and all SDK policies. Exactly35preparations,32confirmed measured
callbacks and32measured observations of each REQUIRED phase are checked.
Codecs' startup probes outside that scope are not mislabeled as payload work.
No delivery or native stream count is invented from diagnostic counts.

V1 wrote all175,000 rows successfully, then FAILED its own assertion expecting
35private publication-wrapper observations. The wrapper did not observe that
boundary; release inlining is the likely cause, not a proven compiler defect.
It saved no usable phase receipt and supplies no phase conclusion. Its small
source/writer/independent-reader/hash receipts remain in `failed-probe-v1/`.
V1's independent recovery also passed before its bulk store/build was retired.
V2 removes that probe and explicitly labels time outside preparation as a
residual. Missing optional snapshot observations are NOT zero-cost work. V2
saves raw observations before mechanism assertions, then requires actual
preparation/encoding/WAL observations, so a missing REQUIRED probe fails.

## Reproduce

From `app/`, with fresh owned absolute output/root paths:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0 \
  /home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt \
  build -m perf.compact-phase-costs -o /absolute/owned/phase-app
```

Keep `build` first: `-Srepro build` on stock0.8.23 fails with unbound
`jolt.host/build-binary` before compilation. Its early `jolt-cli-build-cmd?`
peels `-Sdeps`/`-A`, not `-Srepro`; the latter is handled only later by
`jolt.main`. `JOLT_NO_USER_DEPS=1` supplies equivalent isolation. The original
failed build log is retained separately, not relabeled as a runtime failure.

From a neutral directory, run with `JOLT_CHDB_LIB` set to the26.9.0 library:
`/absolute/owned/phase-app /absolute/fresh/root`. Then run the unchanged old
independent reader from `exporter-stock-nested-fragments-20261019`, with its
benchmark/helper paths and explicit JSON3adc8c5d dependency for the actual guard
Var. These remain workspace research scripts, not a distributable harness.
Revalidate copied results without native work from exporter root:

```sh
bb research/compact-pipeline-phase-costs-20261021/summarize.bb \
  research/compact-pipeline-phase-costs-20261021/results
```

All native writer/reader/build jobs are terminal. The result copies were
compared byte-for-byte before retiring each exact generated store. Bulk artifacts
are not needed to interpret or revalidate these small receipts; final build
retirement is recorded in the workspace retention receipts. No unrelated
processes/caches/user data were removed. No S3, live SDK queue, Oscope, Langfuse,
concurrent preparation/publication or fault-history qualification is implied.
One local heavy workload at a time; no Claude review during timed native work.

## Follow-up: encoding the same already-built rows

`projection-vs-wire.clj` extends the preceding rich component screen, not the
native app. It uses the original SDK-owned converter/shape-view plan and typed
projector, capturing one batch of actual physical rows OUTSIDE measurement.
It then interleaves full encoding with encoding those immutable rows through
the SAME codec: full/wire/wire/full,16observations/arm. Every complete output
compares equal, including the nested JSON String fields and typed columns.
Changed event, typed source attribute and physical-row name each change output.
The typed-source change also changes generic attributes; it is NOT an isolated
typed-projection mutation oracle. The existing independent native recovery is
the separate all-field/type gate.

| Source-mode component | p50 /p90 /p95 /p99 ms/5k | Median estimated B/row |
| --- | --- | ---: |
| Full normalization/projection/nested+outer encoding |289.65 /402.27 /449.37 /449.37|9,769|
| Encode already-built physical rows |99.09 /147.72 /159.00 /159.00|3,467|

The complete outputs are8,647,784bytes each, including a synthetic `SQL` prefix
(not executable SQL). This is an optimistic conditional floor, NOT a measured
production speedup or a precise additive projection cost: the wire arm excludes
normalization, row construction AND preparation of nested JSON text. It retains
one prepared batch; its construction/retention costs are deliberately excluded,
not eliminated. Byte equality is checked after timing, so its work is excluded
from callback times but can affect subsequent GC. Runtime snapshots are separate,
private helpers are used, and no release optimization/SDK queue/native I/O/S3/
population-tail qualification is implied. Do not subtract these source-mode
medians from the separate native/release phase means as an exact decomposition.

Next implementation target: bounded pre-escaped key/schema reuse inside the
independent byte codec, benefiting varied values and nested JSON as well as
outer rows, with an unchanged-kernel control. It must preserve order, escaping,
limits, ownership and policy guards; no stock-Jolt/data.json fork dependency or
default change is needed to prototype it. Validate complete bytes and changed
inputs, then require repeatable native gains before adoption. This is a next
experiment, not an asserted fix or a replacement for the remaining pipeline work.

The wire-only estimate is already close to two payload-sized buffers:
`2 * 8647784 / 5000 = 3459.1B/row`, versus3467.3observed. Own inspection confirms
the writer fills private chunks then copies them into a fresh independent final
bytevector. This is a resource-accounting clue, not an exact object profile or
proof that traversal allocates nothing. Eliminating the~6302B/row difference
requires work on normalization/row/nested-text intermediates, not merely a faster
final byte loop. Key/schema reuse primarily targets CPU work; retain that
distinction when evaluating it, and do not abandon the materialization target.

Reproduce from `app/` with the pinned stock runtime/wrapper, user deps disabled:

```sh
jolt -Srepro -Sdeps '{:paths ["src" "../../scalar-view-exporter-20261020/app/src" "../../../test"]}' \
  ../projection-vs-wire.clj /absolute/owned/projection-vs-wire.edn
```

This uses the existing test fixture's descriptor installation, not native DDL.
It creates no database, native build or bulk evidence. Raw scalar observations
are retained in `results/projection-vs-wire.edn` and the script hash is recorded.
The phase report's Claude source/receipt review found no blockers; Codex checked
its reversed inequality in the review prose against the actual correct code
(`callback >= preparation`) rather than copying that typo into the report.
V1's incomplete source manifest remains historical, not a complete build proof.
Six in-memory controls reject empty writer/phase samples, missing required phase,
false confirmation, bad recovery and negative phase time without editing receipts.
The separate component follow-up review also found no blockers. Its output-lifetime
conclusion was independently checked against `json.ss`: every output gets a fresh
final bytevector, while scope cleanup clears only the private fragment/scratch
state. Both review texts are in `results/`; neither review ran the experiments.
