# Fixed-schema projection on stock Jolt

Research, not a new default or public SDK API. Official Jolt 0.8.23, pinned
Chez 10.4.1, SDK 3366a332, published codec 7ab2bed. No compiler/data.json edits.

Compile a caller-supplied ordered set of at most 128 String keys into a plain
map template containing ordinal indexes, not telemetry values. For each sorted
input, check stock operations, count, node geometry, full key sequence, bounded
scalar values and original owner-supplied live policy anchors. Fill a private
value array and clone the template's hash geometry with the new String values.
No per-map hashing/transient insertion, comparator callbacks, source mutation,
global registration, input cache, or changed acknowledgement. Declines retain
the caller's normal conversion. Static key objects may be reused; String key
identity is not promised. Inputs and schema keys must remain immutable.

This intentionally does not accept arbitrary number types, custom map ops or
change scalar normalization. The native boundary is version-sensitive. The
owner must supply ORIGINAL policy references, never a snapshot of current
caller roots. The research SDK bridge uses private SDK anchors/cache entry
points; this is not a supported cross-library integration API yet.

## Correctness

`shape-projection-test`: **6 tests / 110 assertions**, zero failures/errors.
Includes widths 0/1/8/9/16/32/128, hash collisions, descending comparator without
calling it, exact output kind/order, metadata removal, fresh wrappers, changed
values, malformed/count/ops/cyclic tree rejection, unsupported values, policy
change/restoration and construction under a changed root. Native instrumentation
proves transient insertions occur only at plan construction.

The real SDK cache composition test confirms root hits skip the new plan while
returning fresh wrappers, equal/distinct roots invoke the plan, unknown schemas
fall back, and all eight SDK roots still fence cache results. The first attempt
passed no traversal folder and got the expected unavailable cache on stock
Jolt; the corrected gate injects the actual published library folder, without
installing a runtime helper. This bootstrap failure is not a semantic control.

## Isolated conversion screen

16 samples/cell, 5k maps, 3 warmups, interleaved forward/reverse cell order.
Known schema compilation is excluded. Both modes include the result batch
vector. Full encoded outputs match; a changed value changes the output.

| Width / roots | Existing converter p50 | Template p50 | Estimated B/map existing / template |
|---|---:|---:|---:|
| 17 / shared | 17.61ms | 6.47ms | 1,868 / 1,116 |
| 17 / distinct | 18.26ms | 6.98ms | 1,868 / 1,116 |
| 32 / shared | 28.98ms | 10.80ms | 3,356 / 1,884 |
| 32 / distinct | 32.62ms | 11.61ms | 3,356 / 1,884 |

`conversion-screen.edn` retains observations/CPU/GC/all four percentiles and
source URIs; `conversion-screen.sources.sha256` records the initial files. The
Scheme kernel and conversion screen remain unchanged. Since that screen, the
loader gained only the unused-at-that-time cache-composition function, and the
tests gained the actual SDK cache test. Allocation is a coarse counter estimate,
not an object profile. 16 samples do not qualify production tail percentiles.

## Rich exporter ABBA v1

Same actual projection, SDK root cache (64 insertions / 65,536 characters),
typed columns and byte codec as the previous component diagnostic. Only the
known 17-key resource/scope/event attribute shape uses the new plan on cache
misses. Unknown shapes use the original SDK converters. Cache hits and stores
remain SDK-owned, including policy/budget checks. Compile the schema outside
timing; this does not measure schema inference or administration.

Four fresh processes: baseline / template / template / baseline, each 12
samples per cell. Same runtime/dependencies for every arm. No concurrent local
build/review. Full output is the original 8,647,780 bytes / SHA256
`d954be4c868606f29395483bb22b10878284a3d32a41d801a93519722d015524`
in all 288 observations; changed-event controls still differ. Both byte encoding
and source hashes are checked after each arm. No database or Durable I/O.

| Cell | Baseline p50 (first / last) | Template p50 (first / second) |
|---|---:|---:|
| Shared projection | 54.11 / 51.44ms | 71.92 / 67.93ms |
| Shared prepared encoding | 35.29 / 34.36ms | 42.41 / 40.45ms |
| Shared combined | 97.61 / 94.30ms | 125.42 / 101.04ms |
| Distinct projection | 130.12 / 119.44ms | 134.93 / 105.94ms |
| Distinct prepared encoding | 55.16 / 52.20ms | 69.31 / 54.60ms |
| Distinct combined | 197.10 / 187.53ms | 187.83 / 166.18ms |

Distinct combined allocation falls about **8.6%**, 12,685 -> 11,592 B/row.
Shared combined allocation grows about **128 B/row**, 9,026 -> 9,155. The
unchanged prepared-row encoding controls are also slower, so timing does not
establish an isolated overall improvement or regression. Shared timing is
unacceptable for promotion regardless. All p50/p90/p95/p99/CPU/GC observations
are in `rich-abba-v1/`; p95/p99 are maxima, not tail qualifications.

The research wrapper currently allocates a `some` visitor/cursor on every cache
miss, even for ordinary-map fallbacks. Next: remove that avoidable dispatch
allocation and preserve the original plain-map-first ordering, then remeasure.
Retain this v1 evidence rather than rerunning it unchanged or claiming the
isolated 2.5-2.8x improvement applies to the full pipeline.

## Run

From this worktree, add `research/shape-projection-20261020` to `:paths`, select
the published SDK/codec pins above, and run `-M -m shape-projection-test` through
the mandatory workspace Chez wrapper and qualified stock binary. The isolated
screen additionally needs the existing exporter benchmark support on its path
and writes a report via `screen.clj NEW_OUTPUT_EDN`.

`bash research/shape-projection-20261020/run-rich-abba.sh NEW_ABSOLUTE_DIRECTORY`
reproduces the rich comparison on this workspace. It refuses existing output
directories, validates the stock artifact and borrowed fixture source identity,
and records/checks source hashes. The producer is still a decorated synthetic
ended-span fixture, not live SDK capture or real Samizdat ingestion.
