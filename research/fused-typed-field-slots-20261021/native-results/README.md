# Confirmed local Durable comparison

2026-10-21. Declared order A1(indexed), B1(fused), B2(fused), A2(indexed).
One default-release binary for both arms, SHA256
`702dda478703aae460a28c8ac8276485d16e33b175357331deb13a42831c50eb`,
built on official stock Jolt0.8.23 with pinned Chez10.4.1, native chDB26.9.0.
Product source is exporter `f4b4e457600de951e8f8e62faacab6be9ca855cb`;
research app/dependency pins are in the adjacent `app/` directory.
Compiler and ordinary-JSON implementations were not modified for this slice.
This does NOT mean the complete consumer uses no ecosystem forks.

## Results

Each fresh writer:3warmups plus32measured confirmed5k callbacks,175k stored
physical rows. The original public synchronous exporter writes actual Durable
objects, with the same approved descriptor installation and physical fixture.
Both arms use the SAME constructor wrapper: indexed declines only the new
factory, fused calls it. Factory/selection checks run after export, with no
per-row probe. The live SDK queue is not exercised. Diagnostic batch printing
is outside each callback latency but inside the confirmed window.

| Arm | Confirmed window rows/s | p50 /p90 /p95 /p99 ms | Estimated B/row |
| --- | ---: | --- | ---: |
| A1 indexed |12,174.08|391.22 /510.83 /521.35 /533.36|15,705.99|
| B1 fused |12,391.71|384.33 /514.02 /543.79 /563.80|14,813.53|
| B2 fused |13,653.03|346.92 /428.10 /436.35 /466.91|14,801.86|
| A2 indexed |12,174.71|410.79 /472.76 /495.40 /502.05|15,705.66|

Both pairs favor candidate window rate:forward+1.79%, reverse+12.14%; baseline
rate drift+0.0052%. Pooled descriptive rate12,174.40 ->12,991.83rows/s (+6.71%).
Mean run allocation estimate15,705.82 ->14,807.70B/row (-5.72%). These support
a bounded allocation improvement. The pooled rate advantage is driven mainly by
B2; the forward pair improves only1.79%, whereas candidate run rates differ by
about10%. Two runs per arm do not establish a stable throughput effect size or
statistical significance. Preserve the raw split instead of treating+6.71% as
a reliably repeatable gain.

Pooled latency p50/p90/p95/p99: indexed406.56/480.62/510.83/533.36ms,
fused361.05/466.91/514.02/563.80ms. The slow tail is NOT improved. Per-run
p99 is the maximum of32 samples; pooled p99 is the maximum of64. These are
sample statistics, not population-tail qualification. Pooled fused reciprocal
p50/p90/p95/p99 rates13,848/10,709/9,727/8,868rows/s remain well short of the
25k inverse-p50 /20k inverse-p99 targets (200/250ms per5k respectively).
Window rate and reciprocal batch quantiles are deliberately different metrics.

No claims about S3, real SDK queue admission/shutdown, Oscope, Langfuse,
high-cardinality schemas, other batch sizes or process-wide CPU are inferred.
Allocation uses separate managed live-heap/reclaimed-byte readings, not native
C/RSS/retained-memory or object-site accounting. CPU counters in raw receipts
observe the calling thread, not all process workers.

## Recovery / controls

All four writer processes finished normally. Each was followed by a separate
older-stack source reader at exporter
`0af1008185cf84577298d3a0a0b2db87b97bddd5`, stock runtime and JSON adapter
`3adc8c5d3a57a30d18f62bad9d1c7a5c1f6857e0`. Each independently proved all
175,000 physical rows,5000groups,625traces, every field/type/exact nanosecond,
35copies per record and deterministic tie order. Total700,000 rows; count-only
or current-writer readback is not substituted for independent recovery.

Raw `writer.edn`, `reader.edn` and validated receipts are retained for each arm.
The validator checks selected branch, confirmed callbacks, geometry, quantiles,
rate, allocation estimate and the full independent recovery receipt. Nine in-memory mutations are
rejected, with a valid positive control; persisted receipts are never edited.
The summary script computes pooled quantiles from raw samples, not from averaging
run quantiles. No receipt flag claims population-tail or target qualification.

From exporter root (Babashka orchestration only):

```sh
bb research/fused-typed-field-slots-20261021/validate-native.bb \
  research/fused-typed-field-slots-20261021/native-results/A2 indexed
bb research/fused-typed-field-slots-20261021/validate-controls.bb \
  research/fused-typed-field-slots-20261021/native-results/A2 indexed
bb research/fused-typed-field-slots-20261021/summarize-native.bb
```

Native app build from adjacent `app/`, using the mandatory Chez wrapper and
the pinned official stock binary:

```sh
JOLT_NO_USER_DEPS=1 jolt build -m perf.field-slot-pipeline -o /absolute/owned/app
# Do not put -Srepro before build: stock0.8.23 misdispatches that form.
SDK_STORAGE_ARM=local JOLT_CHDB_LIB=/absolute/libchdb.so \
  /absolute/owned/app /absolute/fresh/run fused
```

Prebuild source hashes were checked again after all writer processes finished;
`post-run-source-sha256.txt` matches the original manifest byte-for-byte and
`post-run-source-check.txt` records the passing check.
The manifest is a selected-source integrity check, NOT complete transitive
compile-time attestation. All original receipts were copied and `cmp` verified
before synthetic stores/scratch were retired. Run root:
`/home/chuck/ai-src/evidence/fused-field-slots-native-20261021.Uq9e14B0`.
Retain only small receipts/logs/manifests after retiring the now-finished build.

## Adoption decision

The serial field-slot optimization has a measured allocation benefit, lower
observed median latency and unchanged full recovery. Stable throughput-effect
size and slow-tail improvement remain unproven. It is
reasonable to review it for the existing experimental native projection path.
Do not enable it in Oscope or declare the throughput/tail goal achieved from
this experiment. The next larger opportunities remain fused normalization/row
construction and separately approved bounded preparation/publication overlap.
