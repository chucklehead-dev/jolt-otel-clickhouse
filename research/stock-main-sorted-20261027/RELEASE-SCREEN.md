# First main-runtime release screen: recovered, target still unmet

## Packaging without repeating Scheme compilation

The earlier source-CLI build completed its Scheme compilation but did not
produce an executable before the WSL restart. Its generated boot-data C header
was about 412 MB; cc1 had reached about 5.4 GB RSS. The exact exit/OOM cause is
unknown and must not be stated as confirmed.

We restored only `main.c`, `jolt_zlib.h` and `jolt.boot.vfasl` from the verified
archive, then embedded the **same** 66,826,921-byte boot payload as a read-only
ELF object with objcopy. Extracting that section and comparing it to the payload
passed. The original launcher is unchanged; a tiny declaration/length file
replaces the giant C initializer. No Scheme code was recompiled or reoptimized.

GCC link elapsed 0.42 seconds, maximum RSS 81,620 KiB, exit 0. This is a local
Linux x86-64 research packaging workaround, not a portable upstream-ready patch
or an improvement to telemetry runtime throughput. Installed Jolt is unchanged.

Binary SHA256:
`aeb563fc255c7bd93e9ccb040a0b0c91d12406dc77838a405d628b8a2a516a98`.
Runtime/source dependency checkpoints are those in [README](README.md).

## Measured screen

Native chDB 26.9.0; upstream Jolt main `8ba11735`; Chez 10.4.1. Existing real
SDK queue/overlap pipeline, 5,000 rows/batch, 3 warmups, 32 measured batches,
16 MiB insert bound, rich typed fixture. No concurrent local build/test/review/
model/archive task during the timed writer. Host load and pressure recorded
outside the window. All writer/reader processes reached exit 0.

| Observation | Result |
| --- | --- |
| Aggregate confirmed rows/s | 20,257.06 |
| Confirmation cadence p50/p90/p95/p99, ms | 227.610 / 341.882 / 401.727 / 408.098 |
| Corresponding inverse-cadence rows/s | 21,967 / 14,625 / 12,446 / 12,252 |
| Estimated allocated bytes/row | 24,118.07 |
| Attempted/exported rows | 175,000 / 175,000 |
| Failed/dropped | 0 / 0 |

This single 32-sample screen is **not** a population-p99 guarantee, request
latency measure, matched before/after comparison, or achievement of 25k/20k.
Configured codec/view options and constructor capability checks ran; there is
no additional direct per-row fast-path activation counter in this executable.
Do not infer that every eligible row used a particular optimized branch.

The allocation estimate is higher than older ~14 KB/row screens. The exposed
live-heap and reclaimed-byte counter fields retain their earlier semantics;
main adds a seventh cumulative-allocation field without shifting these fields.
Counter snapshots remain separate/process-wide, and the estimate is not a
retained-size measurement. A matched stable-runtime graph is needed before
attributing the difference to a particular runtime/library change.

## Independent recovery and retention

A source-only older driver/SDK/ordinary-data.json graph on official 0.8.23
created a separate JSONEachRow oracle, read both stores in fresh processes,
and compared all physical fields/types/exact timestamps and multiplicities.
All **175,000** measured/warmup rows matched (5,000 distinct row groups x35).
The standalone smoke's 24 rows also independently matched.

Evidence: `evidence/stock-main-lowmem-20261027.nAMzXlcM` in the parent workspace.
Keep its checksummed packaging/measurement/recovery runners, logs, host/source/
binary manifests, writer and reader receipts, summaries and unique tiny
packaging sources. After terminal successful recovery, explicitly pruned both
synthetic stores and restored packaging scratch; the evidence directory is
about 65 MiB including one retained executable. Original failure archive and
older control binaries are untouched.

## Next

Build official 0.8.23 with the **same** canonical SDK/codec/driver graph and
entrypoint, smoke/recover it, and compare isolated release measurements. Do not
adopt unmeasured main-only changes or reintroduce the unadopted HAMT/probe code.
If the allocation increase is real, profile preparation on this matched graph
before chasing another unrelated encoder micro-optimization. No upstream PR
has been opened.
