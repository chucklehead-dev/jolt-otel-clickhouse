# JSON worker attribution, qualified runtime and Oscope integration

## Progress and exact source custody

Previous goal turn was progress: matched format evidence rejected promotion of
the losing RowBinary-over-SQL prototype. This turn further attributed the
winning JSON path, qualified the hosted corrected runtime and moved coordinated
SDK/exporter/JSON pins into a real Oscope PR. Targets are still unmet; goal active.

Oscope PR168: https://github.com/chucklehead-dev/oscope/pull/168
branch `deps/sorted-json-sdk-stack-20261018`, clean published head
`a8b12af485c88de2d6fadba6d1c5df30c2104180`, base currentmain
`d7558da043ad957515bbfa4b356c06198ade7fc0`.
Worktree `/home/chuck/ai-src/worktrees/oscope-sorted-json-sdk-stack-20261018`.
Exactly three files changed: deps.edn, dependency_resolution_test.clj, changelog.
Root SDK7878a2e/exporter4d48871/optional nativeJSON96f selected together;
ordinaryJSON0f/driver3d/runtime artifact and defaults unchanged.

The review diff and proposed PR text were shown BEFORE submission, and accepted
PR166's style read. Submission was authorized by standing owned-Oscope approval;
no upstream PR/comment opened. Source-only Claude40222 found the new JSON red
control did not activate the alias; fixed it to use active native alias plus
override-deps for the prior8d coordinate. Actual tests rerun, follow-up69844
terminal0 clear. Commits use Chuck's GitHub noreply email, no AI trailer.

## Actual Oscope gates (not a minimal-profile or throughput claim)

On verified hosted7098 binary, real product Git coordinates (no helper source
overlay/local product dependency overrides):

- Focused68623 terminal0:112 tests/1586 assertions across dependency16/78,
  config26/150, export options12/295, embedded32/705, server26/358.
- Native fixtures89415 terminal0: typed embedded fixture3,1test/8assertions,
  stream1, companion2/bound2/empty2; standalone fixture2,1test/40assertions,
  streams5, companion2/bound2/empty2, reader0 terminal/settled/valid.
- Full embedded72563 terminal0: fixture4,1test/18assertions, streams14,
  companion10/bound10/empty8; real SDK capture, viewer/background-query lifecycle
  and reader1 terminal/settled/valid. All fixture receipts nofail/noerror and
  final settled bit1. Existing fixture-owned stores clean up after readers.

Default initial sandbox invocation failed fetching a dependency before tests;
not credited. The network-enabled focused run above is the actual passing run.

Minimal profile `profiles/embedded` is intentionally not updated by this PR.
Its SDK8110c12f/driver3e3141fd/exporterdfbc9ea6/JSON97298fd8 selections remain a
different authority. New owned issue167 records the coordinated refresh and
real-profile qualification obligation; completed issue77 stays closed. Existing
PR144 fixture work must be inspected before superseding it. No app/default
compiler migration or live external Langfuse/Lemonade/S3-service claim here.

## Hosted corrected runtime: full CI now green

Artifact producer39151375518 completed SUCCESS at workflow
`54a3719204ab19f96a297188508968b1bb8182d3`, compiler source
`7098a8d6fbd1665215e3a24d5ac4bf9a53859101`, tree
`571f8df965464e37298a5dce83e16d92127f4c76`.
Full makeci/shared-library gate and real binary capability probes passed.

Artifact12145549696 `durable-runtime-stock-fold-7098a8d6-linux-x64`, archiveSHA
31bc6cc1d76a4d584fb8af7a0071fcb09bd40dcc7f55e91a719a421f3f9b6422.
Downloaded archive digest, exact3entry allowlist, SHA256SUMS, source/tree/workflow/
run/version/plain-kind/Chez10.4.1/fold/ranged-append manifest values verified.
Binary SHA75c47a6d30cc8f00e53230123383c078222bdfeda790c2660dbecfd399e08798,
version`jolt v0.8.21-60-g7098a8d6`.
Retained executable+manifest/checksums at
`evidence/jolt-runtime-stock-fold-7098-artifact-20261018.DQfwReHc` (~29MB).
Wrapper`evidence/jolt-hosted-stock-fold-7098-20261018.sh` hash-guards the binary
and routes through mandatory Chez10.4.1 wrapper. No default local installation
or canonical integration/aspects ref changed. Removed only the verified20MB
download archive after extraction/consumer gates; manifest and binary retained.

