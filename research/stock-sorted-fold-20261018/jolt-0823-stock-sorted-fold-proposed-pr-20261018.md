# Add internal sequence-order folding for stock sorted maps

## Summary

Add `stock-sorted-map?` and `sorted-map-fold-seq-order` as internal native
runtime helpers. A native encoder or visitor otherwise creates map-entry and
sequence-chunk objects just to read each key/value pair. The helper keeps that
representation-specific traversal in the runtime, instead of duplicating tree
layout in consumer libraries.

The immediate consumer is an [experimental native JSON writer](https://github.com/casselc/data.json/blob/25d30c4a86c6ee04aaeceb0f4cc764f85a36acc8/src/main/clojure/clojure/data/json/jolt_byte_writer.ss).
It uses the predicate to retain custom operations and the fold to avoid building
entries that would immediately be destructured again.

The fold visits keys and values in the order of that same map's stock sequence,
including maps built with a custom comparator, without invoking the comparator.
It snapshots the immutable root before callbacks; exceptions stop at the same
visited prefix and nested folds have independent cursors. Accumulators are
ordinary values, not a Reduced/early-termination protocol.

The predicate rejects custom per-object operation tables without trial calls.
Those maps must retain their own sequence behavior. Defining-core private
roots and immutable runtime internals remain trusted, as in the ordinary walk.
Rejection diagnostics do not retain or render arbitrary input values.

No portable Clojure API or ordinary collection traversal is changed. Includes
an Unreleased changelog entry and regression registration in `sortedcoll`.
No compiler/stdlib source changed, so no seed re-mint is required.

## Validation

- Focused native gate: 79/79 checks on current release v0.8.23 source boot,
  Chez10.4.1. Covers actual constructor maps, empty/chunk-boundary/large maps,
  reverse and armed comparators, unsupported/custom operations, callback order,
  exception identity/prefix, reentry and accumulator identity.
- Mechanism red/green: an independently checked constructor trap sees 65 entry
  allocations for ordinary seq and zero for the fold over the same map.
- Existing sorted collection suite: 58/58. The focused gate was rerun after the
  bounded-rejection follow-up. No timing threshold substitutes for semantics.
- An experimental native JSON consumer passed its own callback/fallback tests
  using this exact helper source on its qualified runtime. This is source-overlay
  evidence, not a newly built compiler or broad downstream qualification.
- Exact-head fork flake CI39145914892 passes at646fcfcb; tests39145915016
  remains in progress. Full contributor benchmark suite
  and standalone/state-image qualification remain before upstream submission.
  Earlier fc6d0678 flake CI passed; do not promote that to final-head CI.

## Review branch

Base: latest stable v0.8.23, `a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5`.

https://github.com/casselc/jolt/compare/a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5...646fcfcbb1e8bcda33f0a4b9e40d32db0ffcd222

Fork review branch only. No upstream issue, PR or comment submitted. Refresh
head/base/checks/participants and finish remaining gates before submission.
