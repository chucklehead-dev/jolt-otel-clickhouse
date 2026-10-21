# Operation-level allocation diagnostic

2026-10-21, exporter runtime remains main `f0aa75dd595dff82290fb2c19389d2a6ad02c1e6`.
Stock Jolt0.8.23 /Chez10.4.1; SDK6cd57c86, unchanged normalization/codec/typed
rules. No product/default/pin/compiler/model changes in this diagnostic.

The previous coarse screen grouped normalization, row construction, typed
projection and nested JSON. This probe wraps those OPERATION callbacks in native
Scheme and aggregates only scalar counts/times/bytes, not per-value records or
payloads. It deliberately observes per-row operations and perturbs execution;
it is NOT the previous coarse no-per-row-probe measurement or a throughput gate.

## Raw result and calibration

One source-mode process, plain/observed/observed/plain,16samples per arm of5k
rich spans. Every complete8,647,784-byte output matches; event/attribute changes
change output. Required phase counts are checked for every observation.

| Mode | p50 /p90 /p95 /p99 ms | Median estimated B/row |
| --- | --- | ---: |
| No observer |165.82 /241.88 /264.52 /264.52|9,768.84|
| Native operation observer |212.01 /271.95 /280.76 /280.76|12,009.90|

Observer median time is~27.9% higher. Do not treat observed timings as production
costs or compare this process's absolute rates to another historical process.
No observer correction is applied to raw reports.

| Observed inclusive operation | Calls/5k | Mean ms | Raw allocated B/row |
| --- | ---: | ---: | ---: |
| Attribute conversion |15,000|39.14|1,874.73|
| Nested text |10,000|35.48|1,766.09|
| Typed projection |5,000|25.91|1,426.24|
| Row including all above |5,000|155.62|8,379.66|

The observer takes two `statistics` snapshots around each operation. An empty
callback control observed35,000calls,17.36ms aggregate inclusive real time,
5,600,000raw allocated bytes:160B/call inside the observed interval. The whole
sample's added estimated allocation is~2241B/row. These use different counter
boundaries; the empty callback does not isolate the total cost of all snapshots
and wrapper work. Do not derive or subtract a fixed observer correction or
claim an allocation-site profile from their numerical proximity.
Row observations include nested observer work; do NOT sum inclusive phases.

This singled out typed projection for the adjacent field-slot implementation:
the classifier promotes only a Boolean and an integer, yet its collection and
assembly cost is material. The subsequent fused-candidate screen is separate,
not a modification to this report or a claim this diagnostic made code faster.

## Counter semantics and a historical correction

`sstats-bytes` reports cumulative Scheme allocation, unlike the public
`jolt.host/bytes-allocated` live-heap reading. The phase delta therefore uses
that raw field; whole-batch comparison retains the earlier separate live-heap/
reclaimed-byte estimate. These are not native-C allocation, retention/RSS,
per-object or atomic cross-thread accounting guarantees. `sstats-cpu` is the
observing THREAD's CPU time, not process-wide CPU. See the primary
[Chez statistics documentation](https://cisco.github.io/ChezScheme/csug10.0/system.html).
Stock Jolt0.8.23's `sa-stats` maps `cpu-nanos` to that field directly.
The corresponding misleading process-CPU metric is tracked separately in
[casselc/otel#70](https://github.com/casselc/otel/issues/70), with source-derived
evidence and a proposed worker-burn regression control, not a claimed tested fix.

The old matched-native-floor note incorrectly labeled its `cpu-nanos` readings
"whole-process CPU"; the numerical readings remain, but that attribution is
corrected there. They cannot establish publisher/native-worker CPU consumption
or CPU-budget headroom for overlap. Wall-clock rates, complete-byte matching
and independently verified recovery are unaffected by this label correction.
The existing two-stage wall-time overlap estimate remains a hypothesis, not a
resource-accounted architectural ceiling or measured pipeline result.

## Reproduce / boundaries

From exporter root, use the pinned stock binary and mandatory Chez wrapper:

```sh
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src" "research/scalar-view-exporter-20261020/app/src"]}' \
  research/native-row-costs-20261021/screen.clj /absolute/owned/screen.edn
```

Callbacks must be ordinary native procedures in this small diagnostic, not
arbitrary IFn implementations. Reports copy scalar counter values, not the
mutable aggregate buffer. Callback results/errors are forwarded; partial
failures are not counted as completed observations. Code/fixture helpers are
workspace research dependencies, not a standalone profiling distribution.

No database, native build, bulk artifacts or runtime-global helper installation.
Raw scalar samples and checksums are retained; no exhaustive/model runs, SDK
queue, native/Durable, S3, Oscope, Langfuse or population-tail qualification.

## Review

Claude's source-only review checked scalar snapshot ownership, observation counts
and table arithmetic. Its calibration criticism prompted removal of the optional
snapshot-cost formula; no raw data, diagnostic code or timing was changed.
Codex qualification:host live-heap plus reclaimed-byte deltas and cumulative
`sstats-bytes` both concern managed allocation, not unrelated units, but their
sampling/observer boundaries differ and do not establish an exact correction.
The reviewer also inferred that160B necessarily includes both snapshot records;
that attribution was not independently established and is not adopted. Retain
the unedited review as advisory; the report claims neither precise correction
nor an object profile. Source checksums pass; no unchanged reprofile is needed
for this prose-only narrowing.
