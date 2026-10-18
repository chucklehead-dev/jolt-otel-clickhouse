# Proposed title

perf: use SDK-owned sorted scalar attribute normalization

# Proposed body

Use the optional SDK sorted scalar-map helper before the existing scalar-map
helper. SDK-produced sorted attribute maps otherwise repeat the same scalar
conversion/policy chain for every field. The defining SDK can qualify a bounded
stock map without invoking custom operations, then return the plain map that
the exporter needs.

Retain generic conversion for older SDKs, unsupported shapes and changed
policies. Re-check live exporter key/value converters before and after EACH
helper: a late policy change cannot accept stale output or call a second helper
under outdated assumptions. Retain the live SDK Var rather than caching its
function. No changes to typed projection inputs, schema, WAL or ACK semantics.

Pins SDK `7878a2e`; JSON, driver and ordinary-backend pins remain unchanged.
Includes changelog, strict selection/fallback tests, CI and dependency controls.

Validation:

- All exact-head CI jobs pass at `a1bedfc`:
  https://github.com/chucklehead-dev/jolt-otel-clickhouse/actions/runs/39141486504
- Local focused tests: 5 / 21; wider native attribute/projection tests: 54 / 969.
- Tests poison fallback to prove selection, reject changed/late policies and
  check missing helper, unsupported data, custom operations and map semantics.
- Local same-queue paired Durable test: 5000 rows/batch, 16 observations/arm;
  median insert 615 -> 565ms, managed allocation 55.4 -> 38.7KB/row. Independent
  older reader checks all 175000 physical rows, typed status/values and exact
  event ticks. This is not p99 or hosted-S3 throughput qualification.
- Independent read-only source review found no concrete regression; SDK-side
  admission/geometry/policy contract was reviewed separately.

Stacked on `perf/sorted-json-consumer-20261018` (`20999f9`); depends on the SDK
sorted-normalization topic. No end-to-end stock-compiler claim is inferred from
the isolated SDK stock-Jolt helper gate.

# Review branch

https://github.com/chucklehead-dev/jolt-otel-clickhouse/compare/20999f9c37d01ce1629f9ae2033bd12483030fc7...a1bedfcc50d84dd59597fe12dec144231b282920

No PR submitted. Refresh base/head/checks and participant state before submission.
