# Nested sorted JSON performance checkpoint

Goal remains active: real Durable throughput and OTel/Oscope integration.
Previous summary turn was no implementation progress; this turn produced two
new terminal0 experiments changing the next optimization target.

## Completed

- Reconfirmed exact-head CI green for SDK697f58b (run39128818354) and
  exporter5a7617a (run39129795124). No submission approval inferred, no PRs.
- Read service-name942c0cd Claude report: no correctness gaps found. Existing
  source tests remain4/27 green; topic remains local, clean, main-based.
- Finished prior into3 overlay measurement: maps55.100->46.290ms/5k and
  10,220->6,077 B/row, full583.026->584.104ms and69,649->61,362B/row.
  Allocation reduction, **not** full throughput improvement.
- Same-byte event representation discriminator job46433 exited0.
- Unchanged-record direct sorted byte traversal job30135 exited0.
  Full results and limits in nested-sorted-json-issue-20261018.md.

## Next bounded implementation

1. Design/implement a guarded sorted-map traversal for the explicit JSON byte
   backend, preferably runtime-owned so data.json does not duplicate a private
   tree ABI. Do not ship the permissive research htable predicate as-is.
2. Protect actual JSONWriter dispatch, custom comparator/table behavior,
   callback ordering, writer observation/reentrancy/errors. Keep fallback.
3. Measure cumulatively on same SDK-produced shape; separately attribute
   pdata-raw rebuild costs before altering its semantics.
4. Run actual Durable writer + independent recovery after production guards
   and correctness tests, then longer percentiles/hosted S3 qualification.

Independent work: service-name fix publication/CI; into3 general runtime patch
and conformance controls; sorted reduce-kv fix. One heavy job at a time.

No active processes were left by these two experiments; both handles returned
exit0. No stores/downloads/builds were created. Only small source/scalar/log
files are retained. No default Jolt, canonical aspects ref, production SDK,
data.json or exporter source was modified by this turn's experiments.
