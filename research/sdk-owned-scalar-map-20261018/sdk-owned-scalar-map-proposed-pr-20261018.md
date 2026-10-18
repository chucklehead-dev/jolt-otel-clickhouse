# Proposed title

perf(any-value): add optional SDK-owned scalar map conversion

# Proposed body

Add `otel.any-value/try-scalar-string-map` for exporters that need string-valued
attribute maps. It avoids repeating the scalar policy/converter chain for every
entry in a bounded builtin map. Policy checks belong to the SDK definition, so
a helper replaced before consumer startup is not mistaken for the default.

This is optional: unsupported runtimes, map shapes, values and changed policies
return `nil`. Callers must retain their ordinary scalar conversion. Original
values, limits, map order and input ownership remain unchanged; this does not
replace attribute normalization or infer types. Private implementation/core
mutation and atomic cross-thread redefinition are not claimed.

The native implementation uses existing stock Jolt primitives, with map-layout,
helper-arity and fixnum-width fences. It reuses Jolt's fast integer formatter;
it requires no compiler fork. The optional helper also declines safely on
Babashka/JVM. That isolated fallback qualification is not full SDK portability.

Validation:

- [Exact-head CI](https://github.com/casselc/otel/actions/runs/39128818354) passes
  at `697f58b`: existing aggregate job plus a separately checksum-pinned stock
  Jolt0.8.23 capability job. The latter runs native5tests/66assertions and
  ordinary AnyValue17/388, with a positive capability requirement.
- Local qualified-runtime SDK suite470tests/4086assertions passes before the
  CI-only followup. Stock0.8.22 also passes native5/66; isolated BB/JVM fallback
  gates pass7assertions each.
- Controls cover preinstalled/later helper changes, limits/bounds, collisions,
  unsupported inputs, ordering/metadata, immutable input, native integer extremes,
  multibyte boundaries and malformed-loader decline.
- Two completed independent read-only reviews; main checked the findings and
  incorporated the useful hardening. No end-to-end throughput gain is claimed:
  export-path encoding CPU improves in the bounded diagnostic, total insert
  time is effectively flat.

Includes changelog and user-facing helper guidance. Refs #50; broader codec
portability remains separately tracked in #63.

# Review branch

https://github.com/casselc/otel/compare/ecce82c427be527b8778cf8dd26a86ff9e22d9e6...697f58bd5b4925f72339694bedbace97bdc869bb

No PR has been submitted. This text and branch are provided for review first.
