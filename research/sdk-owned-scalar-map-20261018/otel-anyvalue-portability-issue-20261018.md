## Scope

Standalone AnyValue portability followup, **not** a blocker for the current
Jolt performance work or a claim that the full SDK supports JVM/Babashka.
Verified owned main `ecce82c427be527b8778cf8dd26a86ff9e22d9e6` has the same
tree as the measured SDK topic `37c684a2`.

## Reproduction and findings

From that SDK source, `bb -cp src:test -m otel.test-runner any-value` fails at
analysis: `Unable to resolve classname: :default`. Jolt's `catch :default`
spelling occurs in `otel.any-value/canonicalize`, `otel.otlp.any-value/decode`
and one test catch. JVM Clojure1.11.4 likewise rejects that catch spelling.

A temporary diagnostic replacing those three catches with `Throwable` gets
past analysis, exposing further existing cross-host assumptions:

- Boundary fixtures use `inc`/`dec` on Long min/max; BB/JVM throw before calling
  the code under test. Use explicit BigInt boundary values or checked host-
  appropriate construction rather than assuming automatic promotion.
- OTLP decimal Int64 parsing accumulates a positive magnitude with
  `(+ (* result 10) digit)` before checking its range. Checked Long arithmetic
  overflows for valid minimum Int64's magnitude and out-of-range positive input,
  instead of returning the intended decoded value/bounded invalid-int64 error.
- The NaN roundtrip assertion assumes `(not= value value)`; test with an explicit
  NaN predicate rather than depending on host number-identity/equality behavior.

Those temporary codec/test edits were not adopted into the performance topic.
Its isolated optional-helper BB/JVM gates pass7assertions each, and the full
Jolt SDK suite passes470tests/4086assertions. The AnyValue namespace alone now
uses portable Throwable catching locally; full codec portability is not claimed.

## Correctness/test boundary

The missing coverage is a host-arithmetic assumption, not merely different JSON
libraries: unbounded mathematical integer accumulation in the Jolt host masks
overflow that occurs before the explicit protocol range check on JVM/BB.
Single-host roundtrip tests are not a cross-runtime conformance proof. Add small
independent BB/JVM oracle fixtures covering Int64 min/max, adjacent invalid
decimal strings, malformed wire values, NaN and nonthrowing error classification.
No new protocol state model or unchanged exhaustive model run is needed here.

Keep this bounded followup separate from scalar-map performance integration.
Local diagnostics: `sdk-owned-scalar-map-{bb,jvm}-qualified-20261018.log` under
the workspace evidence directory record the temporary-catch experiment; do not
interpret them as tests of an unchanged committed source tree.
