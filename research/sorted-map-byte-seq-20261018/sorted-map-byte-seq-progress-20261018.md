# Sorted-map byte sequence implementation checkpoint

## Source and publication

- Base JSON8d557cc18eb9526ed29ae5613089c3793d5973fa.
- Worktree /home/chuck/ai-src/worktrees/data-json-sorted-map-byte-seq-20261018.
- Published branch perf/sorted-map-byte-seq-20261018,
  HEAD a18ae06c787e6e03709bd78f1b92911130a613f1 (implementation0960c8f).
- Clean source worktree, author Chuck Cassel noreply. No PR opened, no merge,
  no dependency/default compiler/canonical aspects ref changed.
- Exact-head CI39134781936 and39134782360 SUCCESS. First includes actual
  qualified native writer, ordinary Jolt and JVM. Second JVM version matrix.
- Proposed text: sorted-map-byte-seq-proposed-pr-20261018.md.

## Implementation and verification

Stock map JSONWriter selected live first; htable receiver stays on byte scalar
emission while using actual public seq/first/nth/next operations. No private
tree/type-tag inspection, forged-op bypass or data-shape replacement. Flush
and sync bracket callback boundaries; key conversion precedes next, which
precedes key validation and child writing. Nondefault options retain existing
portable fallback; per-value dispatch/root and existing row cleanup unchanged.

- Initial existing byte suite36/799 PASS terminal11458=0.
- Combined42/820 PASS terminal45515=0.
- Byte/segmented/focused57/973 PASS terminal15394=0.
- Native38/621 PASS terminal12204=0.
- Expanded focused10/34 PASS terminal37556=0.
- Unchanged baseline red56209=1:7tests22pass3fail0error, all failures are the
  new positive-path mechanism test. Semantic equivalence tests pass baseline.
- Source-instrumented same-output mutant disables new native branch, proving
  output-only equality would not establish optimization selection.
- Native artifact1ae SHA06a2ccb858c072bfcd74ce94d96eb95f008d75e0fb1f46117728602572189811,
  mandatory Chez10.4.1. Version/describe captured in turn. No stock-latest native
  backend qualification claimed, no new compiler build/download.

## Measurements and actual persistence

Same-byte 2k actual SDK-produced spans,16pairs, public sequence implementation:
encoding p50217.346->154.313ms, managed68860->46972B/row. Terminal11359=0.
The report calls the arm :direct-sorted for compatibility with diagnostic
script; :scope :public-seq-sorted-event-byte-walker distinguishes it from the
earlier private-tree upper-bound diagnostic. Production implementation reads
no tree and is not that earlier prototype.

Actual SDK queue/one connection ABBA32batches5k,16perarm,3warmups:
writer completed successfully inside shell13217, shell subsequently exited1
because the original empty-event reader compared numeric insertion values with
timezone-formatted Events.Timestamp query strings. Diagnostic57183=1 isolated
the first mismatch to this wire-vs-physical representation; no writer rerun.
Physical reader16720=1 was a parse error from an escaped backtick, not a native
failure. Corrected reader23016 TERMINAL0 compares the exact stored nanosecond
ticks via arrayMap(toUnixTimestamp64Nano), retaining full-field/multiplicity
equality. Separate old exporter473edbb, old SDK37c684a2 and stock JSON reader
recover ALL175000rows, zero drops/failures, typed values/statuses preserved.
Paired phase/time/row/serial-boundary receipts validated by the bb script.

| phase p50 ms / 5k | baseline | candidate |
|---|---:|---:|
| encoding |713.178|495.295|
| native+confirmation |207.832|215.506|
| whole insert |938.425|727.744|

Whole-body mean949.225->728.532ms. Managed whole-body77237->55398B/row.
~22%p50 whole-insert reduction, ~31%encoding reduction. NOT candidate-only
sustained throughput, p99/SLO, Rust comparison, real tracer-construction cost,
OTLP/Langfuse ingress or hosted S3 qualification. Input is deterministic
SDK-shaped fixture records, not the real tracer-produced component records.

Scalar receipts copied/cmp verified. Exact owned store
/tmp/nested-sorted-sdk-20261018.t00g8KeA (1.4GB) deleted only after terminal
writer/reader+validation; absence verified. Synthetic generated store cannot be
recovered but is reproducible; scripts/scalars/logs remain. No local run left.

## Review status -- do not overclaim

- Initial inline review19506 TERMINAL0. Its claimed custom key/value bugs omit
  the surrounding default-options identity guard; main rejected these against
  actual source and added callback-option tests, which pass. Useful gaps in
  seq/next failures, tagged continuations/list entries were covered.
- Followup23229 TERMINAL0 returned only a request for more source, NO review.
- Complete source packet69581 TERMINAL124/empty, NOT credited. No fourth
  duplicate/retry was started. Final independent review reconciliation remains
  a gate before final PR/merge, not a need for new user authorization.

## Next bounded action

Complete a narrowly scoped independent review with guard context, then own-fork
PR/CI/merge and exporter repin/qualification if approved by evidence. No
external upstream PR authorized. The existing SDK697/exporter5a PR submission
approval boundary remains unresolved and must not be bypassed by this work.
Continue attribution of pdata-raw sorted-map reconstruction, general into3
transient accumulation, and runtime sorted reduce-kv fixes; measure cumulatively.
No Durable protocol transition changed or unchanged exhaustive run requested.
