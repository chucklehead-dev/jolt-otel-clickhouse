# Public synchronous exporter: stock source and release application

This research app uses only public exporter/driver APIs. It does not import
benchmark namespaces, probe private Vars, redefine functions, or evaluate
projection forms. Production exporter, codec, SDK and driver code is unchanged.
Base is exporter merge `f31107c7b984fcf7c8f966de0bdfe2cbb4dfbfb3`.

It directly calls the SDK export protocol synchronously, not the SDK batching
queue or live tracer. It installs a real approved typed manifest, checkpoints
the schema, then confirms 3 warmups +32 measured batches of 5,000 rich spans.
Resource/scope/event attribute roots are equal but distinct sorted maps.
Each fresh store contains 175,000 rows. The independent older-stack reader
compares all physical fields, exact nanoseconds, typed values/status and
multiplicity; it does not consume the writer's expected-row report.

## Consumer resolution matters

The initial two-dependency consumer resolved driver `3314b79` and SDK `14f58e5`,
but selected **crypto `649c993` and data.json `3adc8c5`**, not the qualified
exporter root's crypto `5effcc8` and data.json `0f51b99`. This is a consumer
composition/provenance gap, not a demonstrated resolver defect or a falsified
Durable invariant. No claim about relative speed of those revisions follows.

`initial-app-deps.edn` preserves the initial coordinates, with explanatory
comments added. `initial-public-pipeline.clj` matches the initial main's saved
SHA256 `204e0c7d520a67a6114e0b5a31ea297d3f5fc494298986ed669240ad9f5e9b80`.
The current main additionally rejects a pre-existing object store before native
work. Initial source/release/hidden-source runs all recovered 175,000 rows, but
their timing is **not a canonical-stack baseline**. Those results remain
separately labeled rather than overwritten.

The current app explicitly pins the intended consumer stack. The real
`-Spath` positive gate passes; an actual `:replace-deps` consumer retaining only
the original two dependencies reproduces the old selection and is rejected.
The gate checks canonical selected source roots, not just requested deps.edn
coordinates. Exporter/codec local sources match the clean base above; the codec
implementation matches the published `15b15f3` component. This is not a fully
published-Git application dependency qualification or an upstream-only ecosystem.
The printed `:all-active-classpath-under-home` field is informational; the
accept/reject gate checks the four named dependency families. The actual hidden-
source run supplies the separate execution proof, not that metadata flag.

## Canonical consumer measurements

Official Jolt0.8.23 (SHA256
`7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94`),
Chez10.4.1, libchdb26.9.0 (SHA256
`a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557`).
Stock source and default release build, same app/fixture/dependency pins:

| Mode | Confirmed window rows/s | p50 /p90 /p95 /p99 ms per5k | Estimated B/row |
|---|---:|---|---:|
| Release |13,960|348.19 /390.08 /395.19 /419.22|17,118|
| Source |11,265|424.57 /546.02 /573.29 /632.46|17,246|

Each row has a completed writer and independently verified 175,000-row recovery.
This is one unpaired sequential run per mode, **not a causal AOT speedup claim**.
The 32-sample p99 is the observed maximum, not a population tail qualification.
Progress printing is outside each callback but inside the whole-window timer.
Allocation estimates use separate live-heap and GC-byte snapshots, exclude
native chDB allocation and are not allocation-site profiles. No new pipeline
optimization, live capture, queue, Oscope UI, Langfuse or S3 qualification is
claimed. The25k inverse-p50 /20k inverse-p99 objective remains unmet.

The initial binary also ran with /home and the original /tmp source/cache/build
paths hidden by a private filesystem namespace. Only the executable, libchdb
and writable synthetic store were reintroduced; ordinary system libraries
remained available. Independent recovery verified that store outside the
namespace. The rebuilt canonical consumer also passed the same hidden-source
execution and independent recovery of 175,000 rows. Its timing is not pooled
with the plain source/release runs because filesystem isolation changes the
execution environment. Canonical coverage is 525,000 independently recovered
rows across three stores, separate from the initial graph's 525,000.
This is a standalone-execution proof, not a fully self-contained distribution:
libchdb and declared crypto/ssl/sqlite/z (optional pq) runtime libraries remain
host dependencies. The mode string in a writer receipt alone proves no build
provenance; use the commands, source and executable hashes.

## Reproduce the current pinned consumer

Run from `app/`, through the workspace's Chez10.4.1 wrapper. Set `JOLT_NO_USER_DEPS=1`
and `JOLT_AOT_CACHE=0`; choose the exact stock executable above. `-Srepro` keeps
user dependency configuration out of the source resolution.

```sh
jolt -Srepro -Spath > /absolute/owned/canonical.classpath
bb ../check-classpath.clj /absolute/owned/canonical.classpath accept
jolt build -m perf.public-pipeline -o /absolute/owned/build/public-pipeline
JOLT_CHDB_LIB=/absolute/libchdb.so /absolute/owned/build/public-pipeline /absolute/fresh/release-store release
JOLT_CHDB_LIB=/absolute/libchdb.so jolt -Srepro -m perf.public-pipeline /absolute/fresh/source-store source
```

The negative resolution command, still from app/:

```sh
jolt -Srepro -Sdeps '{:aliases {:unpinned {:replace-deps {io.github.chucklehead-dev/jolt-otel-clickhouse {:local/root "../../.."} io.github.chucklehead-dev/jolt-codec {:local/root "../../../incubator/codec"}}}}}' -A:unpinned -Spath > /absolute/owned/unpinned.classpath
bb ../check-classpath.clj /absolute/owned/unpinned.classpath reject
```

Use the unchanged independent reader from
`research/nested-fragments-20261019/independent-reader.clj` on branch
`perf/stock-nested-fragments-20261019`, with the older exporter/SDK/JSON roots
recorded in the local build-comparison packet. That reader has existing
workspace-specific test/bench dependencies; this research packet is not a
standalone benchmark distribution. After writer and reader terminate:

```sh
bb ../summarize.bb /absolute/store/writer.edn /absolute/store/reader.edn
```

Preserve/cmp the small reports and executable/source hashes before removing
the exact owned synthetic stores/build. Do not retain database files or native
builds merely to preserve benchmark evidence. Never delete a store with an
active writer/reader or a failed/missing recovery gate.

## Next actual performance slice

The stock-library boundary already works; upstreaming compiler changes is not
on this path's critical dependency chain. Keep traversal representation access
centralized, codecs independent, SDK policy source-owned, and Durable publication
unchanged. Do not create a second implementation of the existing walker/encoder.

The learned-shape converter still creates a values vector, fresh HAMT geometry
and numeric Strings, which the encoder immediately walks. Next prototype a
**separate SDK-owned encoder handoff**, not a changed return type for the existing
plain-map converter. Compare the cumulative stack, including distinct source
roots and unsupported/custom-policy fallback; preserve original policy anchors,
callback order, byte/depth limits, independent output ownership and confirmed-only
success. A view/visitor cannot simply masquerade as a promised fresh plain map.
No production cache of all fixture values or global runtime patch is acceptable.

## Independent source review

Claude read-only review of exact commit `0c6b435` approved with no blockers.
The informational classpath flag was its only nonblocking note, clarified above.
See `claude-review.txt` and `review-resolution.md`; review is not a substitute
for the actual writer/reader commands and receipts.
