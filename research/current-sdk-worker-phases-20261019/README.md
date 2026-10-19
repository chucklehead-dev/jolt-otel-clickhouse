# Current correlated SDK-worker phase attribution

Exact selected source graph: runtime7098 (hosted, pinned Chez10.4.1), exporter
`2c54b98d35464c8fc7c737b0c92a0042272b589a`, SDK7878, JSON217, driver3d766fa,
native26.9.0. The supporting fixture worktree3fd96c4 is AHEAD of driver3d in
multiple production files: it supplies test/bench fixtures ONLY. Do not audit or
patch its current production source assuming it is the selected driver.

## Observer failure detected and fixed, not hidden

Initial run45940 exited1: the direct-callback observer counted both the SDK's
borrowed reify and the underlying actual exporter protocol invocation. The
expected35 actual exports therefore hit its guard after16 measured exports,
not after32. The processor reported80,000 successful and80,000 failed measured
spans; this is NOT a successful benchmark or a library delivery regression.

The observation unit assumption was wrong: one protocol entry is not always one
semantic export. The guard caught it instead of silently double-counting phases.
The corrected observer admits the actual ChdbExporter, checks a single owner
identity, and separately requires35 owner entries (3 warmup+32 measured) plus
32 ignored wrapper entries. It does not merely increase the counter limit.

The failed synthetic root79ZrfgFc was retained until independent old-stack
recovery verified ALL95,000 acknowledged rows (16 measured+3 warmup batches),
all physical/typed values/statuses and19 copies per source row, with progress
matching80k successes/80k failures/no queue drops. Reader74780 exit0. Scalar
progress and partial-reader receipts were copied/cmp-checked before deleting
that201MiB task-owned store. The source/logs remain here; no forged full-writer
receipt or complete-profile claim was created for the failed run.

## Corrected real SDK profile

Runner63494 completed exit0. Thirty-two measured5000-row callbacks, one serial
SDK worker and a distinct native writer actor, with3 warmups. All14 phase labels
have32 complete records with exact ordinals0..31. All175,000 rows independently
recover through OLD runtime1ae/exporter473/SDK37/JSON8d, full field/type/status/
multiplicity checks. Copied/cmp receipts and validator pass; owned store5ncOIxUT
was retired by the runner. No bulk store remains from either profile attempt.

Correlating API durations WITHIN EACH callback, not subtracting phase medians:

- projection/encoding is57.25% of summed measured callback time;
- native execution plus confirmation is39.68%;
- other callback work is3.07%.

The encoder and outer callback execute on Thread-3; native stream/WAL/control
phases on Thread-1. Instrumented callback p50 is866.8ms, encoding492.9ms,
native/confirm349.0ms. These timings are perturbed and slower than nearby normal
runs. They are NOT service rates, architectural bounds or a new regression claim.
Nested file-digest/immutable-put/verify/head-CAS costs overlap their parents;
do not add their medians. Raw counters use non-atomic host statistics samples;
thread names do not provide whole ClickHouse worker CPU accounting.

## Bounded GLM settings inventory, main adjudication

Read-only GLM exec18739 completed exit0 in clean detached exact-driver3d worktree
`chdb-selected-native-settings-audit-20261019`. No source/seed, native setting,
build or external publication was changed. Its raw report is included.

Main verified open-spec! takes only path, native bootstrap exposes only
backups-allowed-path, and the closed owned compact header admits only the
raw-ticks setting. Public raw stream-insert! can pass caller query text, but
that is NOT a qualified Durable-owned API for arbitrary tuning settings.

Reject the report's stronger "works/proven" wording for max_threads,
max_insert_threads and input_format_parallel_parsing: the cited simulated and
native tests prove ONLY raw-ticks header passthrough/non-session retention,
not those three settings or their performance effect. Prior SET on a raw
connection is likewise not qualified through the Durable adapter. Also the
selected binding's default package remains26.7.3; these experiments explicitly
select and hash-verify26.9.0 via JOLT_CHDB_LIB. Do not confuse that with a default
installer upgrade. No custom-open guard may be weakened or FFI header silently
changed relative to its persisted canonical SQL for a performance claim.

Next bounded work: inspect existing setting experiments, then a matched
native-only settings/parse comparison on the same rich payload, with setting
readback and exact data checks, before adding any supported closed-header/API
extension. In parallel, encoding remains the largest observed region; a
counterfactual immutable resource/event attribute reuse screen could bound
repeated conversion work, but would NOT itself establish callback/cache
compatibility or authorize a production cache.

## Integration custody

Exporter #140 merged as0830f303d2a6be8b0ca1f2e01695bdb2ddac2c30 after exact
head/base/participants and all4 PR checks passed. Merge tree
b7018615493c99c5f538c4de94ef5b18ed26c3d9 equals tested2c.
Oscope #169 merged as57a8597e11d6d123137a5b67a3f0f2d5748bed9c after all6 checks
passed. Tested head70e8ca3 treef9d8af048e0d9adf96f72c69862d91f425dd6c26;
verify merge tree via GitHub before claiming equality. Its root pins are
exporter4a /JSON217; it does NOT yet include the later singleton exporter2c.
The minimal embedded profile is separately tracked (#167).

Earlier CI endpoints temporarily disagreed about run/job completion; no guard
was bypassed, check manually marked green or live run restarted/canceled.
Both PR merges waited for explicit terminal success on every exact-head check.
No local heavy job, Claude or GLM task remains running from this batch. Further
app repin, native/settings investigation, larger encoding wins, S3 performance
and25k/20k goals remain open. Upstream submission still requires user review.
