# Policy-anchor prototype: no demonstrated speedup

Candidate SDK `35b385e8127ef57dd39e54633a91172da369a92b`, based on published
`3366a3322c3ea4024057f6278e50c999864171e8`. It copies immutable Var/expected-value
pairs into a native vector once, rather than repeatedly indexing Jolt vectors.
Both live-root checks per map remain; this is not a startup-only policy snapshot.
The existing payload cache already uses this pattern. No supported value, map
ordering, metadata, native layout, API, dependency or persistence contract changes.

## Functional controls

Stock Jolt 0.8.23 / Chez 10.4.1 explicit native gates: **13 tests / 160 assertions**,
zero failures/errors, including original plain/sorted helper tests. The new
two-test gate uses lexical resolver instrumentation, not global replacement.
It proves immutable-vector reads occur during construction, not map admission,
while live changed/restored roots still decline/accept. Construction under a
changed root must not freeze it. On actual baseline 3366, those same two tests
have **five mechanism failures, zero errors**, while semantic root-change/result
assertions pass. No broad SDK/application qualification is inferred from this gate.

## Same-shape component pair

Two fresh processes, baseline first then candidate. Each uses the updated
`screen.clj OUTPUT EXPECTED_SDK_SOURCE_MARKER` with the same 12 samples/cell,
counterbalanced cell order, actual projection and published codec. Only the SDK
resource implementation changes. Sources match before/after; selected SDK and
both resource URIs are checked. No local build or Claude review ran concurrently.

| Shape / operation | Baseline p50 | Candidate p50 | Estimated B/row baseline / candidate |
|---|---:|---:|---:|
| Shared projection | 53.24ms | 72.63ms | 5,603 / 5,603 |
| Shared prepared encoding | 39.80ms | 43.95ms | 3,467 / 3,467 |
| Shared combined | 99.46ms | 103.48ms | 9,027 / 9,027 |
| Unshared projection | 124.38ms | 138.12ms | 9,262 / 9,263 |
| Unshared prepared encoding | 52.47ms | 57.47ms | 3,467 / 3,467 |
| Unshared combined | 188.67ms | 213.86ms | 12,685 / 12,685 |

**No speedup or allocation reduction is established.** The unchanged prepared
encoding control is also about 10% slower, so neither an isolated code regression
nor an environmental cause is established from this pair. Do not retry unchanged
until favorable numbers appear or promote the mechanism count as a pipeline win.
No production path, application pin or default is updated to this prototype.

Every timed output and both arms' complete output digests match the original
8,647,780-byte reference `d954be4c868606f29395483bb22b10878284a3d32a41d801a93519722d015524`.
All 144 observations and full p50/p90/p95/p99/CPU/GC data are retained in
`policy-anchor-pair/`. Tail percentiles remain sample maxima, not qualifications.
This has no native database/Durable I/O and does not advance the 25k/20k claim.

The first diagnostic result in `result.edn` remains unchanged, produced by the
earlier script at exporter research commit `0aef6a1`. The subsequent script adds
an explicit SDK-source marker and records resource URIs; both comparison arms
use that same updated script. The exact historical script is preserved at
research commit `5af0db3`. The current script/path also supports the subsequent
fixed-schema experiment; it is not the historical source identity. Reproduce
the historical comparison from that commit, or use the current
`run-anchor-pair.sh NEW_ABSOLUTE_DIR` for a separately identified new run.

## Next higher-leverage experiment

Target map construction rather than another metadata-loop tweak. A fixed-key
shape plan could build the same plain-map layout from validated sorted entries
without rebuilding/re-hashing through a transient map every time. Compile only
keys/ordinal indexes, never telemetry values; preserve exact output kind/order,
fresh metadata-free wrappers, source immutability and scalar policy. Validate
every new key/value/count/geometry; unknown shapes must use the original path.
Start as a schema-fixed experiment, not a default/cache change or SDK policy
permission. Before production, retain SDK-owned live policy guards, root-cache
hit behavior and all existing retention/lifetime bounds. Keep shared input as a
negative/regression control. No concurrency/model change is implied by this lead.
