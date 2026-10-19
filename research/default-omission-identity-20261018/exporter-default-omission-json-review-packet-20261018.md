# Read-only exporter dependency review

Review exact commit4a42716286177fd0c033d69deb708b29dd162933 in
/home/chuck/ai-src/worktrees/exporter-default-omission-json-20261018 against
parent4d48871cf2ac7a2ad0bf6517042c624df376a9bf in
/home/chuck/ai-src/worktrees/exporter-stock-fold-json-consumer-20261018.
Only3files: deps.edn, CHANGELOG.md,
test/otel/exporter/chdb_dependency_test.clj. Read those files only in both
worktrees. No commands/edits/credentials/env files/stores/telemetry/other repos/
delegation/publication. <=400words, concrete defects or missing controls.

The optional :native-json-byte-batch alias moves96f3e47 ->
2172061060a102ccab779f5adc5d77f8b83a81f6. Ordinary JSON0f51b991, SDK7878a2e,
driver3d766fa and runtime artifact/default settings remain unchanged. Source
root oracle expects the new exact SHA and rejects the actual prior96f pin,
plus older pins8d557cc and a0172a0. No alias override should shadow the mutation.
No new native backend is enabled by default.

The JSON change was separately reviewed: default sentinel uses identity,
custom :value-fn equality/fallback remains; normal writer/key callbacks and
reader semantics unchanged. Intentional removal of structural-equality effects
from that DEFAULT marker test is documented in its README/changelog. It is not
an arbitrary equality-runtime rewrite. Exact JSON head CI39160924289 and
39160924885 are green, including qualified native, Jolt0.8.3 and JVM matrix.

This exact exporter source passed56focused tests/981assertions and58dependency
controls with the actual git dependency on qualified runtime7098. All four
actual SDK/local Durable runs (baseline/candidate/candidate/baseline,35*5000rows
each) passed an independent old-stack fresh reader;700000rows verified.
Matched confirmed-window throughput8429.7->10304.0rows/s, estimated allocation
31939.7->25233.2B/row. Two process repetitions per arm, not S3/service-tail/
Rust-relative qualification. Review source, not those performance claims.
