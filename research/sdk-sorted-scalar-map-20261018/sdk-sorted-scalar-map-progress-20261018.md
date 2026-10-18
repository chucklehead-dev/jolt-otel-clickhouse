# SDK sorted scalar normalization checkpoint

## Source custody

- SDK `perf/sdk-sorted-scalar-map-20261018`, HEAD
  `7878a2eb4340519d6843d13490a5a7c99ebade8a`, parent topic `697f58b`.
- Exporter `perf/sdk-sorted-scalar-consumer-20261018`, HEAD
  `a1bedfcc50d84dd59597fe12dec144231b282920`, parent topic `20999f9`.
- Both committed, published, clean when refreshed. No PR opened or merged.
- JSON native dependency `a18ae06`, ordinary JSON `0f51b99`, driver `3d766fa`
  unchanged by this slice. Local performance runtime is qualified `1ae7e0d`
  with mandatory Chez 10.4.1, not stock 0.8.23 end-to-end qualification.

## Contract and correctness

New optional SDK `try-sorted-scalar-string-map` produces a fresh plain map for
bounded stock sorted maps with intrinsic string keys, strings, Booleans and
native fixnums. Existing map-kind-preserving helper is unchanged. Unknown
operations, unsupported values, >128 nodes and changed SDK scalar policy
decline before invoking custom operations/comparators. Generic fallback remains.
Six defining-SDK scalar roots are checked before/after pure bounded admission;
this is NOT an atomic cross-thread redefinition lock. Core/private runtime roots
remain trusted. Stock operation table is intentionally frozen.

Independent three-marker geometry probe checks node slots and exact traversal
order, beyond wrapper UID fences. Source mutation reversing a child walk must
fail loading. Exporter checks its own live converters before/after each helper
individually; a late change must not invoke a second helper or accept stale data.
No WAL bytes, lease, ACK, schema or Durable state transition changed.

## Validation

- SDK strict native 6 tests / 78 assertions; aggregate 471 / 4089, terminal 0.
- Isolated BB/JVM helper-decline checks pass. Initial JVM cache-permission
  failure preceded evaluation; qualified retry with writable CLJ_CACHE passed.
  This is not full SDK/codec portability qualification.
- Exporter focused 5 / 21, wider native attributes/projection 54 / 969 pass.
- SDK exact-head CI 39141384747 passed, including checksum-pinned stock Jolt
  0.8.23 helper capability lane. No claim that full native JSON works on stock.
- Exporter exact-head CI 39141486504 freshly confirmed SUCCESS at a1bedfc;
  native-json-backends, scoped-s3-sdk and test all passed. S3 correctness gates
  are not hosted throughput qualification.
- SDK source review and follow-up completed. Reviewer withdrew an invented
  stock-ops reset API finding after scope clarification; no functional defect
  remained. Consumer review text completed, no concrete regressions found in
  inline source. Its SDK-side trust boundary was reviewed separately.

## Actual queue/Durable result

Same queue/connection, 32 ABBA batches of 5000 (16 per arm), helper enabled vs
disabled. Independent process using older exporter473 / SDK37 / JSON8d recovers
175000 physical rows including warmups, exact typed values/status, multiplicity
and nanosecond event ticks. Validation receipt says verified true.

| phase | baseline p50 ms | candidate p50 ms |
|---|---:|---:|
| full insert |615.315|565.247|
| projection/encoding |424.204|372.895|
| native plus confirmation |177.854|176.123|

Full managed allocation 55381.9 -> 38711.6 B/row; encoding 48913.1 -> 32242.8.
Full means 645.428 -> 580.205 ms. p90 737.160 -> 693.189; p95/p99
764.716 -> 694.606, but with only 16 observations these are NOT qualified tails.
This event-rich shape is not the earlier simpler 25k+ workload. Do not multiply
gains or compare absolute timing across separately scheduled prior experiments.

Exact owned store `/tmp/sdk-sorted-scalar-map-20261018.Tu67i8S8` (747MB) removed
after terminal writer/reader, scalar receipt cmp and independent validation;
absence reverified. Keep small scripts, receipts and reviews only.

## Next actions

1. Prepare SDK/consumer PR descriptions and show branches/text; do not infer
   submission permission from automatic continuation.
2. Qualify older-SDK fallback/missing-capability and dependency-oracle controls
   locally if additional coverage is needed beyond green exact-head CI.
3. Remaining encoding/projection dominates. Investigate sorted sequence writer
   boundary overhead without bypassing custom operations or writer visibility.
4. Longer candidate-only local and hosted-S3 percentile qualification, then
   Oscope pin/integration. Local DSL uploads cannot establish hosted S3 limits.
