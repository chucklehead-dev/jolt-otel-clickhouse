# Proposed title

perf(any-value): normalize bounded stock sorted scalar maps

# Proposed body

Add optional `otel.any-value/try-sorted-scalar-string-map` for consumers that
need a plain string-valued attribute map. SDK-normalized attributes commonly
arrive in sorted maps; visiting the ordinary scalar policy chain for every
entry repeats work whose policy the SDK already owns. This helper checks that
policy once around bounded admission and performs the conversion natively.

Unlike the existing map-kind-preserving helper, this explicitly produces a
fresh plain map matching ordinary `into {}` conversion. Unsupported runtimes,
values, shapes, custom operation tables and changed scalar policies return
`nil`; callers must retain normal conversion. No input mutation or value cache.
Core/private runtime roots remain trusted; this is not an atomic lock against
concurrent Var redefinition.

The loader checks runtime layouts, helper availability, fixnum range and an
independent order-sensitive node-geometry probe. No compiler patch is needed
for this optional helper. Includes changelog, usage guidance and strict tests.

Validation:

- Exact-head CI passes at `7878a2e`, including strict helper tests on
  checksum-pinned stock Jolt 0.8.23 and the ordinary aggregate job:
  https://github.com/casselc/otel/actions/runs/39141384747
- Local strict suite: 6 tests / 78 assertions. Full SDK: 471 / 4089.
- Controls cover custom operations/comparators (zero trial calls), sizes and
  integer/string boundaries, malformed layout, metadata/order, changed policy
  before loading and after loading, and reversed-traversal loader mutation.
- Isolated Babashka/JVM decline checks pass; not full SDK portability evidence.
- Completed independent source review and follow-up, adjudicated by maintainer.

Stacked on `perf/sdk-owned-scalar-map-20261018` (`697f58b`).

# Review branch

https://github.com/casselc/otel/compare/697f58bd5b4925f72339694bedbace97bdc869bb...7878a2eb4340519d6843d13490a5a7c99ebade8a

No PR submitted. Refresh base/head/checks and participant state before submission.
