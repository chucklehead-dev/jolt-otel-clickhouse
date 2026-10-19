# Singleton event-column allocation reduction

Candidate `2c54b98d35464c8fc7c737b0c92a0042272b589a`, parent main
`d6b6b594c06ecba9fd9a62395e74be9e9730636f`, branch
`perf/singleton-event-columns-20261019`. Owned exporter PR #140 is open.
The comparator is tested `4a42716`, whose tree exactly equals merge d6b6b59.
No production source in that baseline differs from the new main parent.

Only immutable singleton-vector event-column construction changes: timestamp,
name and attrs stay in original order, with fresh result vectors but without
three transient mapv builders. Empty/list/lazy/multi-event behavior is retained.
Event JSON and Durable protocol/acknowledgements are unchanged. Five-file
source/test/CI/changelog patch is included. Focused old source passes 33 and
fails exactly one mechanism assertion (three mapv calls vs zero); new source
passes 3 tests / 34 assertions. Wider tests: 43 / 317. Claude session22724
completed exit0, no functional defects. Main inspected live callback order,
failure propagation and immutable-vector assumptions. Its broad claim of no
coverage gaps is not treated as a universal proof: exotic mutable vectors and
arbitrary private/core mutation are outside that reasoning, and custom lookup
records/subvectors were not independently runtime-tested by that review.

## Matched actual SDK / local Durable ABBA

Runner41090 completed exit0. Four fresh writers, baseline/candidate/candidate/
baseline, 5,000-row rich nested batches, 3 warmup and 32 measured exports each.
Same qualified runtime7098, SDK7878, actual git JSON217, driver3d766fa and native
26.9.0. Source-resource checks bind the actual exporter and JSON on every arm.
No public/native callback override or benchmark-source edit during execution.

| Measurement | Baseline4a | Candidate2c |
| --- | ---: | ---: |
| Confirmed-window rows/s | 10,428.205 | 10,425.845 |
| Estimated allocated bytes/row | 25,233.297 | 24,391.602 |
| Callback p50 ms | 446.334 | 448.452 |
| p90 ms | 588.808 | 582.912 |
| p95 ms | 621.130 | 611.889 |
| p99 ms | 666.110 | 665.596 |

Throughput is flat, allocation down ~3.3%. Candidate per-process rates differ
11,311.8 vs 9,668.6: do not cherry-pick the faster arm or declare sustained tails.
The two repetitions/64 measured callbacks per arm do not qualify service p99,
S3 or Rust-relative performance. Targets 25k/20k are NOT achieved.

Every writer has 160,000 measured rows plus15,000 warmup rows, 35 actual native
stream calls, no dropped/failed exports and serial per-trace callback order.
Four independent OLD-stack readers (runtime1ae/exporter473/SDK37/JSON8d) recover
all700,000 rows, including full physical/typed values/statuses and multiplicity.
Receipts copied and cmp-verified before runner validator and exact guarded
synthetic-store retirement. All stores WInoLSfP/GQ3ad2tF/joAqCLg0/qkuppIaP
under /tmp/exporter-singleton-sdk-abba-20261019.* were removed after terminal
success; no raw store is retained. No other /tmp or user data was deleted.

## Separate component result

Four fresh full-encoding processes also match complete 8,648,398-byte output
and digest plus ordinary reference / changed-input controls. Lazy encoding p50
230.579 ->225.500 ms; estimated allocation17,228.262 ->16,380.214 B/row.
Component upper quantiles worsen. This small screen is not substituted for the
flat whole-pipeline result. Candidate is proposed as an allocation reduction,
not a demonstrated throughput improvement. Cumulative earlier wins remain.

## GLM source audit and next target

Read-only GLM audit session ses_eab437866ffeDU9IbvEfTShGoV / exec77897 completed
exit0 on latest stock v0.8.23 source at18dce94a (basea0ac71c3). Raw report and
main's bounded owned issue #176 follow-up are included. Unary mapv already has
raw-vector leaf reduction behind a seq wrapper. Suggested direct-pvec reduce
entry would remove that wrapper only, not all transient/closure/trim setup;
reduced/init/empty/interface semantics need real controls before any patch.
No core source/seed/binary or default Jolt install was modified.

Next larger CPU hypothesis: raw-string!'s common JSON quote/backslash/slash
escape currently leaves its local cursor, updates captured used, and invokes
byte! twice. A bounded two-byte local-cursor fast path could avoid those calls
for nested EventsJSON strings while retaining the scalar boundary fallback.
This is unimplemented and unmeasured. Any experiment must cover buffer remaining
0/1/2 bytes, exact logical budgets, Unicode/control escapes, custom writer
visibility/reentry, output hashes and the same full SDK/Durable comparison.
Do not treat a source-loop observation as evidence of a performance win.

## Live custody at checkpoint, 2026-10-19 15:28 UTC

- No local benchmark/review/build remains running from this batch.
- Exporter PR #140 head2c54b98 has four normal PR CI jobs queued; refresh exact
  head/base/checks and participants before guarded merge.
- Oscope #169 head70e8ca3 still has linux-x86-64 running; the other five checks
  passed. The last verified step is crash recovery through pinned MinIO in
  workflow39274111250, started15:22:28Z. No cancellation/restart was requested.
- A premature explicit exporter workflow dispatch returned422 before the
  branch push completed, so created no run. The ordinary PR workflow is the
  only intended CI for #140; do not duplicate it.
- New publication candidate654c1f9 was not selected (negative screen retained
  separately). Its timed-out empty Claude output is not review evidence.
- Nothing needs user approval for these owned-repo continuations. Upstream
  submission still requires user review; none was opened.
