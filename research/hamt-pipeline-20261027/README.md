# Completed constructor-only HAMT-view pipeline comparison

**Decision: do not adopt this variant on these results.** Allocation falls
consistently, but the small pooled throughput difference is not a repeatable
per-arm speedup. Preserve this result and move to the remaining projection/JSON
costs rather than repeating unchanged trials for a favorable sample.

The fresh one-binary disabled/enabled/enabled/disabled campaign completed on
October 27, after the earlier [clock-interrupted screen](../hamt-pipeline-20261021/RESULTS.md).
Both arms use the new SDK; `disabled` is not a literal old-SDK comparison.
The selector is an atom read only at codec construction, not a dynamic binding
spanning worker lifetime. It is an explicit research patch, not product config.

Default release binary SHA256:
`52050ea8f63af3e24c2bde238c054bdebdbd1267a9f82d1988654122b7c50802`.
Stock Jolt0.8.23, Chez10.4.1, chDB26.9.0. SDK `a4a13b0`, codec prototype
`4650e35`, driver `312812f`, JSON `0f51b99`, stock crypto `5effcc8`.
The [input ledgers and compact receipts](results/) record actual paths/hashes.
Reproduce from research base `953b0ed`, its explicit constructor-only patch and
the updated `research/hamt-pipeline-20261021/run-qualified.sh`. The branch tip's
net product src/resources diff against codec prototype4650e35 remains empty.

Each real SDK/Durable run has 3 warmups and32 measured batches of5000 rich typed
spans. Same fixture, saturated startup-only queue preload,16MiB insert bound,
coarse incremental diagnostic printing and two-slot overlap. Preparation and
confirmation-return timestamps come from the unchanged public pipeline.

| Arm | Confirmed window rows/s | Cadence p50/p90/p95/p99 ms | Estimated B/row |
| --- | ---: | --- | ---: |
| A1 disabled | 10,575 | 438.39 /607.05 /725.79 /857.57 | 14,452 |
| B1 enabled | 10,257 | 479.06 /611.19 /747.73 /779.32 | 14,044 |
| B2 enabled | 11,543 | 421.85 /480.99 /500.09 /515.75 | 14,043 |
| A2 disabled | 10,720 | 465.05 /562.09 /591.74 /695.48 | 14,453 |

Pooled confirmed-window throughput: **10,647 ->10,862 rows/s (+2.02%)**.
Estimated allocation: **14,452 ->14,044 B/row (-2.83%)**. B1 regresses against
preceding A1, while B2 improves against following A2; this is not a stable
per-arm wall-clock benefit. No uncertainty interval or population-tail claim.

Pooled inverse batch cadence p50/p90/p95/p99 rows/s:

- Disabled:11,188 /8443 /8049 /5830.
- Enabled:11,416 /8673 /8181 /6416.

These are not request latencies or independent per-row throughput percentiles.
At64 measured intervals per arm, nearest-rank p99 is the sample maximum. Host
load/CPU/memory-pressure snapshots were taken outside each writer window and
are retained; they do not prove an interference-free VM or explain the large
absolute drift from the older qualified ~22k result. Do not attribute that drift
to the candidate or to dynamic binding without a causal experiment. Targets
~25kp50/~20kp99 remain unmet. No S3, paced app or Oscope qualification follows.

All four writer runs had zero SDK failures/drops. **700,000 rows** independently
recovered through the older driver3314b79/SDK6cd57c8/JSON3adc8c5 reader graph.
Ordinary JSONEachRow oracle comparisons checked every physical field/type,
exact timestamp/event ticks and35 copies per5000 fixture rows. There were four
separate recovery processes per arm; SHA256 collision resistance, the shared
fixture/schema and ordinary control exporter remain trusted dependencies.
The native smoke separately recovered24rows. Requested-mode receipts alone do
not prove activation; the earlier source-rich-fixture mechanism check provides
that separate evidence, not a native hot-path timing probe.

All source/binary terminal checks passed. Successful synthetic stores were
retired only after copying compact receipts. No new model, ownership, lease,
admission, schema or production-default change; no exhaustive campaign.

The SDK initialization option-forwarding fix is separate: it enables an already
supported adapter to reach the maintained constructor. It does not adopt this
HAMT optimization or broaden the exporter's mixed-signal preparation contract.
