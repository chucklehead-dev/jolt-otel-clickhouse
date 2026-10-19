# Fork-free traversal and JSON spike

Research only, 2026-10-19. No default, dependency pin, exporter, Durable protocol,
or compiler changed. This is an independently named codec, **not** a transparent
data.json accelerator. Its production kernels do not require data.json.

## What this tests

- `traversal.ss`: library-owned folds over Jolt's ordinary persistent maps and
  vectors, without constructing MapEntry/sequence intermediates. Map visit order
  follows the stock sequence, including hash collision buckets. The vector walk
  currently uses indexed trie lookup, not an optimized leaf cursor.
- `json.ss`: direct UTF-8 JSON / newline-delimited rows into private 64 KiB
  chunks, then one independent, contiguous byte array. No StringWriter, whole
  payload String, global protocol changes, or fork-only byte-array adoption.
- A stock runtime constructor wraps the fresh final bytevector. Collection and
  array representation access is deliberately centralized and version-sensitive.
  This is not a stable public Jolt ABI or a claim of arbitrary-version support.
- Limits apply during emission. Failed calls expose no prefix; each new call has
  independent state. Concurrent mutation of inputs is outside this sketch.

Supported values: nil, Boolean, strings, keywords/symbols (unqualified name),
integers, finite doubles, ordinary maps with string/keyword/symbol keys, and
vectors. Non-ASCII Unicode scalars are literal UTF-8; lone surrogates reject.
This explicit contract does not consult third-party JSONWriter extensions.
Sorted maps, arbitrary seqs/records, options/custom encoders, resource reuse,
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

The isolated runner completed successfully after review: 79 dependency-free probe checks,
180 independently parsed oracle cases, 360 reference equality/readback checks,
15 entry-allocation controls, whole-batch changed-input/output controls and
all measured full-output comparisons. Source hashes were unchanged across it.

| Prepared-row component | p50 | p90 | p95 | p99 | Estimated bytes/row, median |
| --- | ---: | ---: | ---: | ---: | ---: |
| Stock data.json, literal UTF-8 | 1203.24 ms | 1440.52 ms | 1633.74 ms | 1633.74 ms | 193,829 |
| Explicit codec, stock Jolt | 46.92 ms | 51.94 ms | 53.80 ms | 53.80 ms | 2,355 |

These are 5,000-row batches, each 4,025,770 output bytes. At 12 samples per arm,
p95/p99 are simply the observed maximum, not evidence about production tails.
Large baseline GC counts remain in the raw samples. This ~26x component ratio
compares against unmodified data.json, **not** the already optimized selected
stack. Do not extrapolate it into a collector/Durable speedup.

The [independent review and disposition](review-response.md) preserve both
Claude's source-only findings and Codex's checks. The follow-up runner passed
the collision-root controls and separate-parser extreme-double corpus too.

## Next bounded slice

Extract a loadable Clojure API with fail-closed runtime qualification, then feed
the existing rich typed exporter fixture through it on stock Jolt. Preserve
typed descriptor/value/status semantics and nested EventsJSON/LinksJSON. Compare
against the selected encoder before a real exporter/Durable experiment. Reuse
existing `jolt-bytes` ownership/window/cursor work where applicable rather than
creating a second general-purpose buffer framework. AOT/standalone packaging
remains a separate required gate; source-evaluated Scheme is not proof of it.
