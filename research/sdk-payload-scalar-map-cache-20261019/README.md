# Payload-local scalar-map reuse: component win, pipeline result inconclusive

## Custody and outcome

This is an unselected prototype, not a dependency/default change. No new SDK,
exporter or worker mode was enabled in Oscope. The existing reviewed exporter
singleton-event update did merge separately as Oscope PR170, merge
`b6ac5c10ab04b9ff4504d895ba2280359e88a0d2`; all six checks passed and its tree
`c4e0056eafffea981484bbf13b6540728bfe9b72` equals the locally tested tree.

- First SDK prototype: `21a1c64` on `perf/payload-scalar-map-cache-20261019`.
- Native-budget refinement, actually measured: SDK
  `c474e0285203fc48f205cba39fccf53202ac49d7`.
- Actual exporter wiring measured: `d0aac4e2202542bb1926ac8a3558474be45c427d`.
- Follow-up SDK boundary fix/test/changelog checkpoint:
  `c407d5fd4b616b04322b398d6ea4324c7c4d9016`. This is NOT the measured SHA.
- Exporter changelog-only follow-up: `eef591471773eeb8564a4eccf84d23119a73f161`.
- Runtime7098a8d6, native JSON2172061, driver3d766fa, explicit native26.9.0.
  All local Jolt execution used the pinned Chez10.4.1 wrapper. No compiler build,
  system install change, upstream submission, model edit or exhaustive run.

## Component evidence

Actual `json-each-row-payload`, lazy production row construction, owned statement
output, 5k rich rows/28 columns. Explicit64MiB insert bound (an existing option),
not an increase in the8MiB product default. Each of64 samples matches the entire
8,648,398-byte payload and SHA256
`862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39`.
Changed-input control changes the digest.16 observations per shape/cache setting;
p95/p99 select the same largest sample, not qualified population tails.

| Source shape | Off/on p50 ms | Off/on p90 ms | Off/on p95=p99 ms | Off/on estimated B/row |
| --- | ---: | ---: | ---: | ---: |
| Repeated shared maps |217.958/160.399|255.763/172.750|265.708/203.490|18126.4/14768.2|
| Independently constructed equal maps |220.359/234.897|261.127/263.585|262.585/320.431|18125.5/18320.7|

Shared median time falls26.4%, estimated allocation18.5%. Unshared median time
still regresses6.6%. Do not hide the latter or claim a universally faster encoder.
This fixture deliberately has repeated primitive resource/scope/event attributes;
all-String maps already have a different existing reuse path. It is not a real
Samizdat/OTLP workload or a cross-host comparison.

The first implementation's component screen found shared230.696→207.952ms,
but unshared232.972→298.314ms and18126.7→26656.1B/row. Its extra Clojure policy
walk, hit/miss stamp allocations and weight-reduction traversals were removed in
c474e02, cumulatively atop the prototype. These findings are retained, not discarded.
The V1 screen's attempted private-alias miss counters were both0: constructing
the converter while aliases were replaced invalidated its anchors. That mechanism
instrumentation was vacuous, NOT evidence of zero conversions. The current driver
constructs the calibration closure before replacing trusted private aliases and
observes5001plain misses/1sorted miss for a full shared payload. Timed payloads
each construct a fresh uninstrumented closure. The driver was corrected after
V1; its current source is not a byte-identical frozen V1 invocation script.

## Real SDK + local Durable ABBA

Off/on/on/off in four fresh processes, one serial SDK worker/exporter, same
20k-slot queue and ordinary serialized Durable connection.32measured5k callbacks
after3warmups per arm. Full ordinary writer/old-stack reader/validator outputs
are retained. All700,000physical rows independently recovered, including values,
types, statuses, event/trace data, ordering within traces and multiplicity.

| Arm | Confirmed-window rows/s | Callback p50/p90/p95/p99 ms | Estimated B/row |
| --- | ---: | --- | ---: |
| Off A1 |11915.0|402.4/449.0/505.4/506.7|24393.7|
| On B1 |12593.6|365.2/482.4/546.2/577.7|21031.7|
| On B2 |11471.2|401.3/553.3/640.5/643.6|21032.5|
| Off A2 |10980.3|423.1/525.0/574.8/644.6|24396.3|

Pooled confirmed-window11428.6→12006.2rows/s (+5.05%), while bracketing controls
drift8.51%. Allocation falls about13.8%, but this is not convincing throughput
qualification and the tails do not consistently improve. Nothing meets25k/20k
targets here. Queue-to-span end-to-end latency, actual app arrivals, S3 throughput,
Rust parity, failure injection and a new public mode are not qualified by this run.

Source-derived policy is unchanged: only already-admitted immutable roots may
reuse converted contents, wrappers remain fresh, all8SDK roots are checked live,
sorted kind/tree/ops/count participate, key+value characters and insertions remain
bounded, source metadata is not strongly retained. Original converter bodies,
ordinary core/runtime internals and stable input during each call are trusted.
Woven converter bodies need separate qualification; Var identity is not that proof.

## Tests, reviews, and the boundary defect

First Claude source review completed; main added requested distinct-wrapper
shared-root and same-count changed-root controls. Second independent review of
c474e02 completed, no concrete bug found. Main did not promote review to proof:
a further late-policy-change test reproduced `false` instead of documented `nil`
after native store rejection. One-line owner-boundary normalization fixes this;
red15tests147pass/1fail/0errors, final green16tests153pass/0fail/0errors. Existing
map-helper suites11tests144assertions pass. Exporter focused wiring4tests16assertions
passes with the real local SDK (factory-lifetime tests themselves use a simulator).
Portable JVM/BB optional-loader smoke tests also pass on the final follow-up.
Third narrow Claude review of the nil/false correction completed, no finding;
main inspected the exact change and reran the focused gates. Source branches
are published in casselc/otel and chucklehead-dev/jolt-otel-clickhouse; no PR
or dependency adoption was requested from these unselected measurements.

No accepted persisted value was changed by that return-shape fix, and the exporter
already treats both false/nil as declines. Still, the measured SHA remains c474e02;
do not relabel those timings/recovery as c407d5f qualification.

Two initial research-harness failures were before measurement: malformed fixture
construction and the unconfigured8MiB default for the >8MiB fixture. A later
writer wrapper initially compiled its `fixture` alias before loading it and failed
before opening storage. The empty owned root was removed with `rmdir`; corrected
ABBA ran separately without overwriting its failed log. No production bound relaxed.

## Cleanup and next action

All four synthetic stores were removed only after terminal writers, independent
readers, validators and byte-identical receipt copies:
`ljZh1nR8`, `zg76yykJ`, `ZhxjI3L7`, `5vLisF7Q` under the explicit
`/tmp/sdk-payload-cache-durable-20261019.*` task prefix. Source, commits, scalar
results, small logs and reviews remain; no database is needed to reproduce them.

Do not enable this by default on component numbers alone. Next isolate encoding,
WAL preparation/storage and native insert time with coarse boundaries that do not
perturb per-row dispatch. Reuse the existing RowBinary/Rust comparison work rather
than reinventing it. Resolve unshared-map regression and woven-body scope before
any product adoption; maintain cumulative selected wins and independent recovery.
