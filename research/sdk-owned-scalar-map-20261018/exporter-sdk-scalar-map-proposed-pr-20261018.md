# Proposed title

perf: reduce typed attribute collection and scalar formatting overhead

# Proposed body

Reduce repeated work in native typed-span shaping while retaining the existing
conversion and writer behavior outside the admitted cases:

- Collect bounded ordinary string-key declared attributes directly under the
  original normalizer and stock membership guards. Retain ordered generic
  collection for changed policies, aliases and unsupported maps.
- Use the SDK's optional bounded scalar-map converter while original exporter
  key/value helpers remain installed. Keep the SDK Var live and check exporter
  roots again after it returns. Older SDKs and unsupported inputs retain the
  existing conversion; no attributes are dropped on decline.
- Pin SDK `697f58b`, add a strict native capability CI gate, and preserve actual
  dependency-resolution negatives, including the immediately prior SDK pin.

The collector avoids redundant per-entry normalization/membership calls; the
SDK owns scalar-policy qualification rather than a consumer startup snapshot.
No schema/descriptor rule, typed value/status, JSON writer contract, row budget,
SQL/WAL or confirmed acknowledgement behavior changes. These guards are not an
atomic concurrent-redefinition lock.

Validation:

- [Exact-head CI](https://github.com/chucklehead-dev/jolt-otel-clickhouse/actions/runs/39129795124)
  **SUCCESS**, all3jobs at `5a7617a`: qualified native JSON backends, scoped S3
  SDK/recovery, and aggregate/native/typed recovery gates.
- New native consumer4tests/20assertions, existing attributes21/607 and typed
  projection24/321 pass; CI logs confirm the new gate actually executed.
- Real old-SDK control: overriding only SDK to `37c684a2` makes the strict
  capability gate fail explicitly for the missing API, while ordinary native
  attributes still pass21/607. This is a qualification check, not an ABI-
  incompatibility claim.
- Actual one-worker SDK/Durable ABBA recovers all175,000 physical/typed rows
  through an unchanged accepted exporter/old SDK/stock JSON reader, with zero
  drops or failed SDK exports. All temporary stores removed after verification.
- Completed independent source reviews cover the collector, SDK/consumer
  ownership/fallback, and final pin/CI delta; main checked their findings.

Performance scope: the collector saves approximately112managed bytes/row in
the paired component; the SDK slice saves a further measured~32B/row in its
component. The SDK slice reduces encoding caller CPU96.74->91.63ms/5k, but
full insert-body mean186.70->186.37ms is effectively flat. These are bounded
component/diagnostic results, **not** sustained application throughput or p99
qualification. Keep the broader target open.

Includes changelog. Refs #91 and casselc/otel#50. SDK topic CI is already green;
land its reviewed change before merging the consumer pin.

# Review branch

https://github.com/chucklehead-dev/jolt-otel-clickhouse/compare/e3750302c44cd10273c6e1277dc3912975cac8f5...5a7617a4b3c806f21e15b902993156fc2f751a3c

No PR submitted. This combines the previously shown collector allocation patch
with its SDK scalar-map followup; it supersedes the proposed5741546-only PR
packet for review, not any previously granted submission approval.
