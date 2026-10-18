# SDK-owned scalar-map implementation checkpoint

## Source custody

SDK worktree `worktrees/otel-sdk-owned-scalar-map-20261018`, branch
`perf/sdk-owned-scalar-map-20261018`, HEAD
`697f58bd5b4925f72339694bedbace97bdc869bb`, hardening commit
`0cd4e1a66558b5942c231cf624a7deb581e14ee4`, initial implementation
`a4d594a621d7bdb55e963ea78d2bc1b4d0d6d555`, base37c684a2.
Owned main ecce82c4 is tree-identical to that base (e760dc6b), so no source
rebase is needed merely to follow the merge commit. SDK topic is published.
Exact-head SDK CI39128818354 is COMPLETED/SUCCESS, both jobs; actual stock
0.8.23 native5/66 and ordinary AnyValue17/388 confirmed from job logs.

Exporter worktree `worktrees/exporter-sdk-scalar-map-20261018`, branch
`perf/sdk-scalar-map-consumer-20261018`, HEAD
`5a7617a4b3c806f21e15b902993156fc2f751a3c`, initial implementation
`e23d535b321e327293fa0a5d6808a0cf6be199ec`, parent5741546. Topic published.
SDK dependency now pins697f58b, and the native CI job runs the strict new
consumer gate. Pin-focused4/20 passes without local roots. Exact-head exporter
CI39129795124 is now COMPLETED/SUCCESS at5a7617a, all3jobs; logs confirm the
new consumer4/20 actually ran. No redispatch is needed.
Old SDKs retain the old conversion. No PR created or merged.
Prior submission denial for exporter5741546 remains unresolved; do not bypass.

## Implementation

SDK exposes optional `try-scalar-string-map`, not a bare consumer-snapshotted
policy token. Source-owned original scalar helper, canonicalizer, default-limit,
string-byte-counter and Int64-bound anchors are checked natively before and
after pure admission. Supported input: builtin HAMTs9..128, string keys,
bounded string/Boolean/fixnum values, no collision buckets. Fresh private nodes
preserve kind/order, omit metadata, and do not mutate input. Unsupported hosts,
layouts, helpers, inputs or loader failures return nil, requiring old scalar
conversion. Jolt scheme/native code is lazily optional; existing fast integer
formatter is reused. Loader fences fixnum width and known map/helper ABI.

Exporter selects it only in explicit native byte mode under source-owned
original key/value converter identities, checks those again after the live SDK
call, then retains the unchanged native/generic fallback. Configured JSON,
custom writer dispatch, row limits, typed descriptors and WAL/ACK are unchanged.
Private/core mutation and atomic concurrent redefinition are not qualified.
No new state transition/model logic or unchanged exhaustive check was run.

## Validation

- Initial SDK native4tests/63assertions; hardened head5/66 PASS on both
  checksummed qualified1ae and **stock0.8.22**. No compiler fork needed for
  this isolated helper. Not a claim the whole native JSON backend is stock-ready.
- Hardened source head0cd4e1a full Jolt SDK suite470tests/4086assertions PASS, process39862
  terminal0; `sdk-owned-scalar-map-full-sdk-20261018.log`.
- SDK AnyValue17/388 PASS; isolated BB/JVM helper-decline gate1/7 each PASS.
  Full codec/SDK portability is NOT claimed; existing gaps tracked in
  https://github.com/casselc/otel/issues/63 . Temporary diagnostic edits to OTLP
  code/tests were restored, and no broader portability fix was folded in.
- Exporter new4/20 PASS; existing native attributes21/607 and native
  projection24/321 PASS using the SDK topic. First native-attributes invocation
  omitted its JSON byte alias and failed dependency loading; only the separate
  qualified rerun is credited. Local pinned aggregate process15922 printed
  `all checks passed`, but its session later became missing before an exit
  receipt was obtained. No live jolt/scheme/timeout remained in host process
  inspection. Do NOT credit clean exit0 or rerun solely to recover a lost
  observation; exact-head hosted CI now provides the authoritative aggregate,
  native capability, independent recovery and scoped-S3 SDK gate results.
- Controls include preinstalled scalar replacement before lazy initialization,
  no invocation of changed helpers, metadata/input/order preservation,
  collisions/unsupported types and sizes, character bounds, native fixnum
  extremes, malformed-resource decline, missing SDK API, late key/value policy
  change after SDK return, and actual poisoned-fallback path selection.

## Performance and actual recovery

First-head SDK a4d594a / exporter e23d535 component:24paired rounds,5kwide16,
same production code with optional helper disabled/enabled, exact full row and
UTF8 equality before/after. Base p50 23.028->16.174ms; full p5068.429->67.029ms.
Base24/24 pairs faster, full18/24; mean pair ratios0.722/0.923. Managed saving
~32B/row (full3791.16->3759.20). Not tail, app or persistence qualification.

Actual SDK/local Durable/native26.9 ABBA, one SDK worker and same connection:
process54417 terminal0,32actual callbacks/5k each,16samples per arm. All
175,000 physical/typed rows (including3warmups) recovered through unchanged
accepted exporter473edbb, old SDK37c684a2 and stock JSON reader; zero drops or
failed exports. Driver3d766fa/JSON8d/qualified1ae unchanged. Scalar receipts
copied and cmp-verified before removing exact266MiB owned store
`/tmp/scalar-map-sdk-20261018.Om5inlme`; absence verified. No large store retained.

