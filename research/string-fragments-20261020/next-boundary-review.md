# Next boundary: source audit, not an approved implementation

Bounded Claude Sonnet read-only review completed after all timed local work.
It examined the exact SDK 14f58e5 checkout, library shape-plan sources and
exporter call boundaries. No edits, tests or publication were delegated.

Useful source observations, independently checked by Codex:

- `scalar_text/plan.ss` allocates a native values vector, converts integer
  values to Strings, then clones the template HAMT through `copy-node` /
  `copy-coll` or the small flat-root copy before returning a fresh plain map.
- SDK `payload_scalar_string_map_cache.ss` walks a newly admitted output map
  again in `weight`, counting normalized key/value characters before storing
  its root in the bounded cache. Shared-root hits avoid most of this; distinct
  roots do not.
- JSON subsequently traverses that normalized map to encode it. A direct
  representation could avoid the HAMT clone, but no runtime measurement yet
  establishes the time saved by that specific removal.

Corrections to the advisory review (do not copy its API proposal verbatim):

1. Boolean text uses literal `"true"` / `"false"`; it is not a fresh String
   allocation for each Boolean. Integers call `jolt-fixnum->string`.
2. A lazy per-entry callback cannot decline after partial emission and then
   silently replay the normal fallback. Admission/fallback and producer-effect
   order need to remain explicit. Generic callback dispatch per field may also
   defeat the purpose; a native consumer must not cross the Clojure bridge for
   every key/value.
3. The old normalized output is a HAMT, not necessarily sorted-key order. A
   replacement must preserve its actual order if promising identical bytes;
   sorted-input iteration alone is not sufficient. Store schema-only ordinal
   emission order alongside existing template geometry.
4. The rejected string cache did not remove HAMT cloning or SDK weight walks.
   Its negative result does not prove that removing those allocations cannot
   help. Conversely allocation counts alone do not prove a time improvement.

Next bounded experiment: an explicit, additive SDK-owned representation bridge
for the existing callback-free admitted scalar lane. Keep the old converter's
fresh plain-map contract unchanged. A private immutable view could contain
schema keys/emission ordinals plus this row's admitted raw scalar values, and
the native encoder could quote/format them without an intermediate HAMT or
numeric Strings. All policy anchors, bounds, metadata/layout checks, admission
before acceptance and fallback behavior need equivalent negative controls.
Views must not put telemetry values in retained schema plans or introduce a
new cross-thread/async ownership promise. First measure normalize/clone/weight
cost on the exact rich fixture; then test the representation in the real
publication path. No new API or performance result is claimed by this note.
