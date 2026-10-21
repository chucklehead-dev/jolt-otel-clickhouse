# Exporter opt-in for SDK-owned scalar-text views

Additive `:experimental-span-scalar-views?`, defaultfalse, requires the existing
codec/payload-cache/shape-plan options and the NEW separate SDK projector.
Missing SDK projector, codec view factory or codec box rejects before JDBC;
invalid flags cannot select a downgrade. Disabled calls do not resolve the new
capabilities. Original map APIs, exporter key/value guards, late generic fallback,
typed projection, nested-event String semantics and confirmation are unchanged.

This checkpoint builds on library implementationdbbe399 (reviewed at a646a72)
and SDK implementationd11752a (reviewed at023fe33). Default SDK/root published
codec pins are intentionally unchanged. `:scalar-views-test` selects GitSDK
023fe33 and checkout-local codec only for the new CI gate; no local-root SDK is
needed by CI. An application must deliberately select compatible dependencies
before enabling the experimental flag. This is not an Oscope repin/default.

New exporter gate3tests/18assertions covers actual preflight, each missing
capability, invalid options, disabled-path resolution, opaque result selection,
wire equality and changed exporter policy fallback. Existing SDK/library gates
are separate evidence, not substitutes for this consumer test.
The same3/18 gate also passes with the actual published GitSDK selected by the
new alias, not just the local-root SDK. The first fetch was network-denied;
after confirming that process was absent, the authorized network run passed.

## Cumulative rich component

Official stockJolt0.8.23 /Chez10.4.1; all currently selected caches plus typed
projection,5,000 equal-but-distinct-root SDK-shaped spans. Full output bytes
equal after every observation; changed-event controls reject equality.16
interleaved observations per arm:

| Arm | p50 /p90 /p95 /p99 ms | Median estimated B/row |
|---|---|---:|
| Existing plain-map handoff |162.43 /185.37 /187.52 /187.52|11306|
| Scalar views |159.18 /170.44 /282.21 /282.21|9770|

Allocation about13.6% lower; median time only about2% lower. View ordinal13
has126.34ms GC time within282.21ms elapsed (CPU282.20ms). Keep that observation:
there is NO demonstrated tail benefit. Statistics are separate snapshots, not
an allocation-site profile. This includes per-payload SDK policy/cache work and
typed projection, not live SDK capture, queueing, native insertion or Durable.
Private-Var component probes are identical across arms; this is not a release
optimization qualification or a causal end-to-end throughput result.

## Public standalone app and actual Durable

`app/` reuses the qualified public synchronous app/fixture from checkpoint
77065f6. Only new arm validation/flag/reporting is added to main. Both arms use
ONE default release executable; no private probes/redefs/benchmark imports in
main.3warmups+32 measured5k callbacks, native26.9.0, raw ticks, real approved
typed installation and schema checkpoint. SDK queue/live tracer not exercised.
SDK is an explicit LOCALROOT at clean023fe33 for these runs; other declared
consumer pins are driver3314b79 /crypto5effcc8 /JSON0f51b99. Actual classpath and
source/executable hashes are retained. This is not published-Git SDK resolution
or an upstream-only stack; the separate alias/CI gate exercises GitSDK.

| Arm | Confirmed window rows/s | p50 /p90 /p95 /p99 ms | Estimated B/row |
|---|---:|---|---:|
| Views, run first |12918|379.19 /470.89 /482.50 /494.01|15705|
| Maps, run second |12556|373.33 /483.41 /506.06 /604.61|17247|

Estimated allocation falls about8.9%. This one sequential pair is NOT ABBA or
a causal throughput/tail win: window rate changes~2.9%, median actually slightly
worse for views.32samplep99 is the maximum, not population qualification. Do
not compare absolute timings to older source/release runs as causal evidence.
Targets remain unmet. No new S3/Langfuse/live-app/OScope qualification.

Both writers and independent older-stack readers completed successfully:
350,000 physical rows total, all fields/types/status/exact nanoseconds/multiplicity.
Both owned synthetic stores and the standalone build were retired only after
terminal success, receipt validation/copy comparison and source/executable hashes.

## Reproduce

Mandatory workspace Chez10.4.1 wrapper, official stock executable, with
`JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0`. New GitSDK gate from exporter root:

```sh
jolt -Srepro -M:scalar-views-test
```

Rich screen additionally needs the public fixture namespace from checkpoint
77065f6's `research/public-pipeline-release-build-20261020/app/src` and root
test/bench paths, plus explicit SDK023fe33 local root. The script writes only
scalar counters/results, no native store. Its fixture/descriptor helpers are
workspace research dependencies, not a standalone benchmark distribution.

From app/, default `jolt build -m perf.public-pipeline -o /absolute/owned/app`
(keep build first). Run the SAME binary with separate fresh absolute roots:

```sh
JOLT_CHDB_LIB=/absolute/libchdb.so /absolute/owned/app /absolute/fresh/views release view
JOLT_CHDB_LIB=/absolute/libchdb.so /absolute/owned/app /absolute/fresh/maps release map
```

Use the unchanged old independent reader documented by stock-app checkpoint
77065f6; compare physical data, not the writer report. Preserve/cmp small
reports/source/executable hashes before retiring each exact synthetic store and
the binary/build directory after terminal success. No user files or broad
caches are cleanup targets. Do not upload native binaries or database stores.

Next: bounded source review, actual GitSDK gate/hosted CI and final recovery
receipts. Adoption requires separating allocation benefit from throughput
claims; these modest changes alone do not close the overall target. Continue
larger projection fusion and evaluate bounded ordered preparation/publication
overlap under the appropriate model scope, not another unbounded value cache.
