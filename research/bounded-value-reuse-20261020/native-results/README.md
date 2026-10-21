# Confirmed local Durable comparison: no adoption

2026-10-21; implementation `b8d5501a55237e6f9ce2d9a2d5cfb49c96e1b36b`.
One default-release executable, official stock Jolt0.8.23, Chez10.4.1,
libchdb26.9.0. Order declared before candidate results: A1 views, B1 reuse,
B2 reuse, A2 views. Each fresh process/store writes3 warmups+32 measured
callbacks of5,000 rows. No simultaneous local heavy workload or Claude review.

| Run | Confirmed window rows/s | Batch p50 /p90 /p95 /p99 ms | Estimated B/row |
| --- | ---: | --- | ---: |
| A1 views |11,295.8|436.07 /512.14 /551.61 /557.21|15,708|
| B1 reuse |13,341.0|355.79 /440.72 /520.09 /541.50|15,740|
| B2 reuse |12,578.5|400.65 /456.03 /511.75 /516.44|15,737|
| A2 views |12,754.5|377.62 /446.45 /485.93 /533.50|15,703|

Pooled windows:11,980.9 baseline versus12,948.5 candidate (+8.08%). Baseline
drift is12.91%; the reverse pair favors baseline by1.38% in window rate and
also favors it in median latency. Estimated allocation slightly increases.
This does NOT establish a repeatable causal throughput or tail gain. Sample
p99 is the maximum of32, not a qualified population tail. Targets remain unmet.
No retry or discarded unfavorable timing; retain all observations.

## What the comparison exercises

The research shim in `../app/` selects the public library provider with the
SAME provider/constructor wrappers in both arms. It forwards original SDK
policy bindings unchanged, asserting1provider/36constructors. Reuse reports
the actual final planner stats `[1 302 9999 1]`. It is not a product flag.
The unchanged inherited public app constructs resource/scope/event contexts
with17 sorted fields, distinct map roots and repeated equal values, approved
typed span fields, exact timestamp ticks, real schema installation/checkpoint,
and actual synchronous confirmed exporter calls. No live SDK queue is involved.
The fixture reuses String constants; retention of equal-but-distinct Strings is
covered by the earlier alias-specific tests, not proven by this workload.

Both arms use the same binary and dependency pins: SDK6cd57c86 (Git, NOT the
inherited app's older local SDK root), driver3314b79, crypto5effcc89,
JSON0f51b991. The component needs no SDK/data.json/compiler fork; this complete
consumer still selects ecosystem forks. No runtime/default/pin changes here.
The inherited writer's older scope/private-probe labels describe its body;
the research shim explicitly marks provider selection and is NOT an unmodified
release-optimization control. Runtime counters are non-atomic estimates, and
diagnostic printing is outside callback timing but inside the window.

Each writer was followed by the independent older-reader script from exporter
worktree `exporter-stock-nested-fragments-20261019`, using JSON3adc8c5d for its
real guard Var. It verifies every physical field/type/status, exact nanoseconds,
5,000 groups,625 traces,35 copies and175,000 rows per store:700,000 total.
Writer reports alone do not prove recovery. No cross-arm SQL byte digest was
captured; do not claim SQL-hash equality. The WAL is framed binary, not bare
JSON despite its `.jsonl` extension.

## Reproduce and validate

From `../app`, build with the mandatory workspace Chez wrapper and official
stock executable, `JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0`:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0 \
  /home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt \
  -Srepro build -m perf.value-reuse-pipeline -o /absolute/owned/value-reuse-app
```

Run that SAME binary from a neutral directory, with a fresh absolute root per
run and `JOLT_CHDB_LIB` pointing at26.9.0. Arguments are `ROOT views` or
`ROOT value-reuse`. Run the old independent reader with explicit JSON3adc8c5d
and its original benchmark/helper paths before validating or retiring a store.
These are workspace research scripts, not a standalone benchmark distribution.
The small copied receipts can be revalidated without native work, from exporter
root:

```sh
bb research/bounded-value-reuse-20261020/validate-native.bb \
  research/bounded-value-reuse-20261020/native-results/B1 value-reuse
```

Each directory contains raw `writer.edn`, independent `reader.edn`, and
`validated.edn`. Source/executable/native checksums are recorded separately.
All writer/reader jobs are terminal; all four copied receipt pairs compare
equal to originals and pass the validator. Each generated store was removed
only afterward with `tools/retain-run-artifacts`; small logs and retention
receipts remain under `evidence/value-reuse-native-20261021.Dg2Ce4N6`.
The completed executable/build tree was also retired after checksum capture;
the run root now retains about60KiB, not native binaries or databases.

## Review and custody limits

Claude's bounded read-only source/receipt review agrees that the report supports
keeping reuse unadopted. It found an incomplete source-hash manifest: the
factory-selection Clojure file was omitted. `post-run-source-sha256.txt` now
includes that file, both scalar planner resources, traversal and writer sources;
all committed component/inherited-app files compare unchanged to `b8d5501`.
These are POST-run source checks, not a retroactive compile-time attestation or
complete resolved-dependency manifest. The original executable checksum remains
in `source-and-artifact-sha256.txt`. Do not claim stronger custody from them.
The review cited the factory source beyond the named review-file list, so strict
file-list compliance is not claimed. No credentials/persisted data were in the
review packet. Codex owns execution; source review is not recovery proof.
The receipt validator also rejects five in-memory controls: missing round,
false confirmation, absent cache selection, incorrect physical recovery and
inflated throughput. No persisted receipt was modified for these checks.

## Next implementation decision

Do not add a production option or spend another native sweep on this strategy
unchanged. Preserve its mechanism/tests as optional research. The independent
traversal/byte-codec library remains useful without value reuse. Prior matched
prepared-input native evidence demonstrates local backend headroom, but uses
another historical workload and is NOT a causal decomposition of these runs.
Next, isolate the remaining normalization/row construction, encoding and WAL
preparation costs on the SAME current workload, then improve the largest stage.
No concurrent preparation/publication implementation until its pending model
scope is approved. No new model or unchanged exhaustive check was needed here.
No S3, Oscope, Langfuse or live-app qualification is implied.
