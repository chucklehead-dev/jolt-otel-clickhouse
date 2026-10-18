## What and why

The native typed-attribute collector currently invokes the key normalizer and
membership function for every input attribute, including undeclared keys.
For ordinary string-key maps those calls are redundant under the original
policies. Use the defining namespace's original normalizer identity and live
stock membership/string-predicate guards to collect that case directly.

Keep the existing ordered collector for changed policies, non-string aliases,
unsupported maps and larger wanted sets. Bind stable Var references once in
the native factory, retaining live reads while avoiding a five-argument
variadic bridge on every map.

## Validation

- Native projector suite: **24 tests / 321 assertions**. Covers output shape
  and order, string-hash collisions, duplicate normalized keys, preinstalled and
  later root replacements, exact eight-key acceptance/nine-key decline,
  non-string wanted keys, and post-scan policy change before fallback.
- Poisoned generic fallback proves real fast-path selection; changed policy
  and unsupported input still reach that fallback.
- Full maintained stock-Jolt 0.8.22 suite passed on first head `e1723f4`.
  [Final-head CI](https://github.com/chucklehead-dev/jolt-otel-clickhouse/actions/runs/39123527387)
  is green at `5741546`: aggregate tests, qualified native JSON backends, and
  scoped S3 SDK qualification.
- Five local SDK/Durable writer-to-independent-reader runs recovered all
  **875,000 physical/typed rows**, with zero SDK drops/failures. The reader
  always used the unchanged accepted exporter and stock JSON reader.
- Independent read-only reviews covered guard/fallback semantics and the
  constant-binding followup; main checked the source and runtime controls.

## Performance scope

Same-process 5k-row/wide16 component screen on the existing qualified runtime:
typed projection p50 **14.05 -> 12.20 ms**, fresh projection plus encoding
**87.86 -> 78.45 ms**, approximately **112 fewer managed bytes per row**.
Exact typed vectors and UTF-8 bytes match before/after measurement.

This is an allocation optimization, **not a demonstrated end-to-end throughput
win**. Earlier separate-process SDK runs put the first candidate below both
controls. The final same-SDK/connection diagnostic was effectively flat in
full insert-body mean (**174.55 -> 174.08 ms**), and encoding-phase CPU did
not clearly improve. Short correlated samples do not qualify a p99/SLO or
the larger throughput target. Keep that work open.

No JSON writer/output policy, descriptor fencing, typed value/status rule,
row byte limit, SQL/WAL, or confirmed Durable acknowledgement changes. No new
model transition or unchanged exhaustive model run.

Refs #91.