| diagnostic mean ms | baseline | candidate |
|---|---:|---:|
| projection+encoding wall |97.059|91.927|
| projection+encoding caller-thread CPU |96.741|91.630|
| native+confirmation wall |84.620|88.412|
| full insert body |186.703|186.370|

**Encoding CPU saving, NOT a demonstrated end-to-end throughput win.** Mixed-arm
aggregate25,990.95rows/s is not candidate-only throughput or a tail/Rust/S3
comparison. Full body p50174.196/173.285ms. Coarse native caller CPU excludes
engine worker CPU; counters are not additive ownership partitions. Short16-arm
samples do not qualify p99/SLO. Do not cherry-pick prototype p50 improvement as
production throughput. Preserve/iterate this source-owned slice rather than
silently abandoning it or treating it as the whole gap's solution.

## Independent review and next steps

Original four-file read review18196 terminal124/empty, NOT credited. Reduced
self-contained inline review76284 terminal0; packet/output are
`sdk-owned-scalar-map-inline-review-packet-20261018.md` and matching txt.
Main rejected its budget objection: SDK string-bytes is exactly4*count, not
actual UTF8 bytes, and astral boundary tests already exercise matching counts.
Accepted useful hardening: explicit fixnum-width fence, malformed-resource
fallback test, and comment locating policy guards in native owner. Those are
in0cd4e1a and retested. Followup review81413 TERMINAL0 found no functional
defect; main reconciled assertion floors (initial actual63 +new3 =66), and
does not claim automatic retry/recovery of a cached failed loader. The isolated
factory's malformed-code decline and unaffected scalar fallback are tested.
SDK hosted qualification completed; exporter pin/CI-only review17213 TERMINAL0
with no findings, output `exporter-sdk-scalar-map-pin-review-20261018.txt`.
Main independently verified all old coordinate controls remain. Real old SDK37
override fails the strict native-capability gate for its explicit missing-API
assertion, while ordinary native attributes still pass21/607. This proves
qualification refusal plus maintained fallback, not functional incompatibility.

Stock0.8.23 capability CI and published topic refs are now complete; no local
runtime download or floor bump. SDK PR text is ready for user review at
`sdk-owned-scalar-map-proposed-pr-20261018.md`; submit nothing without resolving
the existing user-review boundary. Installer/source and archive digests are
pinned in resources/otel/ci-toolchain.edn and negative-controlled by2/17 tests.
Public0.8.23 Linux archive digest was read from release API:
90031be43241e61d0788a2f1bbd3da56d8f4324ae9fa87271f9637b43a6071f2.
Installer digest from exact a0ac71c3 source:
737e4f3198f7bb41094b7cb9dc95029ac142694a4d3a829d4dcf64d7676e8a70.
The prior optional canonical-OTel fast-mode question is still unanswered; this
compatible slice does not assume it. Continue larger projection/encoding and
native-confirmation diagnosis; performance target remains open.

Consumer combined PR text is ready at
`exporter-sdk-scalar-map-proposed-pr-20261018.md`, current main e3750302 to
topic5a7617a. It includes the previous5741546 collector slice. Present both
branch diffs and proposed text before any submission; no approval inferred.
Current697f58b isolated BB/JVM helper gates reran7assertions each, terminal0.
All source reviews and both topic CI runs are terminal. No local workload is
live. Main accepted the final e23..5a review's no-findings result after reading
the source delta and actual CI4/20 log. No PR exists; approval of proposed text
is still required before submission, not a blocker to further performance work.

## Actual SDK producer-shape check and next performance targets

Do not infer ended-span representation merely from attributes/normalize returning
a TreeMap. A real SDK tracer/simple-processor/memory-exporter probe ends a span
with16attributes and an event, then cleanly shuts down the provider (69144
TERMINAL0). `sdk-ended-attribute-shape-qualified-20261018.log` proves:

- Ended span attributes are builtin HAMTs: the new SDK fast path actually selects.
- Resource/scope/event attributes are TreeMaps: the new helper intentionally
  declines; values remain equal and ordinary conversion is required.

Initial probe used the wrong shutdown namespace and exited1 AFTER printing
shape facts; it is not credited as a clean probe. The separate corrected run
is the qualification. Trace-decode source also reduces attributes into{}.

Next opportunities: benchmark actual SDK-ended shapes, not only hand-built
HAMTs; profile/optimize sorted-map materialization for resource/scope/event/log
inputs, preserving ordinary fresh-map order and callback behavior. Investigate
normal update-vals/reduce-kv specialization as an upstreamable alternative to
private-layout code. Neither is implemented/measured yet. Larger full-row plus
encoding fusion remains the main combined-allocation opportunity; do not repeat
the rejected compact-row-only/staging experiments or claim this~5ms CPU slice
closes the remaining Rust gap. Keep all accepted cumulative optimizations.

Owned updates:
https://github.com/casselc/otel/issues/50#issuecomment-6250151403
https://github.com/chucklehead-dev/jolt-otel-clickhouse/issues/91#issuecomment-6250151586

Exact266MiB store and obsolete four-source temporary review packet have been
removed after terminal jobs and copy/cmp checks. Sources/scalars remain small.
