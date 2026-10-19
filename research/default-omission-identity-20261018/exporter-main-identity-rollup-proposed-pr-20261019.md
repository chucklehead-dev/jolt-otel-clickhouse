## Summary

Reduce temporary allocation while shaping and encoding SDK span attributes in
the optional native JSON backend. Ordinary JSON remains unchanged.

- Use the SDK's optional scalar-map helpers for built-in HAMT and sorted maps
  while the original conversion policies remain installed.
- Avoid redundant declared-string-key normalization under the defining
  namespace's original policies; retain the ordered generic path otherwise.
- Update the native JSON dependency to retain sorted-map byte emission, use
  optional runtime-owned traversal, and compare its known default omission
  marker by identity rather than rebuilding nested maps to compare to a function.
- Preserve live callback/writer behavior, typed source values, descriptor
  provenance, field statuses, payload limits and confirmed Durable semantics.
  Older SDK/runtime capabilities and custom operations retain fallback paths.
- Add changelog entries, dependency-source rejection controls and CI coverage.

## Validation

- Exact head `4a42716286177fd0c033d69deb708b29dd162933`: 56 focused tests /
  981 assertions and 58 dependency checks on the qualified runtime.
- New omission mechanism has an old-source red/new-source green control.
  Custom callback equality retains its positive control.
- Four fresh SDK-to-local-Durable writer processes, matched ABBA with 5,000-row
  rich batches, and four independent older-stack readers: all 700,000 rows
  recovered with full field/value/status checks and no drops or export failures.
- The final JSON update, measured against its immediate predecessor with the
  same SDK/exporter/runtime, improves confirmed-window throughput from 8,430 to
  10,304 rows/s and estimated allocation from 31.9 to 25.2 KB/row. This is a
  bounded local comparison, not S3 or sustained tail qualification and not an
  attribution of the entire cumulative rollup to a single change.

The cumulative source review and hosted workflow results must be checked before
merge. No runtime artifact, backend default, ordinary JSON pin or driver changes
are included.
