# Rich telemetry transport comparison: current RowBinary SQL prototype loses

## Exact scope and outcome

Completed runner94387 exit0: four fresh direct serial callback processes in
JSON/RowBinary/RowBinary/JSON order. Same rich nested sorted resource/event
attributes and typed spans as the preceding SDK fixture,625 traces/8 siblings,
5000 rows/batch,3 warmups+32 measured samples each. This comparison omits SDK
queues and OTLP; do not equate its callback rate with the prior SDK-window rate.

Frozen writer graph: actual local binary631984d6 / SHA64272bbb25330b00481486a52db17b90d6aff27bef84966bdac9bb8d49b3dd22,
exporter4d48871, SDK7878a2e, nativeJSON96f3e47, driver3d766fa, libchdb26.9.0 /
SHAa7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557.
No product files/default pins changed; canonical integration/aspects untouched.

JSON retains its real owned compact stream. RowBinary explicitly uses the
existing diagnostic native-open seam to set max_query_size=16MiB BEFORE recovery
on writer and reader; owned streaming is off for that arm, as the driver
requires when open operations are customized. Experimental binary encoder
calls the same production span-row and typed vector projector, with pre/post
plan guards. Base64 is a standalone experimental flat-string procedure, not a
global runtime replacement. Kernel sources are retained. No format/model/API
extension or reusable production RowBinary encoder is claimed.

Pooled descriptive results (two process repetitions/64 measured batches per arm):

| Measurement | JSON stream | RowBinary SQL prototype |
| --- | ---: | ---: |
| Callback rows/s | 8952.3 | 4736.5 |
| Callback p50 ms | 536.5 | 1018.1 |
| Callback p90 ms | 684.9 | 1198.0 |
| Callback p95 ms | 714.6 | 1232.0 |
| Callback p99 ms | 746.0 | 1329.8 |
| Estimated allocated bytes/row | 30685.2 | 83674.2 |
| Projection/encoding phase p50 ms | 341.6 | 526.0 |
| Separate Base64/SQL framing p50 ms | included above | 151.6 |
| Native+confirmation phase p50 ms | 179.0 | 332.4 |

JSON's first phase includes its framing; RowBinary's first two phases must be
considered together. Do not add phase medians and call the result a measured
total. Native+confirmation encompasses the entire execute-owned-and-flush!
call in BOTH arms, including data transfer, classification, native work and WAL
publication. Allocation is the existing delta(live-heap+GC-bytes) estimate.

RowBinary raw payload6713890 bytes expands to8953250-byte ASCII SQL per5k batch.
That exceeds the earlier8MiB query budget, so simply copying the older small-
shape14.9k result was not valid. Even with the flat Base64 kernel, this rich
prototype has higher conversion/framing/native costs. It is not evidence that
RowBinary intrinsically cannot win; it rejects promoting THIS implementation or
assuming the old four-sample result predicts the current rich workload.

Independent older-stack readers1ae/exporter473/SDK37/JSON8d with stock JSON
readback verified175000 rows for every arm,700000 physical rows total. Typed
status/physical types, full values, event ticks, trace siblings and multiplicity
were checked. Kernel known bytes and Base64 tails/length controls also pass.
Positive trials do not cover malformed RowBinary, every supported type/boundary,
concurrent users, larger batches, S3, Langfuse or service p99.

## Initial failure and independent review adjudication

Earlier combined-mode runner16072 exited1 BEFORE ingestion: the unchanged driver
rejects `owned-compact-stream?` combined with `operations/open-native!`. Source,
log and the tiny16KiB diagnostic scratchixWLMhCj are retained. No repeated run of
that unsupported combination and no weakening of the default-operation guard.
The corrected experiment uses separate best-native-path processes instead.

Claude read-only review22057 completed exit0. It confirmed conversion guards,
runtime/pin binding and oracle strengths/limits. Codex REJECTS its assertion
that JSON transfers outside the observed Durable call: product execute-durable-
sql! calls execute-owned-and-flush! BEFORE the writer dispatches the stream
executor, and the latter sends the bytes inside that operation. Counts are35
stream creations per JSON process, not one per process. Raw review preserved;
do not promote the mistaken native-call narrative. Its phase-label caution is
retained explicitly above; timing totals are the primary comparison.

## Next useful work, not new format promises

- Keep the sorted-fold JSON improvement. Do not repin an app to this RowBinary
  prototype or spend priority polishing a losing transport without a new
  measured hypothesis.
- JSON allocation remains concentrated in projection/encoding (~24.3KB/row).
  Profile that and the ~179ms native/WAL phase separately on the current graph.
  Use actual worker-phase counters; caller CPU while waiting is not worker CPU.
- Source observations for later extraction: stock v0.8.23 Base64.encode(byte[])
  materializes a full Unicode String then UTF-8 and another byte array. A byte-
  oriented Base64 path might remove those copies, but is NOT implemented or
  qualified here and is secondary to improving the winning JSON path.
- The RowBinary prototype calls string->utf8 for every String; the current JSON
  encoder already has bounded string caching and direct small-ASCII emission.
  Do not attribute these implementation differences to the wire format alone.
  Any future binary prototype should avoid a full Unicode Base64 SQL string
  and temporary per-string buffers BEFORE another end-to-end comparison.

## Retention and live custody

Runner/writer SHA matched pre-launch receipts; frozen scripts were not edited
while running. After exact terminal0, aggregate validation and byte-identical
copied writer/reader receipts, retired only the four task-created reproducible
storesURc6H4kD/o7P1cQBD/8hMLdRt6/VjexaJrM under
`/tmp/rowbinary-rich-direct-abba-20261018.`. Absence verified; small logs, raw
scalars, source and review remain. No VHD trim/compaction requested/performed.

No local benchmark/build/review process remains live. Remote artifact39151375518
(workflow54a37192 -> compiler7098a8d6) and latest-release extraction tests39151345065
(source18dce94a) were still authoritatively in_progress at the last check; poll
the same handles, never duplicate because observation is quiet. No runtime
artifact has been promoted and no upstream PR/comment has been submitted.
