# Direct scalar normalization into byte output

Research only, base merged exporter `a6cbf9390d1521026e5da5d2ea3cfc0b8916ae2b`
(PR157). No product/SDK/compiler/data.json/default/pin/model changes here.
Official stock Jolt0.8.23, mandatory Chez10.4.1 wrapper; no native database,
build, queue, S3 or bulk disk artifacts. Only small source/reports/logs.

The earlier ordinary-map template/slot view at `d105da6` reduced allocation but
did not improve median time. This is a different algorithm:normalization writes
directly into encoder output, without template matching, replacement HAMTs,
formatted numeric Strings, raw-slot vectors or an opaque borrowed-map view.
The existing traversal backend still performs map visits; its implementation
is not copied or replaced. Input maps/Strings must remain immutable.

## Two coordinated steps, not selected favorable reruns

`prototype.clj` exact-match patches the actual SDK6cd57c86 ordinary helper,
SHA256 `179f1b12c596f09f26178c39a913e335c14b341819b7280fe99956be048e7ca8`.
The control uses that helper unchanged. The candidate keeps its exact admission
and owner-anchor checks but returns the admitted raw map instead of copying it.
A separately compiled specialization of the SAME JSON kernel writes scalar
values as JSON strings. Its plain encoder is selected, not a fragment-cache mode.
Every raw map is consumed synchronously; only fresh output bytes leave encoding.
This specialized writer is NOT a general JSON API:every map is a flat normalized
attribute dictionary, not an arbitrary nested map.

First declared copy/sink/sink/copy screen,16observations/arm, distinct5k17-field
maps, complete2,124,565bytes checked each time:

| Arm | p50 /p90 /p95 /p99 ms | Median estimated B/map |
| --- | --- | ---: |
| Original SDK copy + unchanged writer |23.40 /34.30 /34.71 /34.71|1800.72|
| Same admission + direct scalar sink |22.17 /38.63 /54.11 /54.11|858.17|

Allocation~52.34% lower; median~5.29% lower but sample maximum WORSE. The slowest
sink sample has54.11ms wall/~54.17ms observing-thread CPU and no collection;
no scheduler/GC explanation is asserted. Do not hide this report or compare
absolute times with another process as causal evidence.

The follow-up `inline.clj` builds ON that candidate, removes its preliminary
value-admission walk, and validates String keys, scalar kinds and a FIXED100-
character value bound while emitting. Rejecting discards the entire private
output; nothing partially encoded is returned. This is a STRICT component
encoder, not SDK fallback/error equivalence:there is no transactional per-map
rollback or generic owner fallback. It also accepts String hash-collision
buckets which the original helper declines; that difference has an explicit
test. Unsupported error shapes differ. Do not install it through an existing
map-returning SDK converter or treat it as an authorized production adapter.

Declared copy/prevalidated/inline/inline/prevalidated/copy,16samples/arm:

| Arm | p50 /p90 /p95 /p99 ms | Median estimated B/map |
| --- | --- | ---: |
| Original SDK copy |17.71 /20.97 /21.34 /21.34|1800.72|
| Prevalidated direct sink |16.55 /18.20 /21.83 /21.83|858.17|
| Inline validating sink |15.86 /17.92 /19.31 /19.31|858.17|

Within this second process, inline median~10.44% lower than copy,~4.15% lower
than the prevalidated sink. Allocation is unchanged between sink variants.
Each complete output equals the original; changed values change every arm.
All raw observations in both reports remain. At n=16,p95/p99 are sample maxima,
not population tails or an end-to-end performance guarantee. Quantiles use the
nearest-rank convention in the saved scripts.

## Resource accounting and limits

Two output-sized buffers alone cost about849.83B/map
(`2*2124565/5000`), close to the measured sink858.17B/map. Source inspection
confirms chunk storage plus a fresh final copy. This is a useful allocation
floor comparison, NOT zero allocation, an object-site/RSS profile, exact native
memory accounting or a proof of minimality. Estimates combine separate live-
heap/reclaimed-byte snapshots. CPU counters observe this thread, not all workers.

