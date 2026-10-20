# Independently usable stock-Jolt traversal

This slice extracts the existing traversal implementation into
`jolt.traversal.experimental`. It is one library distribution under
`incubator/codec`, with independent namespaces, not several new repositories.
The traversal namespace does not load JSON, OTel, the exporter or the driver.
The codec captures native fold and sorted-map predicate procedures once.
No core/compiler patch, global helper installation or per-element Clojure
adapter is introduced. The byte/JSON kernel is unchanged.

Parent checkpoint: `e81060660a747811cfa1caca34bb7b61366bb4a1`. The prior
SDK payload-cache, private nested-text and prepared-WAL work is retained. No
default application/dependency pin, WAL protocol or persistence order changes.

## Contract and tests

These are synchronous full folds, explicitly not `reduce`/`reduce-kv`: visitor
accumulator is last and Reduced does not stop traversal. Ordinary maps retain
seq order, including collision buckets. Sorted maps use tree order without
calling comparators and reject a changed core operation table. Persistent
vectors use index order. Callback exceptions stop future callbacks; sequential
record sources request no next record after an exception, subject to their own
realization semantics. Map/vector kernels construct no entries/seq cursors;
this does not claim callbacks, vector indexing or arbitrary inputs allocate
nothing. Native layout access and provider purity remain trusted boundaries.

Official Jolt 0.8.23 through Chez 10.4.1 wrapper:

- Traversal-only process: **5 tests / 42 assertions**, JSON namespace absent
  before and after. Ordering, nil/false, collision keys, vector boundaries,
  comparator avoidance, changed operations, exceptions, reentry, native bridge
  and no global helper controls pass.
- Combined current exporter/codec/cache/traversal gate: **25 / 161**, green.
- SDK cache using this extracted native folder: **19 / 162**, green.
- A separate consumer in `/tmp`, with only the incubator directory as a
  `:local/root` dependency, loads traversal and folds successfully without
  loading codec/exporter. No exporter project dependencies are selected.

The first codec gate failed on a missing native sorted-map predicate during
the split. Passing that predicate alongside the folds fixed the dependency;
the original codec suite and new combined gate now pass. No gate was weakened.

## Same-process component comparison

The preserved script reuses the earlier full rich 5k-row fixture, projection,
timers, full-output hashes and changed-event controls. Old fused and separated
kernels alternate in one process (16 samples each), both using private nested
text bytes. Historical mode labels `:string` and `:bytes` mean **fused** and
**separated** in this screen, explicitly recorded in `:mode-labels`; they do
not mean different JSON representations. The old namespace is loaded under a
distinct research name; the JSON kernel source hashes are equal in both arms.

| Per 5,000 rows | Fused | Separated |
| --- | ---: | ---: |
| Median | 180.25ms | 181.90ms |
| p90 | 235.60ms | 213.05ms |
| p95/p99 (maximum of 16) | 259.44ms | 294.75ms |
| Median estimated allocation/row | 13,151B | 13,311B |

All output hashes match and changed inputs are detected. The roughly 0.9%
median-time and 1.2% estimated-allocation differences are not a speedup claim;
this is a library-reuse boundary with visible measured cost, not an invisible
or guaranteed zero-overhead abstraction. Raw CPU/GC samples are retained.
No actual Durable/S3/application throughput was requalified in this slice,
and no population-tail or AOT/standalone-packaging claim is made.

## Next performance work

Use this stock-Jolt library boundary to implement bounded reuse of already
encoded normalized attribute maps, then consider fusing row projection into
encoding. Preserve wire/order/depth/size/ownership and policy controls, keep
unshared negative cases, and compare the cumulative stack. The actual Durable
~25k p50/~20k p99 goal remains unmet. No model logic changed and no unchanged
exhaustive checks were rerun here.
