# Template key prefixes: measured, not adopted

Research on exporter base `a6cbf9390d1521026e5da5d2ea3cfc0b8916ae2b`.
Official stock Jolt0.8.23, mandatory Chez10.4.1 wrapper. No compiler,
data.json, SDK, product codec, pin, default, WAL or acknowledgement changes.
No native builds, databases, model runs or large artifacts were created.

## Question and library boundary

Can an explicit byte codec avoid repeatedly escaping keys for SDK-owned scalar
views, without caching telemetry values or requiring a compiler fork? The
existing traversal and codec are independently selectable dependency-free
modules; this is a research specialization of that codec, not another runtime
helper installed globally. The full rich screen still resolves the parent
exporter's ecosystem forks; it is NOT an entirely fork-free application test.

The candidate looks up a weak template-root identity once per map, learns its
ordered field ordinals and copies pre-escaped key/comma/colon byte prefixes.
Values are read fresh from every view. It uses the same native map traversal,
SDK-owned converter and typed projector as the control. Original template,
scalar-view and nested-text record types are shared lexically across arms.
Exact source patches reject missing/duplicate anchors with assertions enabled.
Nothing is registered as an alternative data.json backend or exposed as raw
JSON insertion. No user-facing API has been added.

Per encoder invocation: eight attempts INCLUDING declines, 128 fields/plan,
64 scalar characters/key, 32KiB prefixes/plan and128KiB total prefix bytes.
These are not RSS bounds. Plans retain only copied bytes and integer ordinals,
not input wrappers/trees/metadata/values; the weak table keys are template roots.
Input/schema Strings must remain immutable. Invalid/oversize key shapes fall
back to the original scalar entry writer. A partial failed plan is discarded
and not charged as a retained plan. All limits are prototype constants.

## Fixed rich screens, both retained

5k rich spans, original/disabled/prefix/prefix/disabled/original,16 observations
per arm, full SDK policy/conversion/typed projection/nested+outer encoding.
Every complete8,647,784-byte output matched; changed event and resource-value
controls also matched the original and differed from unchanged input.
Only one measured local workload ran at a time, with no concurrent Claude task.

| First screen | p50 /p90 /p95 /p99 ms | Median estimated B/row |
| --- | --- | ---: |
| Original |145.33 /161.45 /195.56 /195.56|8871.13|
| Added structure, plans disabled |154.28 /200.89 /212.84 /212.84|8982.43|
| Prefix plans |158.11 /187.34 /191.11 /191.11|8988.77|

Prefix median8.80% slower, estimated allocation1.33% higher than original.
The disabled arm exposed overhead even without prefix compilation. This does
not isolate a specific allocation site or prove the helper is the sole cause.

The materially changed follow-up keeps the ORIGINAL scalar-entry callback
intact and inlines its value writer inside the prefix-hit loop, avoiding the
extracted per-value helper. Fixture, order, sample count and oracles are the
same. `inline.clj` compiles distinct sources; `inline-screen.clj` selects those
kernels without reloading/replacing them with the first candidate.

| Inlined follow-up | p50 /p90 /p95 /p99 ms | Median estimated B/row |
| --- | --- | ---: |
| Original |150.97 /213.34 /286.58 /286.58|8871.13|
| Added structure, plans disabled |169.28 /197.36 /275.74 /275.74|8983.16|
| Prefix plans |145.56 /184.09 /203.80 /203.80|8990.13|

Prefix median3.58% lower within this second process, allocation1.34% higher.
This small component result does not establish a stable end-to-end benefit or
justify adoption/native qualification yet. Do not compare absolute timings
between processes as a causal improvement, discard the negative first screen,
or rerun unchanged seeking a favorable result. With16 observations,p95/p99 are
the maximum, NOT population tails. Counters are separate non-atomic snapshots;
allocation is an estimate combining live-heap and reclaimed-byte deltas, not an
allocation-site/RSS profile. CPU counters measure the observing thread, not the
whole process. No SDK queue, native I/O, recovery, S3 or throughput target is
qualified by either screen.

Adjacent six-observation blocks reinforce the caution:prefix mean time is lower
in only2/8 blocks in the first screen and6/8 in the inlined follow-up. In the
follow-up, changes range from37.76% lower to21.81% higher; its largest apparent
gain coincides with a slow original block. These are descriptive arithmetic
over the retained samples, not an independent significance or scheduler test.

## Why the cache is not simply missing

Separate untimed instrumented32-span execution:33 encoder invocations,63
scalar-view visits, one plan compiled (190 prefix bytes),63 selected-plan
visits. Key-writer calls fell from2240 disabled to1169 enabled:1071 avoided.
Both complete55,216-byte outputs match. The real SDK therefore DOES reuse this
plan; lack of cache lifetime is not an explanation for the negative screen.
This observation is from the original extracted-helper source, not a timing or
a measured mechanism count for the inlined follow-up. Instrumentation is NOT
present in either timed kernel.

## Functional controls

Original prototype:5 tests /199 assertions. Follow-up: same original suite,
then4 reused functional/budget/mechanism tests /196 assertions on the inlined
kernels. All pass. Checks include fresh values, permuted ordinals,0/1/8/9/17/
32/128-field templates, Unicode/control/quoted keys, declined long/non-String
keys, exact/one-under output budgets, depth boundaries, >64KiB values, SQL
prefix accounting, fresh output ownership, invalid ordinals and stopping before
a later record on failure. Separate mechanism variants count actual avoided
key writes and enforce attempt/per-plan/total byte limits.

Two original-source mutants are rejected by real oracles:all ordinals mapped
to0 changes bytes, and removing the extracted depth guard incorrectly accepts
an over-deep scalar value. Those mutation source patches are NOT reused for the
different inlined source shape. No heap-retention proof, every runtime/layout,
full JSON conformance or new nested-scope lifecycle proof is claimed.

## Reproduction and next decision

From this worktree, prefix the exact stock executable with the mandatory wrapper:

```sh
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src"]}' \
  research/template-key-prefixes-20261021/inline-check.clj
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src" "research/scalar-view-exporter-20261020/app/src"]}' \
  research/template-key-prefixes-20261021/screen.clj /absolute/owned/screen.edn
```

Use `inline-screen.clj` for the second screen and `rich-mechanism.clj` for the
untimed counts. Keep the small sources, scalar reports, logs and checksums.
Production sources are unchanged and no new codec option should be enabled.

Next larger opportunity remains combining normalization and encoding without
building temporary attribute maps/Strings. The previous direct-sink allocation
result is relevant but not owner-policy/fallback equivalence. A real adapter
must preserve all SDK policy anchors, collision/fallback behavior and limits,
and must not let an immutable input loan escape synchronous encoding. The
existing #141 preparation/publication design is separate and still subject to
its outstanding RevisionB type/scope approval. No concurrency or persistence
contract change is implied by this research.

## Independent review

Claude's bounded read-only source review found no blockers to publishing this
as unadopted research. It checked the kernel selection, source anchors, cache
bounds, oracle controls, result arithmetic and claim boundaries. The complete
unedited review is in `claude-review.txt`. Claude did not execute tests, prove
heap retention or validate a native pipeline. Codex executed the named controls
and screens, checked checksums and independently computed the adjacent-block
descriptions; those descriptions are not a statistical qualification.
