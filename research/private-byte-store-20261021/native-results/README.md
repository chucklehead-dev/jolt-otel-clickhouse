# Confirmed local Durable qualification

Stock Jolt0.8.23 / pinned Chez10.4.1 / native26.9.0, final kernel453fe44.
Two default-release binaries, not one switchable image. Baseline SHA702dda47
is the previously verified archived app, copied/cmp to ext4. Candidate
SHA8176d816 is built from the same exporter/application sources and declared
SDK6cd57c86/driver3314b79/JSON0f51b991/crypto5effcc89 dependencies, with only
the codec JSON kernel changed. Full hashes/classpath accompany the results;
selected-source checks are NOT complete transitive compile-time attestation.

Both binaries use `perf.field-slot-pipeline` with `fused`, the SAME constructor
wrapper, no per-row observers. Historical `field-slots-arm=fused` is retained
in ALL writer receipts: it does NOT identify this experiment's codec arm.
The external run label and independently recorded executable SHA distinguish
baseline/candidate; `summarize-native.bb` checks those expected hashes.
This is real synchronous confirmed export, not live SDK queue ingestion.

Declared order A1/B1/B2/A2; each fresh store has3warmups +32measured5k callbacks.
No timed run overlapped an agent-started build/review. Host load/frequency was
not controlled. No repeated favorable trial, dropped sample or unchanged sweep.

| Arm | Confirmed window rows/s | p50 / p90 / p95 / p99 ms | Estimated managed B/row |
| --- | ---: | --- | ---: |
| A1 baseline | 13612.73 | 344.85 / 455.76 / 466.86 / 604.40 | 14812.54 |
| B1 candidate | 14785.07 | 330.83 / 372.81 / 391.76 / 397.99 | 14813.97 |
| B2 candidate | 14542.10 | 335.35 / 378.94 / 435.15 / 444.41 | 14813.90 |
| A2 baseline | 13255.26 | 366.59 / 420.19 / 493.30 / 495.96 | 14800.03 |

Both directional pairs favor candidate window rate: +8.61% forward and +9.71%
reverse. Pooled descriptive window rate13431.62 ->14662.58rows/s (+9.16%).
Allocation is effectively unchanged14806.29 ->14813.94Brow. Baseline run rate
drifts ~2.6%, candidate~1.6%; preserve these splits. Four runs do not establish
a stable effect size, statistical significance or population-tail guarantee.

Pooled candidate p50/p90/p95/p99:332.05/378.54/397.99/444.41ms, versus baseline
354.26/455.76/488.64/604.40ms. Candidate inverse-latency rows/s:
15058/13209/12563/11251. P99=max for32sample runs and64pooled samples. These
are observed sample tails, not target/population qualification. The25k
inverse-p50/20k inverse-p99 goals remain UNMET (200/250ms per5k). No S3,
Langfuse, Oscope, real SDK queue or other-batch-size claim follows.

## Independent recovery and receipt controls

Each writer exited0 and was followed by a separate older source reader at
exporter0af1008185cf84577298d3a0a0b2db87b97bddd5, JSON
3adc8c5d3a57a30d18f62bad9d1c7a5c1f6857e0, same stock/native versions.
The helper-only storage selector is from chDB worktreea92f60b4; LOCAL is
explicit, no credentials/environment data is printed. Exact invocation and
source selection are in `run-qualification.sh` and accompanying manifests.

All700,000 physical rows independently recovered, every field/type/status,
exact timestamps,5000groups/625traces/35copies and deterministic tie order per
run. Count-only/current-writer readback is not substituted. Existing validator
and9receipt mutations pass per run. Raw receipts are never modified; summary
is independently recomputed from samples and expected binary identities.

Selected source/binary/native hashes match before AND after all runs. Each
store was copied/cmp at receipt level and retired only after writer, reader,
validation and mutation controls finished. At most one synthetic store was
retained at a time. No app database, credentials or user telemetry was deleted.
Keep the small source/report evidence; generated build/store bulk is disposable.

`bb research/private-byte-store-20261021/summarize-native.bb` recomputes the
summary from these raw receipts. Historical field-slot validators remain
unchanged; they validate confirmation/geometry/recovery, not a trusted runtime
codec attestation. The fixed invocation/hash/copy checks supply provenance.
