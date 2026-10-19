## Measured allocation amplifier

On v0.8.23 (`a0ac71c3`), comparing an ordinary function with a sorted map/set
falls through `sorted-fast=` to `sorted->plain`, constructs the complete hash
collection, and redispatches equality just to conclude false.

The existing sorted-collection allocation harness extended to this pairing
measures **8.3 MB at 10k entries → 38.2 MB at 40k** per map comparison. Both
operand orders and sets show the same growing allocation. This matters to
ordinary library code using a function as an omission marker: a nested sorted
map triggers roughly **6.7 KB/record** in the measured JSON fixture.

The isolated no-GC probe preserved output and attributed that allocation to
omission equality, not key conversion (zero measured bytes there). Merely
counting encoder loops/temporary writers did not reveal the normalization.

## Draft runtime fix, deliberately NOT promoted

[Latest-release review diff](https://github.com/casselc/jolt/compare/a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5...388a3ad613f35a519aeefb0b895ff82b17088616)
rejects a procedure opponent inside the selected sorted equality arm before
conversion. Three files, no compiler/seed/aspect migration. Its 68 sorted gates
pass; the same new tests against the older body fail six checks. Allocation is
constant at 208–496 B/comparison for 10k and 40k entries. A selected extension
on the original operands still wins before the sorted arm, as tested.

However, Claude identified a real normalized-operand compatibility edge and
main reproduced it through the public legacy registration API:

```clojure
(def marker (fn [x] x))
(__register-eq!
  (fn [a b]
    (and (identical? a marker) (map? b) (not (sorted? b))))
  (fn [_ _] true))
[(= marker {"a" 1}) (= marker (sorted-map "a" 1))]
```

Current release: `[true true]`; draft fast reject: `[true false]`.
The extension only recognizes the plain representation reached by the old
second dispatch. This is a supported public seam, not a forged tree, and must
not be silently dismissed as a timing-only change. The initial direct gate
and selected-handler test did not cover it. Source-overlay consumer screening
also preserves ordinary output and reduces allocation, but does not solve
this semantic decision or qualify a built runtime.

No upstream PR/comment, default install change, or canonical `integration/aspects`
edit has been made. The runtime branch is for review/CI only. A cumulative
local port exists but has not been built, selected or pinned.

## Bounded library alternative already qualified

[casselc/data.json #20](https://github.com/casselc/data.json/pull/20) changes only
the **known default** omission marker to identity. Custom value-function
equality stays unchanged; README/changelog disclose the narrowed default rule.
It passed native/portable/JVM CI, source review and four actual SDK/local
Durable runs with independent recovery of 700,000 rows. Matched local
confirmed-window throughput 8.43k → 10.30k rows/s; no S3/tail guarantee.
This producer-owned change is being integrated without adopting the broader
runtime behavior change.

## Next upstream extraction decision

- Preserve legacy normalized callbacks by declining a runtime fast path unless
  an explicit coherent domain/ownership proof excludes that second dispatch;
  do not infer callback purity or future behavior from sample predicates.
- Alternatively make an explicit, reviewed Clojure-conformance/compatibility
  decision about normalized-only equality extensions, with the above control.
- Keep the eventual PR focused on generic collection equality and follow the
  latest release, contributor benchmark and changelog requirements. No upstream
  submission without user review.

## Modeling/testing gap

Our protocol models treat library equality as an atomic trusted operation;
they do not establish its cost or its internal representation conversions.
Same-value tests were therefore non-discriminating for this inefficiency.
The new allocation-scaling oracle has a failing old-body control. Conversely,
that oracle and green functional suites alone did not prove extension
compatibility: the normalized-operand control was required. No Durable
transition changed, so no unchanged exhaustive protocol run was launched.
