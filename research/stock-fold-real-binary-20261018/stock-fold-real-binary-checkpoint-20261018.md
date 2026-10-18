# Real binary sorted-map folding and runtime-root correction

## Verified product and performance evidence

Exporter `perf/stock-fold-json-consumer-20261018` at
`4d48871cf2ac7a2ad0bf6517042c624df376a9bf` is committed, published and clean.
Its only product changes are the optional native JSON pin96f3e47, dependency
controls and changelog. SDK7878a2e, ordinary JSON0f and driver3d remain unchanged;
the existing qualified runtime pin1ae is not promoted or replaced.
CI39150093348 passed all three jobs, including S3 SDK and native JSON backends.

The real standalone local candidate631984d6 (binary SHA64272bbb25330b00481486a52db17b90d6aff27bef84966bdac9bb8d49b3dd22)
passed65 JSON tests/1006 assertions and56 exporter tests/981 assertions plus58
dependency controls, without helper/factory source overlays. The exporter also
passed the same checks on hosted1ae without the new helper; an explicit helper
requirement failed on that older binary as intended. Actual SDK-ended smoke2/12
passed separately. Deterministic workload fixtures are not an OTLP/Langfuse or
real app integration test.

Candidate-only local Durable run:32 measured batches of5000 after3 warmups,
confirmed-window9264.026 rows/s; callback milliseconds p50=510.127,
p90=632.435,p95=670.086,p99=692.213. Independent old-stack reader recovered175000
rows with typed values, timestamps, trace siblings/order and multiplicity checks.
No tail qualification, controlled speedup, hosted S3 throughput or Rust-relative
parity is claimed. Owned731MB store and452MB build scratch were retired after
terminal success and receipt validation; keep the29MB candidate binary as control.

## CI failure, correction and current custody

Producer39147744960 failed at the unchanged DCE root-reference check:
`clojure.core/sm-ops (host/chez/host-table.ss): got #f expected #t`.
Latest-release helper CI39145915016 failed the same check (other jobs must be
checked independently). Runtime tests do not substitute for this gate.

The native predicate interns this private Var outside the app IR graph. Added
it to `dce-runtime-core-roots`; no checker or capability requirement relaxed.
Focused source red=133/134, corrected qualified=134/134, latest0.8.23=130/130.
Native fold79/79 passes on both corrected branches. Claude read-only review70490
completed exit0, no defect; Codex inspected the graph's ordinary data-Var handling.

Published clean branches, exact-head guarded fast-forwards only:

- qualified source7098a8d6fbd1665215e3a24d5ac4bf9a53859101,
  tree571f8df965464e37298a5dce83e16d92127f4c76,
  `perf/qualified-stock-sorted-fold-20261018`, versionv0.8.21-60-g7098a8d6.
- latest-release extraction18dce94a633b798e3e5fafce964e8e42b398a4bb,
  `perf/0823-stock-sorted-fold-20261018`, basev0.8.23a0ac71c3.
- artifact workflow54a3719204ab19f96a297188508968b1bb8182d3,
  `ci/qualified-stock-fold-artifact-20261018`; only five provenance/identity
  values changed from reviewed producere1796a09; actionlint passed.
  Full make ci/shared library/capability gates remain mandatory.

New artifact run39151375518 was authoritatively in_progress at this checkpoint,
exact workflow54a37192, independently pinned compiler7098a8d6. Poll this same
run; do not dispatch a duplicate or cancel another run because observation is
quiet. New artifact name:durable-runtime-stock-fold-7098a8d6-linux-x64.
No new artifact has been downloaded/promoted, no default install or canonical
integration/aspects branch changed. Local benchmark receipts remain631984d6,
not the corrected source7098a8d6. No upstream PR/comment submitted.

## Next bounded work

1. Inspect the exact full-gate run above, including any new failures. A green
   workflow-branch compiler test is not proof of its separately pinned source.
2. After producer success verify archive/provenance/binary and rerun actual
   consumer gates on that binary. Do not relabel the provisional local binary.
3. Make a matched runtime/dependency performance comparison, then integrate
   qualified SDK/exporter/native JSON into the current Oscope candidate rather
   than its stale root checkout. Current Oscope source was73020329 at
   worktrees/oscope-s3-checksum-config-163; refresh remote authoritative state.
4. Continue allocation/encoding hot-path work. Existing RowBinary feasibility
   issue263 and Oct17 explicit-budget probe must be reused, not reinvented:
   28columns,5k batches,4 samples,14,854 rows/s, independent35000-row recovery.
   This is a promising comparison arm, not a qualified speedup or tail result.

No user approval needed to continue owned work. External upstream submissions
still require user review; the latest helper draft now points at18dce94a and
states the failed gate and remaining full-CI/benchmark/standalone gates.

Small logs/scripts/reviews/receipts are preserved in the research worktree's
`research/stock-fold-real-binary-20261018`; no binary, dataset or build scratch
is copied there. No local build, benchmark or review process remains active.
