The next nested-allocation slice is now measured and its library fix has landed
in [casselc/data.json #20](https://github.com/casselc/data.json/pull/20).

Root cause: the default JSON omission check compared a function sentinel to
sorted attribute maps using structural equality. Jolt normalized those maps
before returning false, allocating about 6.7 KB per rich row. The library now
uses identity only for the known default marker; custom value-function equality
retains its previous behavior. Focused old-source red/new-source green checks,
existing contracts, JVM tests, source review and hosted CI passed.

Matched SDK-to-local-Durable ABBA, four fresh writers with 5,000-row batches:

| Measurement | JSON 96f3e47 | JSON 2172061 |
| --- | ---: | ---: |
| Confirmed-window rows/s | 8,429.7 | 10,304.0 |
| Estimated allocated bytes/row | 31,939.7 | 25,233.2 |
| Callback p50 ms | 554.7 | 442.7 |
| Callback p90 ms | 721.5 | 596.4 |
| Callback p95 ms | 796.9 | 646.2 |
| Callback p99 ms | 871.0 | 680.6 |

Runtime 7098a8d6, exporter 4d48871, SDK 7878a2e and driver 3d766fa are unchanged.
Four independent older-stack reader processes recovered all 700,000 rows with
full field/value/status controls. Two process repetitions per arm do not qualify
service tails, S3 or Rust-relative performance. This remains below our objective.

The exporter pin/rollup candidate is pushed at 4a42716 and passes 56 focused
tests / 981 assertions plus 58 dependency checks. Full cumulative source review
is running. Hosted workflow [39272493202](https://github.com/chucklehead-dev/jolt-otel-clickhouse/actions/runs/39272493202)
has passed the native-JSON and scoped-S3 SDK jobs; its aggregate test job is
still running. The scoped-S3 correctness job is not an S3 throughput claim.

An underlying Jolt equality shortcut is deliberately held: review found and we
reproduced a legacy normalized-operand extension behavior change, tracked in
[aspect-packs #182](https://github.com/chucklehead-dev/jolt-aspect-packs/issues/182).
No runtime shortcut or compatibility weakening is needed for the library win.

Sources, scalar samples, red/green controls and independent recovery receipts
are preserved in [research checkpoint 30f0731](https://github.com/chucklehead-dev/jolt-otel-clickhouse/tree/30f0731/research/default-omission-identity-20261018).
All four validated synthetic stores were retired between runs. Next: complete
exporter review/CI, propagate to Oscope, then reduce remaining dispatch,
materialization and row-shaping allocation without bypassing live callbacks.
