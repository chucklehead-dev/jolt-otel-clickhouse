Source follow-up against stock v0.8.23 base `a0ac71c3` (read-only audit at
`18dce94a`, whose independent sorted-fold extraction does not change mapv).

The unary body in `jolt-core/clojure/core/00-kernel.clj:66` is still the
transient fold. For a singleton builtin vector it creates a reducing closure,
an 8-slot transient buffer/record, a vector-seq wrapper and the trimmed result.
The host already has direct leaf traversal: `reduce-seq` routes vector chunked
sequences into `vec-reduce`. This is fixed per-call setup, not one lazy cell
per element or an O(n²) traversal regression.

A consumer experiment replacing three singleton event-column mapv passes with
fresh literal vectors saves about 848 estimated bytes per telemetry row in
full encoding. Its four-process component ABBA only improves p50 about 2%
and worsens the observed tail; real SDK/Durable comparison is still running.
That is not a core performance result or justification for claiming service
throughput. It does motivate checking a normal core path rather than spreading
small-vector special cases through consumers.

GLM suggested a direct-pvec arm in `jolt-reduce` after the custom interface
check. Main source inspection confirms it would remove only the seq wrapper;
it would NOT eliminate the transient buffer, reducing closure or result trim.
Do not treat that as a complete solution to the fixed mapv setup cost. Before
any patch, gate exact reduced-initial-accumulator and empty behavior, custom
IReduceInit/IFn paths, callback order/exceptions, metadata, subvec/map-entry
shapes and the already-specialized arithmetic reductions. A source audit is
not proof that these contracts are covered or that a compiler will inline it.

No runtime source/seed was modified, no binary was built or selected, and no
upstream issue/PR was opened. Existing #176 empty-input work remains intact.
Raw audit and the consumer experiment will be retained in the owned research
branch; an upstream proposal still needs user review and normal producer and
consumer qualification.
