# Key-only fragment cache: not adopted for the current pipeline

Tested weak identity-key reuse and a content-equal-key alternative, including
short keys omitted by the previous >=16-character string-cache experiment.
Per record-encoding call: 64 insertions / 8KiB encoded bytes / 4KiB per complete
quoted key. No source maps or telemetry values retained. Candidate production
changes are removed; exact implementation and four tests / 92 assertions are
preserved in candidate.patch and candidate_test.clj. Apply the patch first to
reproduce the screens; it is not an enabled maintained-library feature.

Rich untyped projection/encoding, 5,000 rows, eight interleaved samples per arm:

| Arm | p50 ms | p90 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|
| Original merged kernel | 171.04 | 261.69 | 261.69 | 261.69 |
| Modified kernel, cache off | 171.08 | 207.39 | 207.39 | 207.39 |
| Weak identity keys | 182.74 | 276.76 | 276.76 | 276.76 |
| Content-equal keys | 175.04 | 269.70 | 269.70 | 269.70 |

All completed outputs were byte-identical. No measured target-fixture win;
therefore no real Durable run, public exporter flag, default change or repin.
The selected production source remains exactly the merged f31107c source.

Generic 5,000-map controls, eight samples/arm, p50 milliseconds:

| Keys | Original | Off | Identity | Content |
|---|---:|---:|---:|---:|
| Shared short | 3.22 | 3.53 | 3.07 | 4.22 |
| Fresh equal | 3.74 | 3.66 | 4.45 | 5.21 |
| Unique | 4.21 | 4.39 | 4.86 | 6.23 |
| Shared long | 8.96 | 7.65 | 3.33 | 6.85 |

The shared-long-key microbenchmark win does not justify claiming a rich-pipeline
gain. This may remain useful for a future explicitly shaped workload, but the
fresh/unique controls show why it should not become a universal default.
Timings are preliminary, not production-tail qualification. Whole-field
preencoding in a schema view remains a different, untested design.

Candidate tests preserve exact byte/depth budgets, chunk/size/retention bounds,
key-only retention, output independence and producer failure order. An initial
mechanism assertion incorrectly assumed separate equal String literals shared
identity; fixed by binding one shared key. No production bug was found there.
Generic screen startup errors (relative path, Delay dereference, a local name
shadowing core/keys) were corrected before any reported samples. No failed or
incomplete run contributes measurements.

Reports: /home/chuck/ai-src/evidence/key-fragments-rich-screen-20261020.edn and
key-fragments-generic-controls-20261020.edn. No database/build artifacts created.
Next code target: guarded burst writes for escaped characters, where the
current native codec still uses repeated per-byte checks and cursor updates.
