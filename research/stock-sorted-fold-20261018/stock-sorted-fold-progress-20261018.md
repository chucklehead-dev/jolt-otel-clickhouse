# Stock sorted-map fold: source extraction and performance checkpoint

Previous goal turn made progress: negative closure probe plus exact sequence
boundary accounting changed the optimization choice. This turn implemented,
measured, reviewed, committed and published the runtime-owned alternative.
Goal remains active and end-to-end performance/integration targets are NOT met.

## Current authoritative source

- Jolt latest release refreshed via official release API: v0.8.23, published
  2026-10-17T19:41:22Z, base a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5.
- New clean/published topic `perf/0823-stock-sorted-fold-20261018`, worktree
  `/home/chuck/ai-src/worktrees/jolt-0823-stock-sorted-fold-20261018`, HEAD
  646fcfcbb1e8bcda33f0a4b9e40d32db0ffcd222. Implementation fc6d0678; follow-up
  only bounds unsupported-input diagnostics and tests that they retain no input.
- New clean/published JSON topic `perf/optional-stock-sorted-fold-20261018`, WT
  `/home/chuck/ai-src/worktrees/data-json-optional-stock-sorted-fold-20261018`, HEAD
  96f3e47dba5970728866dacb5ac41a25135a2df5. Implementation25d30c4, CI6305f3d,
  final CI-outcome/helper-pin follow-up96f3e47. Base a18ae06.
- No PR opened or merged, upstream or owned. Canonical integration/aspects,
  installed/default Jolt, exporter a1, SDK7878 and driver3d unchanged. Native
  measurements still use qualified1ae artifact/Chez10.4.1 with explicit source
  overlays. Merely repinning JSON on that old artifact will NOT enable the fold.

## Implementation and trust

Runtime owns native map admission/tree layout. `stock-sorted-map?` compares
the actual stock ops table through the defining core's interned private Var
cell; no custom operation trial call. Generic native fold captures the root
before callbacks, preserves structural seq order, no comparator, no entry/seq
objects, native visitor(k,v,acc), not Reduced-aware. Ordinary private-core/
immutable-internals trust applies; no forged-node or concurrent mutation seal.

JSON optionally captures both runtime helpers with procedure/arity fences.
After selecting the live stock Map method, known stock maps use runtime fold;
unknown/custom operations keep original public-sequence publication/sync.
Children retain live dispatch, prefix reads/edits, exceptions and reentry.
NO private tree layout added to data.json. No SQL/WAL/lease/ACK change.

## Validation and reviews

- Actual v0.8.23 native source gate79/79, existing sorted suite58/58. Kernel
  constructor trap is checked live: seq creates65 entries, fold0. Exception
  identity/prefix, custom comparator, custom ops and nested fold checks pass.
- Full `make sortedcoll` invocation was blocked before tests by Makes bootstrap
  network policy; NOT credited. Ran both exact source recipe commands directly
  through mandatory Chez wrapper instead. No binary or seed rebuild.
- New worktree runtime submodules initialized at exact pins. First init was
  interrupted by local irregex mirror transport policy; completed with narrowly
  permitted local transport. Only task-created empty clone indexes restored to
  their exact pins; no older worktree/vendor state touched. New tree clean.
- Source-overlay consumer final65/1006 passes, actual helper absent60/982,
  separate ordinary native38/621. actionlint passes. Exact final workflow overlay
  command was parsed from YAML and executed locally:65/1006, entered/complete/
  passed true, exit0. Source checkout location alone replaced with local path.
- Review of bounded diagnostic found no ordering defect; requested clearer empty
  return convention, bounds and positive anchor controls. Added geometry/anchor
  attestation and4/42 tests (some initially used weaker some? predicate, hardened).
- Generic extraction read-only review found no concrete defect; missing wrong
  arity controls added (strict suite becomes5/24). Reviewer read additional core
  interning source to assess image identity; source inspection is NOT live image
  qualification. Runtime image/binary qualification remains pending.
- CI review found no syntax/order/disclosure defect. Complete markers are not
  pass proofs; final source now explicitly prints passed bool then exits accordingly.

## Important invocation correction

`-A:jolt-native-test -M script.clj` ran that alias's native-test main rather than
the requested script on qualified1ae. Initial reports38/621 are REAL native suite
passes, not requested byte/sorted/segmented/script qualification. Earlier closure
probe and this turn's first regression labels must be read with that correction.
Benchmarks/effects/Durable drivers did NOT use that alias, so their execution is
unaffected. Explicit path-only commands now require exact per-namespace counts
and entry/completion markers. No claim that prior hosted -A ... -e CI was broken;
current hosted logs explicitly confirm60/982 and65/1006 scopes.