The modest component time savings despite removing most non-output allocation
show why this alone cannot close the full confirmed Durable gap. Rich spans,
other normalization kinds, row construction, WAL/publication, live queues and
SDK cache/policy-owner integration are EXCLUDED. The component uses one original
test-policy anchor, not the full SDK eight-anchor owner contract. Native fixnums
are a subset ofInt64; full-width bignums remain outside admission. The inline
prototype's100-character policy is fixed by this screen, not a configurable API.

Focused checks:prevalidated4tests/70assertions; inline4/46, all pass. Includes
String/Boolean/integer/extreme-native-fixnum values, Unicode/escaping,9/128-entry
bounds, unsupported values/shapes, live policy decline, exact/one-under byte
limits, depth limits, independent output mutation, no later lazy-record request
after failure, and an unquoted-numeric mutant that breaks full-byte equivalence.
Source hashes pass after execution. Inline attempt1 failed its exact-match
source-patch guard BEFORE timing (needle also matched another visitor); the
needle was narrowed to the whole map-entry declaration. Failure log/hash manifest
are preserved, not described as a successful screen or overwritten.

## Reproduce and next decision

From exporter root, prefix the selected official stock binary with
`/home/chuck/ai-src/tools/jolt-with-chez-10.4.1`. Use
`-Srepro -Sdeps '{:paths ["incubator/codec/src"]}'`, followed by:

```text
research/direct-scalar-normalization-sink-20261021/checks.clj SDK-SOURCE
research/direct-scalar-normalization-sink-20261021/checks-inline.clj SDK-SOURCE
research/direct-scalar-normalization-sink-20261021/screen.clj RESULT SDK-SOURCE
research/direct-scalar-normalization-sink-20261021/screen-inline.clj RESULT SDK-SOURCE
```

SDK-SOURCE is the exact immutable6cd57c86 resource named in the manifests.
The timed screens run from the exporter checkout, so their RESOLVED dependency
graph still includes the parent project's ecosystem forks; not loading those
namespaces does not prove a fork-free graph. A separate `isolated-smoke.clj` was
then launched from neutral `/tmp` with only the absolute codec source path and
empty dependencies. Its `-Stree` output is empty; it checks byte equality at
9/17/128fields and asserts no SDK/JSON/exporter/driver namespace is loaded.
The SDK helper is read as reference source data, not resolved as a dependency.
That smoke qualifies dependency isolation, NOT the timing, a packaged product or
a complete fork-free exporter. No nREPL server was available; isolated stock
processes kept source/selection provenance explicit. Do not rerun unchanged
screens to seek favorable numbers.

Keep this research unadopted pending independent review and an actual owner-
integration design. A real fused adapter needs safe per-map rollback/fallback,
original policy and callback order, current limits, no escaped input loans and
separate rich-span/native recovery qualification. The existing #141 bounded
preparation/publication proposal remains a distinct larger opportunity, still
subject to its explicit RevisionB model-scope approval. No concurrent lifecycle
or persistence-acknowledgement change is implied by this serial experiment.

## Review adjudication

Claude's bounded read-only review checked the prototype/report and found no
hidden success claims. Its pre-reuse blocker is the explicitly widened collision
admission. Source audit explains the original guard:the SDK helper's
`copy-node` handles only `hnode` and pair children, then calls `cdr` on a leaf;
`admitted?` rejects `hcoll` records before that unsafe branch. By contrast the
shared traversal kernel explicitly handles `hcoll` and reverses its physical
alist to preserve source seq order. Thus this is a structural eligibility bound,
not evidence that String hash collisions are semantically forbidden attributes.
It still does NOT establish equivalence to the real SDK's generic fallback,
normalization order or owner policy; the explicit non-adoption gate remains.

The review's empty-input control asymmetry was fixed by adding the original
copy arm's zero-limit check; both now pass. Codex also executed the separate
dependency-isolation smoke after review. No timed source or results were
changed/rerun for those control/prose additions. The unedited advisory review
remains; it neither executed tests nor reviewed the subsequently added neutral
smoke file. No production PR is opened for this exploratory component.
