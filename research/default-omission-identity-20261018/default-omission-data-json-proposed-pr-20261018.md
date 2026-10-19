# Compare the known default omission marker by identity

The default writer uses its private value function as an omission marker. Check
that known marker by identity instead of asking arbitrary values for structural
equality. This avoids rebuilding a whole sorted collection just to reject it as
the marker on Jolt, while keeping the default function value itself omitted.

Custom `:value-fn` callables retain their existing equality check and callback
order. Reader value functions, JSONWriter dispatch, key callbacks, escaping,
backend defaults and output limits are unchanged. The fork README/changelog
explicitly disclose that default-marker checks no longer invoke equality
extensions. Native admission still rejects custom callbacks before emission.

## Validation

- Baseline fails the two new structural-equality mechanism checks; candidate
  passes 4 tests / 16 assertions. Independent positive observer control prevents
  an inert probe from passing. Custom comparison remains observed.
- Existing byte/sorted/segmented/optional-fold gates: 65 tests / 1,006 assertions.
- Existing native live-hook/string gates: 38 tests / 621 assertions.
- Portable Jolt compatibility checks pass.
- JVM Clojure 1.12.2: 90 tests / 254 assertions pass.
- Read-only Claude review found no correctness defect; main independently
  checked the option/stock admission and retained publication boundaries.
- Four fresh full-encoding processes, baseline/candidate/candidate/baseline,
  5,000 rich rows per sample: observed p50 308 → 241 ms, estimated allocation
  23.9 → 17.2 KB/row. Complete output size/hash and changed-input controls pass.
  This is a component result, not a sustained-throughput or tail guarantee;
  observed component p99 did not improve.
- Matched actual SDK → local Durable comparison: confirmed-window throughput
  8.43k → 10.30k rows/s (+22%), estimated allocation 31.9 → 25.2 KB/row.
  Observed batch-callback p50/p90/p95/p99: 555/721/797/871 → 443/596/646/681 ms.
  Two process repetitions per arm; all four independent older-stack readers
  recovered the expected 175,000 rows each. This is not S3, service-tail or
  Rust-relative qualification. Both exact-head fork CI workflows are green.

Fork-only stack: base `perf/optional-stock-sorted-fold-20261018` at `96f3e47`,
head `2172061`. No PR to clojure/data.json is authorized or submitted.