Consumer63805 terminal0: JSON65tests/1006assertions and exporter56tests/981
assertions +58 dependency controls pass on this ACTUAL hosted binary. First
consumer attempt1 failed before tests because its test path missed src/test/jolt;
failure log/runner retained, corrected runner has separate fixed log. No failed
attempt is counted as a passing gate. No throughput measured on7098; previous
matched rates still belong to631984d6 and must not be relabeled.

Latest stable release rechecked official API:v0.8.23, published2026-10-17.
The extraction source18dce94a full fork tests39151345065 now SUCCESS, including
Linux, Android, Windows dependency paths and attribution. Updated upstream PR
draft states green CI but still leaves contributor benchmarks and standalone/
state-image qualification as pre-submission gates. No upstream submission.

## JSON worker profile: measured next priorities

Corrected profile63737 completed writer+independent reader exit0;175000 exact
physical rows checked. Runtime631984d6/exporter4d48871/SDK7878/JSON96f/driver3d.
Only coarse diagnostic wrappers and existing writer-phase observer; no data.json
JSONWriter roots, conversion policies or native executor semantics replaced.
Each of11 expected worker phases has32 complete ordered events, all Thread-1.
Projection/encoding remains on the calling thread. Counters distinguish these;
calling-thread CPU is not the CPU of ClickHouse's other engine threads.

| Worker phase | p50 ms | p99 ms |
| --- | ---: | ---: |
| Native classification | 2.25 | 6.83 |
| Native compact stream | 87.75 | 148.34 |
| WAL preparation | 34.79 | 69.17 |
| WAL append | 5.92 | 13.00 |
| WAL seal/join | 1.59 | 3.68 |
| WAL publication | 42.61 | 68.99 |
| Head commit | 6.45 | 13.24 |

Within publication: file digest p50=9.65ms, immutable PUT21.45ms,
independent verify9.29ms; within commit headCAS3.96ms. These are NESTED phases,
not extra time to add to their parents. Do not sum phase medians. Diagnostic
wrappers perturb timing; this run is attribution, not a new throughput claim.
Classification (~2ms) is not a worthwhile next optimization at the expense of
admission guarantees. Next meaningful performance action: split row construction
from native JSON emission in a bounded component screen, then implement on the
dominant side; don't skip independent verification to chase a small hash win.

First profile35207 exited1 before native open: instrumentation incorrectly
assumed writer-dbspec is normalized only once. Source shows JDBC normalization
legitimately calls it twice. Corrected wrapper is idempotent for its OWN observer
and rejects a different hook; it records both writer and control closed phase
vocabularies. Old failed source/log preserved. No driver bug inferred.

Profile source checksums/final logs retained. After exact terminal0, full scalar
validation and cmp of writer/reader/worker receipts, retired only
`/tmp/json-rich-worker-profile-fixed-20261018.riwphqM2`744MiB; absence verified.
Tiny diagnosed pre-open failure scratch retained; no reproducible bulk store or
live local benchmark/build/review remains. No trim/compaction performed.

## Live remote handles: poll, do not duplicate/cancel

Manual Oscope runs at exacta8b12af:
tests39154573033 and native-export39154575148 were in_progress.
PR creation legitimately triggered ordinary PR checks at samehead (not a retry):

- tests39154790964: Typed Clojure pilot alreadySUCCESS, headless+Chromium running.
- native-export39154790966:stream running (keeps1ae writer +stock0.8.22 reader).
- minimal-profile39154790990:running unchanged-profile compatibility regression.
- durableAWS39154790992:linux-x86-64 running.

PR168 last refresh:OPEN, UNSTABLE(pending checks), exacthead a8b12af, base d7558da,
no external comments/reviews. Do not merge until actual checks complete green;
refresh head/base/checks/participants and use expected-head guard. Owned PR/CI
work needs no additional user approval; external upstream submission still
requires their review. Keep goal active: perf targets/local+S3 and actual app
integration are not complete merely because these bounded gates pass.
