# Bounded typed field-slot collection (candidate)

Base main `f0aa75dd595dff82290fb2c19389d2a6ad02c1e6`, refreshed before branching.
The preceding operation-allocation diagnostic identified typed projection as a
bounded improvement target, despite only two promoted fields in the rich fixture.
This slice changes neither JSON/WAL encoding nor publication/concurrency.

## Implementation

Compile at most8 unique `(location,key)` fields in at most3 trace locations.
For ordinary immutable String-key maps of at most128entries/location, collect
raw selected values directly into field ordinals, avoiding intermediate maps
and the outer collected-values vector. Schema tables stay immutable; all
input/output scratch is invocation-owned. Absent values remain distinct from
present nil/false. The SAME original classifier receives each singleton/empty
input and decides value/status in field order, with its existing continuation
or live replacement's ordinary two-argument contract. Continuations retain no
output buffer. No source wrappers/trees are retained after an invocation.

The defining namespace supplies its original key normalizer. The native helper
guards that root plus live stock membership/String predicates before/after
callback-free scans. Unsupported maps/keys, replaced policies, duplicate schema
keys and larger plans use the existing indexed fallback. Custom callbacks are
not skipped or speculatively replayed. Generic resource/scope/span capture,
status codebooks, output ownership, confirmed descriptor/target checks and
portable two-argument projection are unchanged. Runtime/core/folder internals
and immutable inputs are trusted; this is not a cross-thread redefinition lock
or a general guarantee for woven helper bodies. No heap/RSS claim is made.

## Executed qualification so far

Official stock Jolt0.8.23, pinned Chez10.4.1; no compiler or data.json patch.
Focused stock explicit-folder gate:8tests561assertions pass. Includes portable
equivalence, all three locations, nil/false/empty, full Int64 range, invalid
types, aliases, sorted/large/record fallbacks,128generated inputs, fresh outputs,
preinstalled/live normalizer replacement, membership effects, projector
list/short/nil results and reentry. A fallback-throws witness proves the fast
branch is actually selected; wrong-folder mutation changes output and fails
the equivalence oracle. Baseline published codec/exporter gate71/553 also passes.

The legacy `:native-projection-test` gate assumes the older qualified runtime's
global folder and is not a blanket stock-Jolt gate. An initial attempt on stock
hit that pre-existing missing helper and also exposed this candidate's missing
Scheme closing delimiter; that syntax error was fixed before the focused gate.
Do not report the legacy attempt as passing or the delimiter error as baseline.
The new gate supplies the independent traversal capability explicitly. Hosted
stock-library CI at `f4b4e457600de951e8f8e62faacab6be9ca855cb` passed and its
actual log records8tests/561assertions, not merely a green job status:
[run39581595584](https://github.com/chucklehead-dev/jolt-otel-clickhouse/actions/runs/39581595584).
This does not qualify a later head or the native throughput experiment below.

Initial Claude source review found a missing continuation-bridge capability
fence. The factory now checks presence/procedure/arity of `jolt-invoke3`, matching
the existing positional emitter, and fails at construction before classification.
A new test supplies a wrong-arity bridge in a local native `dynamic-wind` and
restores it on every exit. All8/561 pass. The component timing below PRECEDES
this constructor-only guard; its recorded hashes are historical, not rewritten
to imply the newer tested source was timed. The hot per-span body is unchanged.
The follow-up source review closed that finding without reporting a new blocker;
neither review executed the tests or persistence comparison.

## Paired component screen

One stock source-mode process; indexed/fused/fused/indexed,16samples/arm.
The baseline constructor disables ONLY the new factory, selecting the original
unchanged indexed implementation. No timing/observer callbacks are installed
on the candidate hot path. Approved descriptor fixture,5k rich ended spans,
original SDK6cd57c86 and original codec/normalization/typed rules. All portable
typed vectors AND complete8,647,784-byte rich outputs match, with changed-source
control. Descriptor fixture installation is not native DDL.

| Screen / arm | p50 /p90 /p95 /p99 ms | Median estimated B/row |
| --- | --- | ---: |
| Typed only / indexed |14.04 /20.45 /21.23 /21.23|1,309.49|
| Typed only / fused |4.97 /6.12 /9.39 /9.39|411.40|
| Rich encoding / indexed |163.38 /208.21 /210.46 /210.46|9,768.93|
| Rich encoding / fused |147.49 /197.41 /334.25 /334.25|8,870.99|

Typed median~64.6% lower and allocation~68.6% lower. Rich median~9.7% lower and
allocation~9.2% lower, but sample maximum WORSE. This is NOT a demonstrated
tail improvement, release/native throughput or the requested25k/20k target.
Sample p95/p99 are both maxima at n=16. Runtime allocation counters are separate
live-heap/GC-byte snapshots, not an allocation-site/RSS profile. Retain all
observations; do not compare different historical processes as causal controls.

Reproduce from exporter root with the mandatory Chez wrapper/pinned stock binary:

```sh
jolt -Srepro -M:native-field-slots-test
jolt -Srepro -M:stock-span-codec:stock-span-codec-test
jolt -Srepro -Sdeps '{:paths ["src" "test" "bench" "incubator/codec/src" "research/scalar-view-exporter-20261020/app/src"]}' \
  -m otel.exporter.chdb-field-slots-benchmark /absolute/owned/screen.edn
```

Raw samples, source hashes and focused test output are retained alongside this
note. The benchmark reuses the existing workspace fixture, not a standalone
distribution. No database/native build/bulk evidence has been created by this
screen. No exhaustive/model run: no protocol transition changed.

## Actual confirmed local Durable comparison

The declared same-binary A/B/B/A comparison now independently recovered all
700,000physical rows. Pooled confirmed window rate12,174 ->12,992rows/s (+6.71%)
and estimated allocation15,706 ->14,808B/row (-5.72%). Both pairs favor the
candidate rate; sample slow-tail latency is NOT improved. No target, SDK queue,
S3 or Oscope qualification. See [all raw receipts and scope](native-results/README.md).

## Remaining adoption gates

Product-source review, focused tests, actual default-release public exporter
ABBA and independent older full-field/type/nanosecond/multiplicity recovery
have completed. Final research/report review and its allocation-control follow-up
also completed; source-only reviews and Codex adjudication are retained under
`native-results/`. Final-head hosted CI remains.
Keep defaults and consumer pins unchanged. S3, SDK queue, Oscope and
Langfuse are separate integration gates, not inferred from this component.
The larger Revision B overlap proposal still awaits explicit scope approval;
this serial pure-row optimization neither implements nor bypasses that gate.
