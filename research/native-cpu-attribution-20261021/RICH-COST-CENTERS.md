# Rich source-path CPU diagnostic

`rich-cost-centers.clj` runs the existing 5,000-span rich fixture with the real
SDK converter, typed projector and scalar-view codec on official Jolt 0.8.23.
No compiler, SDK, policy, format or persistence changes. This is a source/private
component probe, not an AOT/Durable throughput qualification.

Fixed order plain/shape/timed/timed/shape/plain, 16 samples per arm. Shape wraps
the same callbacks and counts calls without cost-center timing. Timed adds
public Chez `with-cost-center` calls. Construction is outside the measured batch.
All output bytes agree (8,647,784 bytes); changing an event changes the output.
Each wrapped batch has 5,000 row and typed calls, 15,000 attribute calls, 10,000
nested calls and one batch call. Exact callback exceptions propagate unchanged.
Only scalar observations are retained, not telemetry or encoded output samples.

| Arm | p50 / p90 / p95 / p99 ms | Estimated managed bytes/row |
| --- | --- | ---: |
| Plain | 198.59 / 446.56 / 494.61 / 494.61 | 8870.68 |
| Shape/counting | 190.81 / 395.24 / 534.74 / 534.74 | 8870.96 |
| Timed | 247.18 / 448.89 / 492.61 / 492.61 | 13014.77 |

These sample tails are not population tails. Substantial within-run CPU/wall
drift makes differences particularly uncertain. Cost centers increase median
allocation ~46.7% and wall time ~24.5% versus plain; shape alone does not show
a measured allocation increase of that scale. Do not subtract a fixed observer
cost or publish observed region percentages as production proportions.

Timed inclusive CPU medians: batch 247.15 ms, row 161.36 ms, attributes 46.34 ms,
typed 13.81 ms, nested 40.97 ms. Row includes all three subregions; batch includes
row. Medians are computed independently and are not additive. Region CPU is
executing-thread only; no native worker, durability, queue or actual I/O occurs.
The broad actionable finding is that neither row preparation nor final encoding
can be ignored. Fine-grained cost centers are too intrusive for a release gate.

Next bounded experiment: remove per-digit output bookkeeping by copying each
already-formatted integer's private scratch range directly into output chunks.
Preserve exact bytes, signed/unsigned boundaries and chunk/budget enforcement;
screen full rich encoding without region observers before considering adoption.

Run from the exporter root, prefixing the stock Jolt executable with the required
Chez 10.4.1 wrapper:

```sh
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src" "research/scalar-view-exporter-20261020/app/src"]}' research/native-cpu-attribution-20261021/rich-cost-centers.clj research/native-cpu-attribution-20261021/results/rich-cost-centers.edn
```