## Performance: do not mix separately scheduled runs

First BOUNDED diagnostic (not generic product implementation): actual SDK2k,
24 pairs, full same bytes, full p50131.636->108.248ms,30.3->23.1KB/row.
Its local queue/Durable5k16/arm median620.101->545.746ms; mean614.616->587.343;
upper observations WORSE.175k independent recovery. Not a p99/SLO improvement.

Actual GENERIC source implementation fc6d0678/JSON25d30c4, qualified1ae overlay:

| same-byte SDK2k component | baseline | candidate |
|---|---:|---:|
| p50 ms |128.759|118.541|
| caller CPU sum seconds |3.384|3.006|
| managed B/row |30306.65|23136.76|

Actual same-queue/connection LOCAL Durable5k ABBA,16 observations/arm:

| phase | baseline p50 ms | candidate p50 ms |
|---|---:|---:|
| full insert |547.308|524.868|
| projection/encoding |356.702|326.853|
| native+confirmation |177.121|173.641|

Full means575.516->544.847ms (~5.3%); median~4.1%. Full managed allocation
38666->31502B/row (~18.5%); encoding32199->25030. Full p90 688.321->663.328;
p95/p99 observed723.048->695.331 (16 samples NOT tail qualification).
Independent OLD exporter473/SDK37/JSON8d reader verified all175000 full physical
rows, typed status/values, multiplicity and exact nanosecond event ticks.
Not sustained throughput, hosted S3, real-app or Rust-relative qualification.
The raw-ticks component and actual exporter-controlled timestamp wire need not
have identical costs. Do not multiply gains from different runs or conflate
first bounded diagnostic with later generic implementation.

Both owned747MB stores deleted after terminal0 writer/reader, validation and
receipt cmp: qualified.9H8LFuAF and runtime.2ESUJMF1; absence checked. Initial
failed driver hit namespace alias collision before creating any data; empty4KB
rhWEuXMS directory removed with rmdir after terminal1. Logs retained, not passes.

## Hosted CI custody

- JSON6305f3d: compatibility39145448570 SUCCESS all3jobs, test39145449419 SUCCESS.
  Job117728230869 actual logs confirm60/982 +65/1006 markers. Final96f3e47 refresh
  now independently confirmed SUCCESS: compatibility39146080863 and
  Test39146081324. Not inferred from previous CI.
- Joltfc6d0678 flake39144987602 SUCCESS; tests39144987546 still live last poll.
- Jolt646fcfcb flake39145914892 SUCCESS; tests39145915016 confirmed in_progress.
  Do not cancel/restart either previous or final live runs from quiet output.

## Next bounded actions

1. Refresh final exact-head Jolt/JSON CI. Review branches/text prepared, no PR
   submission inferred from automatic goal continuation or historical approval.
2. Make helper available in a QUALIFIED runtime artifact; preserve canonical
   integration/aspects and all existing callback-domain/interop/JSON work. No
   entire aspect compiler rebase merely for version currency. Then exporter
   JSON pin and Oscope integration; current app pins are unchanged.
3. Prioritize a larger residual-cost opportunity after this measured fold. New
   v0.8.23 direct-linking for loaded code is a candidate, NOT yet measured here:
   preserve live policy/redefinition boundaries via explicit gates, not blind
   optimization flags. Component phase timestamps/wrappers remain candidates,
   not established hotspots. Don't keep repeating rejected closure/lock probes.
4. Longer local+hosted-S3 matched workload/batch/percentile qualification and
   Rust-relative comparison still needed. 50/10Mbps DSL cannot prove hosted S3
   limits. Keep one local heavy workload/one Claude; delete generated stores
   after recovery and retain scalar sources/proof. No changed protocol/model,
   so no unchanged exhaustive model run was started.

PR drafts: jolt-0823-stock-sorted-fold-proposed-pr-20261018.md and
data-json-stock-sorted-fold-proposed-pr-20261018.md. Upstream draft is focused on
generic native-consumer relevance, not application-specific performance. Full
CONTRIBUTING read; benchmark/standalone gates remain before upstream readiness.

Public issue update:
https://github.com/chucklehead-dev/jolt-otel-clickhouse/issues/138#issuecomment-6252455944
Participants rechecked owner-only before posting. Its pending-CI wording is a
point-in-time snapshot; final results above were refreshed afterward.
