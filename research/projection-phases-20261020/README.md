# Direct projection versus prepared-byte encoding

This diagnostic reuses the rich 5k-row fixture, actual `span-row` and typed
vector projector, SDK-owned scalar conversion, and the published codec. It
does not change producer source or replace projection callbacks. The fixture
decorates prebuilt ended-span maps; it is not live SDK capture or delivery.
The descriptor installation is the existing test fixture, not a live catalog.

| Shape | Projection + nested preparation p50 | Prepared-row encoding p50 | Combined p50 |
|---|---:|---:|---:|
| Shared sorted attribute roots | 53.1ms | 40.1ms | 99.5ms |
| Equal values, distinct sorted roots | 128.8ms | 52.3ms | 188.2ms |

Estimated median bytes/row in those three cells are 5,603 / 3,467 / 9,026 and
9,262 / 3,467 / 12,685 respectively. These are coarse live-heap/collected-byte
estimates, not object profiles. All p50/p90/p95/p99 and CPU/GC samples are in
`result.edn`. Twelve samples per cell make p95/p99 maxima, not population tails.

Projection includes real attribute conversion, timestamps/enums, nested JSON
preparation, and building the ordinary 24-column vector plus four typed fields.
It additionally materializes a batch result vector. Prepared encoding excludes
that work and reuses already-prepared, immutable rows. The combined operation
projects incrementally. Independent medians and counter deltas are **not additive
phase attribution**, nor a causal version comparison or throughput ceiling.

Both sharing shapes, every prepared/full route and all 72 timed outputs have
the same complete 8,647,780-byte SHA256
`d954be4c868606f29395483bb22b10878284a3d32a41d801a93519722d015524`.
Changed-event controls differ in both full and project-then-encode routes.
Native root-identity checks distinguish actual shared/unshared trees despite
equal values. Original producer source and exporter sources remain unchanged.
This is functional wire equality against the existing pipeline, not independent
physical schema/readback proof. Native database I/O, Durable and S3 are absent;
normal runtime/digest native primitives are still used.

## Provenance / reproduction

- Exporter source: main `7ab2bedcb2e1845efdf6ed5c55ae9d2164338a8d`.
- Published codec subproject at that same SHA; no local codec overlay.
- SDK `3366a3322c3ea4024057f6278e50c999864171e8`.
- Official stock Jolt 0.8.23, pinned Chez 10.4.1.
- Borrowed only test/benchmark support from clean
  `/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019`,
  commit `0af1008185cf84577298d3a0a0b2db87b97bddd5`. Current exporter source/test
  paths precede those support paths; selected support URIs are recorded.

Run `screen.clj OUTPUT_EDN` from this exporter checkout, through the workspace
Chez wrapper and checksum-qualified stock binary. Use `-Srepro`,
`JOLT_AOT_CACHE=0`, source paths `src`, `test`, then that support checkout's
`test` and `bench`; explicitly select the SDK and codec pins above via `-Sdeps`.
The codec uses `:deps/root "incubator/codec"`. Preserve the root SDK coordinate's
`:exclusions [jolt-lang/jolt-crypto]`. Source hashes were recorded before and
verified after the successful run; no local build or review ran concurrently.

Two bootstrap attempts stopped before any measurement (first missing support
namespace; second selected the separate chDB fixture checkout instead of the
exporter support checkout). They produced no result report and are not negative
semantic controls. Their small workspace logs remain separate. No databases,
standalone binaries or large persisted stores were generated.

## Next action

Projection/nested preparation is the stronger next lead, especially when equal
maps lose root sharing; prepared byte encoding is still material. Do not assume
the fixnum-only helper domain explains this fixture: its attribute integers fit
native fixnums. One concrete source-level inefficiency is repeated traversal of
immutable SDK policy-binding vectors on every map. The payload cache already
decodes the same pairs once into native anchors while retaining live Var-root
checks. Apply and measure that pattern in the plain/sorted converters before
inventing another cache or changing publication/concurrency contracts.
