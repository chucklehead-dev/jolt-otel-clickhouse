Real-binary follow-up (no helper/factory source overlay):

- Exporter `4d48871` pins optional native JSON `96f3e47`, retaining SDK `7878a2e` and the
  existing ordinary JSON/runtime pins. CI [39150093348](https://github.com/chucklehead-dev/jolt-otel-clickhouse/actions/runs/39150093348) passed all three jobs,
  including scoped S3 SDK and native JSON backends.
- Standalone candidate `631984d6` passed 65 JSON tests / 1,006 assertions and 56 exporter
  tests / 981 assertions plus 58 dependency controls. The same exporter checks also
  pass on the older hosted `1ae` binary without the new native helper.
- 32 measured 5k SDK-shaped batches: confirmed-window 9,264 rows/s; callback p50/
  p90/p95/p99 = 510/632/670/692 ms. Independent older-stack recovery verified 175,000
  rows and typed/order/multiplicity controls. Candidate-only local evidence;
  not a controlled speedup, qualified p99 throughput or hosted-S3 measurement.

The new runtime's full artifact CI caught a genuine omitted DCE dependency:
the helper's private `clojure.core/sm-ops` Var was not rooted. Corrected qualified
source `7098a8d6` and latest-release extraction `18dce94a` add the root without weakening
the checker. Focused root gates and 79/79 native fold checks pass; read-only Claude
review found no defect. New exact-pinned producer [39151375518](https://github.com/casselc/jolt/actions/runs/39151375518) is running from
workflow `54a37192`; no artifact/runtime pin is promoted until its full gates pass.

Next: complete runtime qualification, matched performance comparison, then app
dependency integration. Source/scalar receipts are retained; successful owned
bulk build/store intermediates have been cleaned up.
