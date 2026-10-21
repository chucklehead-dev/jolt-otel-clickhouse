# Bounded scalar-value reuse (unadopted)

Clean extraction onto exporter main
`f0aa75dd595dff82290fb2c19389d2a6ad02c1e6`. No unrelated fused-writer,
ordinary-map-view or handwritten projection experiments are included. The
JSON byte kernel, traversal implementation, SDK/exporter/defaults and pins
remain unchanged. The component itself still has no SDK/data.json dependency
and runs on official stock Jolt0.8.23 / pinned Chez10.4.1.

## Why

An opaque scalar-text view avoids rebuilding normalized maps but cannot use
the encoder's existing ordinary-map byte cache. For repeated equal context
values in distinct source maps, retain one bounded immutable value snapshot
per learned template. On repeat, materialize a normalized root once, then
return fresh wrappers sharing that private root. The existing byte cache can
skip repeated formatting/escaping. Changed values keep the old view path.

`native-value-reuse-plan-factory` is an EXPLICIT internal policy-owner bridge,
not a new exporter flag or a general map converter. SDK still supplies its
eight ORIGINAL policy anchors and controls learning after canonical admission.
Every call still checks shape/type/value limits and live policy BEFORE reuse.
Never pass this factory to the SDK's plain-map-returning converter.

Extra retention: at most8 first snapshots and16384 conservatively charged
key/value characters per serial planner, separately from existing8schema/
8192key-character bounds. No source wrappers/trees/metadata are retained.
Immutable scalar Strings may be retained for planner lifetime. Keys, numbers
and native nodes/vectors have bounded overhead; this is NOT a hard RSS limit
or a cross-thread redefinition lock. The native codec box/layouts are trusted.

## Evidence and limits

The preceding research branch's single-plan component screen observed
159.76→137.47ms median per5k,9769→9802 estimated B/row. All full bytes matched;
actual reuse stats were[1entry302characters9999matches1materialization]. This
is a16-sample/arm directional result for repeated contexts; max/p99 slightly
worsened185.44→187.24ms. It is NOT confirmed Durable throughput, population
tail, SDK queue, Oscope/Langfuse or S3 qualification. It predates the small
retention-alias fix below; no new-binary rate is claimed.
Raw scalar samples are in `prototype-component.edn`; BOTH arms use the same
stock Jolt executable. Its label `:clojure` means existing scalar views, not JVM
Clojure; `:value-reuse` means the prototype strategy. No JVM/BB comparison is
implied. Old prototype scripts/source remain in the research worktree, not
silently rewritten to represent this alias-fixed implementation.

Source on THIS clean-main extraction:49tests815assertions PASS. New cache
tests6/219. Implementation/factory files are byte-identical to the reviewed
research implementation including the separately reviewed alias fix. Tests
add an actual hcoll-node witness and independent byte oracle; old
experiments/test namespaces are excluded, hence its different total count.

Final neutral-directory standalone also passes49/815. The SAME compiled binary's
alias-only mutant exits1 with813pass2FAIL, and stale-value mutant exits1 with
803pass12FAIL. SHA256 before retirement:
`eb4f2ac399bb580fa6cb753c1bffec85f98d1d8eb28a9900f258cd8e6242f33c`.
Final receipts use the `final-` prefix; preceding49/813 receipts remain historical.
Both normal/negative process handles are terminal; these intentional failures
are successful mutant rejection, not failed startup or a disabled oracle.
Both generated build trees were retired after the gates, preserving logs/hashes;
final retention receipt is `evidence/scalar-value-reuse-main-20261020/retention.Oj0Yho4g.txt`
under the workspace. No database/bulk workload was created in these gates.

Own review caught a missing retention invariant BEFORE adoption/publication:
on first repeat, equal-but-distinct caller Strings could be materialized from
the second input while retaining the first snapshot, holding two copies under
one charge. Byte equivalence cannot catch this. Materialization now uses the
ORIGINAL charged snapshot, sharing the same immutable Strings with the root.
The alias-only mutant preserves bytes but fails2 ownership assertions. A
separate stale-value mutant fails12 semantic assertions. The ordinary checks
still exercise changed values, collisions/reverse order, limits, policy and
fresh-output behavior. String identity is an internal budget witness, not a
public encoding API promise.

Prior research also passed direct SDK owner controls3/67 for ALL eight anchors
both changed live and replaced BEFORE construction. A functional instrumented
encoder control observed0→98 actual wire-fragment hits for100 distinct equal
maps, all37704 bytes identical. Those preceding receipts and the raw timing
experiments remain on `perf/ordinary-scalar-view-20261020`, not duplicated here.

## Next gates

Initial source review and separate alias/accounting follow-up both returned
scoped Claude APPROVE. The initial review MISSED the retention flaw; own review
and the new object-alias oracle caught it. Frozen packet hashes:
`6dd868b5d5c72ff5c61d828502fa64c7f6672e3b1dab2b690494808ba9b40dc9`
and `b9982bab6fb072ec51a9879cd846c7fe0bb8fbf6395a36987cabebb716cbcaaf`.
Source review is not execution or a resource/protocol proof. Raw review texts
are retained alongside this note; client debug logs are private local evidence,
not part of this branch. An initial review timed out with no verdict; do not
count it as approval. The successful retry read the packet then attempted one
nonexistent path (returned no content), so strict single-tool-call compliance
is not claimed.

Next: actual confirmed local Durable A/B writes with independent full-field/type/nanosecond/multiplicity
recovery. Include variable context values to expose cache overhead. Do not
change defaults or claim targets from this component screen. Hosted networking
is required for target S3 throughput; local DSL upload is capped at10Mbps.
No new protocol/concurrency model or unchanged exhaustive run is needed for
this representation-only slice; its resource-alias invariant has explicit
mechanism and negative controls, not a WAL-model proof claim.
