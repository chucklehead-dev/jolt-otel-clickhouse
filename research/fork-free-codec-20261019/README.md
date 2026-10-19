# Fork-free traversal and JSON spike

Research only, 2026-10-19. No production default, dependency pin, Durable protocol
or compiler changed. A companion isolated exporter branch adds a private optional
nested-writer argument; no selected exporter adopts it. This is an independently named codec, **not** a transparent
data.json accelerator. Its production kernels do not require data.json.

## What this tests

- `traversal.ss`: library-owned folds over Jolt's ordinary/stock sorted maps and
  vectors, without constructing MapEntry/sequence intermediates. Map visit order
  follows the stock sequence, including hash collision buckets. The vector walk
  currently uses indexed trie lookup, not an optimized leaf cursor.
- `json.ss`: direct UTF-8 or correctly ASCII-escaped JSON / newline-delimited rows into private, at-most-64 KiB
  chunks, then one independent, contiguous byte array. No StringWriter, whole
  payload String, global protocol changes, or fork-only byte-array adoption.
  Single values start at 1 KiB and grow geometrically; rows start at 64 KiB.
  A projected-row entrypoint calls a per-batch adapter once per record instead
  of preparing a whole batch first. Nested encoder calls own separate state.
- A stock runtime constructor wraps the fresh final bytevector. Collection and
  array representation access is deliberately centralized and version-sensitive.
  This is not a stable public Jolt ABI or a claim of arbitrary-version support.
- Limits apply during emission. Failed calls expose no prefix; each new call has
  independent state. Concurrent mutation of inputs is outside this sketch.

Supported values: nil, Boolean, strings, keywords/symbols (unqualified name),
integers, finite doubles, ordinary/stock sorted maps with string/keyword/symbol keys, and
vectors. Non-ASCII Unicode scalars are literal UTF-8; lone surrogates reject.
This explicit contract does not consult third-party JSONWriter extensions.
Sorted maps with replaced operation tables reject; stock core-private roots
and immutable representation internals remain trusted. Arbitrary seqs/records, options/custom encoders, resource reuse,
decoder, schema-generated projection and RowBinary are still work to do.
Full folds are synchronous; Reduced/early termination is not implemented yet.
No allocation claim covers numeric bignums, recursion/closures, chunks or output.

## Reproduction

Run `bash research/fork-free-codec-20261019/run.sh` from this worktree.
The runner uses an official downloaded v0.8.23 Linux release, not a source overlay
or the locally installed Jolt, and checks its binary SHA-256:

`7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94`

Download archive SHA-256 was checked against its release `.sha256` file before
extraction. `FFC_JOLT` may point to another copy of that exact binary. All local
Jolt commands pass through the workspace's mandatory Chez 10.4.1 wrapper.

