# Stock-Jolt explicit row-view experiment

Base `21f6d18c1523762b0e3a71020d5f22bf7287ce4d`, official stock Jolt 0.8.23,
Chez 10.4.1. This introduces an experimental generic row-construction API in
the already independent codec component; it does not modify exporter/SDK
production code or enable anything in Oscope. No compiler or data.json patch.

## Construction boundary

`row-builder` compiles a fixed-arity constructor once per schema (0..32 fields).
It builds private native vector storage rather than a persistent vector and
its construction intermediates. `append-row-fields` references a persistent
vector suffix without copying/flattening either side. The explicit codec
emits both as one JSON array. Values/converters are still evaluated eagerly
before constructing/encoding a row. No mutable native vector is exposed and
no raw-JSON input is introduced. Returned byte arrays remain independently
owned. Inputs must remain immutable during use. This is low-allocation, not
an allocation-free claim; one base vector/row view and optional append view
are still created.

The 32-field constructor bound avoids a reproduced stock runtime arity-mask
defect at 60+ arguments. A 128-element vector suffix works. There is no 32-column
schema limit; only the fixed-arity constructor has that bound. Raw source
probing of the Jolt invocation contract is retained separately.

## Paired encoding screen

`screen.clj` reads the live private span-row form and replaces exactly one
24-field compact vector literal with the captured row builder in an isolated
function. It reuses every conversion/callback expression and original order;
it does not replace a global Var or patch production source. This is a
prototype, not a production integration API. The normal path still constructs
and extends its vector. Both arms use identical JSON/traversal code, the same
SDK normalization cache, typed projection, private nested text, raw ticks and
synthetic rich 5k-row fixture. Record size checks are present in both arms.

32 ABBA observations per arm, full-output SHA256 equality after each timed
section, changed-event negative controls, raw CPU/GC counters:

| Measurement | Persistent vector | Row view |
| --- | ---: | ---: |
| p50 |126.68ms|126.95ms|
| p90 |145.97ms|135.94ms|
| p95 |155.83ms|142.10ms|
| p99 sample quantile |162.21ms|178.99ms|
| Median estimated allocation/row |9,869B|9,293B|

Estimated allocation falls about 576 B/row (5.8%). **No median throughput win
or improved tail is established.** The p99 is the maximum of 32 observations,
not a population-tail qualification. No actual SDK drain, native insertion,
WAL, recovery, S3 or application throughput is measured. Do not compare these
absolute numbers against another run as if they were a paired experiment.
This does not qualify the 25k p50 / 20k p99 confirmed-row objective.

The allocation result is useful evidence, but not enough to turn this into a
production exporter path yet. Next larger hypothesis: payload-scoped reuse of
encoded immutable nested attribute maps, or more complete projection/encoding
fusion. Any such work must preserve callback order, limits and output ownership
and measure the cumulative stack, not discard these results or rerun unchanged
screens for a favorable median.

## Gates and artifacts

Source and an actual Linux standalone binary executed from a neutral temporary
directory each pass 23 tests / 242 assertions. This includes 3 new tests / 43 assertions
for values, arity/width bounds, suffix independence, 128-wide suffixes, limits,
output mutation isolation, failure-before-next-record, depth 128/129 equivalence,
cached/uncached modes and reentry. Existing library tests remain unchanged.
The test source and build outputs are small/temporary respectively; only the
source, reports and terminal logs are retained. No database is created.
The existing local exporter gate also passes 39 tests / 276 assertions.

Claude's bounded read-only review found no blocking issue and agreed this
should remain a research checkpoint rather than become an exporter path on
these results. Codex independently ran the source, binary and exporter gates.
The review notes two non-blocking follow-ups: put the width-bound rationale
next to its source check, and add explicit unsupported-value cases in suffix
fields (the shared emitter is currently tested with unsupported base values).
Those are retained review notes, not claimed as completed coverage.
The runtime boundary is tracked in
[jolt-aspect-packs #183](https://github.com/chucklehead-dev/jolt-aspect-packs/issues/183),
not upstream. Jolt's current latest release was independently refreshed as
v0.8.23 during this work; the experiment is not deliberately targeting an
older release to preserve compiler-fork compatibility.

Reproduce from the repository root:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0 \
  /home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt \
  -A:test:experimental-span-codec-test \
  research/stock-row-view-20261019/screen.clj /absolute/path/result.edn
```