The dependency-free probe and separately parsed oracle use no data.json or
time provider. Only the component comparison adds **original upstream**
clojure/data.json `1fce9070ee851f49a6c4ce6e1c0bb0db4d7594a0` and upstream
jolt-lang/time `33c5e9831c046cfcbe68bc0c9c33e92568eaebb1` (needed when loading
stock data.json's date formatter). These already exist in local gitlibs.

The runner preserves small scalar results and source hashes under `results/`.
A temporary synthetic oracle corpus is checked by Babashka's native Cheshire
in a separate process and removed on exit. No database/store or telemetry from
an actual user/application is generated or retained.

## Gates and interpretation

- Probe: digit/sign boundaries, UTF-8, escaping, empty values, map sequence order,
  exception prefix/reentry, ownership, byte/depth limits and chunk boundaries.
- Independent oracle: 180 synthetic cases parsed using BB/Cheshire, including
  non-BMP text and 64-bit/unsigned-range numbers.
- Component: original data.json vs explicit codec on 5,000 prepared synthetic
  trace-shaped rows, with map attributes and nested events. Both produce the
  exact same complete JSONEachRow bytes. Every measured result is compared
  outside the timing/allocation interval; changed input must change output.
- Entry-constructor trap: ordinary seq must allocate one entry per map item;
  direct fold must visit every item without invoking that constructor.
- 24 samples in ordinary/direct/direct/ordinary order; 12 per arm. p50/p90/p95/p99
  are descriptive component latencies, **not qualified production tails**.
- Allocation uses separately sampled live-heap plus collected-byte counters.
  It is an estimate, not atomic profiler accounting. The baseline includes its
  per-row strings and final UTF-8 conversion; the explicit path avoids those.

Original data.json defaults on Jolt escape an emoji as `\u1f600`, not a valid
surrogate pair. The baseline explicitly sets `:escape-unicode false`; both arms
therefore emit literal UTF-8. Do not reproduce that escaping defect merely to
claim default byte parity. This known interop issue is distinct from the codec.

The earlier selected encoder's ~57% payload-phase share includes projection and
owned-output construction. This spike's prepared-row comparison excludes those
upstream projection costs and does not measure SDK queues, native insertion,
WAL/publication, S3, restart recovery, or the selected optimized encoder. It
cannot establish a speedup over that encoder or the Durable throughput target.

The isolated runner completed successfully: 128 dependency-free probe checks,
180 independently parsed oracle cases, 360 reference equality/readback checks,
15 entry-allocation controls, whole-batch changed-input/output controls and
all measured full-output comparisons. Source hashes were unchanged across it.

| Prepared-row component | p50 | p90 | p95 | p99 | Estimated bytes/row, median |
| --- | ---: | ---: | ---: | ---: | ---: |
| Stock data.json, literal UTF-8 | 1469.13 ms | 1745.40 ms | 1753.11 ms | 1753.11 ms | 193,827 |
| Explicit codec, stock Jolt | 38.89 ms | 49.67 ms | 50.31 ms | 50.31 ms | 1,619 |

These are 5,000-row batches, each 4,025,770 output bytes. At 12 samples per arm,
p95/p99 are simply the observed maximum, not evidence about production tails.
Large baseline GC counts remain in the raw samples. This component ratio
compares against unmodified data.json, **not** the already optimized selected
stack. Do not extrapolate it into a collector/Durable speedup.

The [independent review and disposition](review-response.md) preserve both
Claude's source-only findings and Codex's checks. The follow-up runner passed
the collision-root controls and separate-parser extreme-double corpus too.
The expanded sorted/ASCII/projection work also received a
[targeted follow-up review](rich-review-response.md), with raw source review
and independently verified red/green receipts preserved alongside it.

## Next bounded slice

The actual rich fixture and selected-encoder comparisons below now pass. Next,
qualify a loadable Clojure API and the full consumer on stock Jolt, then run the
real exporter/Durable experiment, preserving typed descriptor/value/status
semantics and nested EventsJSON/LinksJSON. Reuse
existing `jolt-bytes` ownership/window/cursor work where applicable rather than
creating a second general-purpose buffer framework. AOT/standalone packaging
remains a separate required gate; source-evaluated Scheme is not proof of it.

## Existing rich fixture versus the selected encoder

`run-rich.sh` compares the existing 5,000-span typed/nested-sorted fixture after
projection: 28 physical columns, 8,647,780 bytes, SHA-256
`8e7b560f4bc64de2f49ed88dd2fcca46e3f9dec12b63ecf9fad86a1f779244a4`.
The SQL prefix is excluded in both arms. Every measured output digest matches.
The original naive codec, intermediate optimization, and final combined run are
retained so the slower result is not lost. Final receipts live in `rich-final/`.
The combined run interleaves selected/naive/explicit/explicit/naive/selected,
16 samples each. There is no claim that the much larger stock-data.json ratio
applies to this already-optimized baseline. Projection and nested string
construction are outside this prepared-row interval.

The transferred techniques are a local ASCII cursor with proven private buffer
bounds, fixnum-specialized decimal arithmetic, chunk-capacity-based byte bounds,
and one visitor per invocation rather than a closure per container. None
requires a compiler patch. We retain two-copy chunk-to-contiguous output;
allocation at outer encoding approaches that output-storage cost, not zero.

| Prepared outer encoding | p50 | p90 | p95 | p99 | Estimated bytes/row, median |
| --- | ---: | ---: | ---: | ---: | ---: |
| Selected encoder | 53.71 ms | 63.67 ms | 67.28 ms | 67.28 ms | 3,677 |
| Original naive explicit codec | 92.77 ms | 121.71 ms | 136.51 ms | 136.51 ms | 4,373 |
| Optimized explicit codec | 54.15 ms | 64.20 ms | 74.36 ms | 74.36 ms | 3,461 |

This is near parity at p50, not a demonstrated outer-encoding speedup. Source
snapshots for the final run are preserved; intermediate results are historical
diagnostics and do not supersede `rich-final/`.

`run-projection.sh` then compares the full projection/nested-JSON/outer-encoding
component using the companion isolated exporter adapter. The injected writer
is per call, not a global protocol/Var replacement. Existing arities pass nil
and resolve the original nested writer at each original boundary. Default
compatibility and explicit failure-order tests plus the focused event/typed
namespaces passed: 15 tests, 84 assertions.
The adapter is [commit 3731f87](https://github.com/chucklehead-dev/jolt-otel-clickhouse/commit/3731f87d8811e89ac42d68098c67a26087776a6c),
on research branch `research/explicit-codec-row-adapter-20261019`, not an adopted
backend. The committed source matches the checksum recorded before the run;
the exact measured delta from base 2c54b98 is retained in the result directory.

The selected, outer-only, and fully explicit arms must all produce the same
complete digest above. The outer-only arm has a fresh native nested-writer
binding but lacks the selected collector's private string companion; it is a
diagnostic, not a clean isolation of outer encoding alone. The fully explicit
arm uses the new codec for both EventsJSON/LinksJSON and the outer payload.
It still uses the selected SDK/projection stack and runtime, so this is **not**
qualification of the full consumer on stock Jolt. Final receipts and exact
source/adapter snapshots are in `rich-projection/`.

| Full projection + encoding component | p50 | p90 | p95 | p99 | Estimated bytes/row, median |
| --- | ---: | ---: | ---: | ---: | ---: |
| Selected encoder | 212.77 ms | 248.70 ms | 258.01 ms | 258.01 ms | 16,381 |
| Explicit nested + outer codec | 185.34 ms | 223.51 ms | 225.50 ms | 225.50 ms | 13,527 |

This observed ~13% p50 reduction and ~17% allocation reduction exclude SDK
queues, owned-statement ASCII snapshots, native insertion and Durable/WAL.
At 16 samples/arm, p95/p99 are again maxima, not production tail qualification.
These component results do not establish the Durable throughput goal. The
larger remaining allocation target is projection/attribute conversion, where
intermediate stringified maps and row vectors still exist.
