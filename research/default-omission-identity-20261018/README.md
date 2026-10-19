# Default omission identity: measured local Durable improvement

## Decision and exact refs

The allocation probe found that comparing the known JSON default function
marker with a sorted map causes Jolt to normalize/copy the map before returning
false. The narrow library fix uses identity for the known default marker and
retains structural equality for custom value functions. The README/changelog
explicitly disclose removal of equality-extension effects on default checks.

The library candidate `2172061060a102ccab779f5adc5d77f8b83a81f6` passed
focused red/green, existing native contracts, portable Jolt and JVM tests,
source review and hosted CI. Owned-fork casselc/data.json PR #20 merged at
`cd83ff49a53d9fab4526d1c8fd12bc961b1d367b`; the merge tree matches the tested
candidate tree `356db941d37fd5f26b7f9062c5e86916332d69e4`.

## Real SDK / Durable evidence

`default-omission-sdk-abba-20261018.summary.edn` aggregates four terminal fresh
writer processes in baseline/candidate/candidate/baseline order. Baseline JSON
is `96f3e47`, candidate `2172061`; runtime `7098a8d6`, exporter `4d48871`, SDK
`7878a2e`, driver `3d766fa`, native chDB 26.9.0 are unchanged. Each writer has
160,000 measured rows plus 15,000 warmup rows in 5,000-row rich batches.

- Confirmed-window throughput: 8,429.744 -> 10,304.043 rows/s (+22.2%).
- Estimated allocation: 31,939.683 -> 25,233.243 bytes/row (-21.0%).
- Callback p50/p90/p95/p99: 554.735/721.492/796.887/870.990 ms ->
  442.668/596.403/646.235/680.613 ms.
- Four independent older-stack readers recovered all 700,000 rows, including
  full physical/typed fields, statuses, multiplicity and per-trace ordering.
- Only two process repetitions per arm: not qualified service tails, S3,
  Rust-relative performance or achievement of the 25k/20k objectives.

The separate full encoding ABBA has identical full output bytes/digest and
ordinary/changed-input controls. Its component p99 did not improve. Do not
replace whole-pipeline measurements with that component or invert callback
quantiles into throughput quantiles.

Every successful synthetic Durable store was deleted only after terminal
writer, independent reader and validator success and copied receipt comparison.
The runner records exact owned-prefix/non-symlink guards and absent-store
verification. No bulk store is preserved here; bounded source/scalars suffice.

## Runtime extraction deliberately held

Latest stock 0.8.23 source candidate `388a3ad613f35a519aeefb0b895ff82b17088616`
avoids sorted/function normalization and passes 68 source checks. However,
review plus the included executable counterexample show an extension that
recognizes only normalized plain-map operands changes sorted comparison from
true to false. Do NOT select/pin/promote this candidate without a coherent
extension-compatibility decision. Owned tracking: aspect-packs #182. The
qualified-runtime port `7a56addf` is source-only, unbuilt and unselected.

Initial spy/test/setup failures are retained explicitly, not credited as green
evidence. The final default-omission mechanism tests observe native equality
before JSON loads and have a mandatory positive observer/custom control.

## Consumer integration checkpoint, 2026-10-19

Exporter candidate `4a42716286177fd0c033d69deb708b29dd162933` on
`deps/default-omission-json-20261018` is pushed. The exact candidate passes
56 tests / 981 assertions plus 58 dependency checks. The final cumulative diff
against main `e3750302c44cd10273c6e1277dc3912975cac8f5` spans twelve files;
its complete source-only patch and review packet are included here.

The first cumulative Claude handle disappeared with an empty output file;
that is NOT a review result. A bounded source-only retry is running under
exec session 19572, output in root evidence
`exporter-main-identity-rollup-claude-review-retry-20261019.txt`.
CI workflow 39272493202 was explicitly dispatched because topic pushes do not
trigger the workflow. Refresh actual terminal review/CI states before PR/merge.

The post-fix allocation attribution probe also completed successfully in all
16 no-GC rounds, with ordinary output equivalence. Structural omission
equality calls drop from 1,344 per 64 rows to zero. JSON method-site resolution
still accounts for about 1,236 gross bytes/row and nested string publication
about 1,051 gross bytes/row. The map-fold reading includes recursive emission:
it is NOT evidence that the traversal itself allocates its inclusive total.
These observer timings are not benchmark results. The next attribution should
separate exclusive traversal/dispatch/publication overhead without removing
callback observation or materialization boundaries.

Oscope #168 is already merged at `11feb1f5d009bfbf67ff1622fcb208f127e2a372`
for the preceding stack. After reviewed/green exporter integration, advance
Oscope's root pins and separately qualify its minimal embedded profile (#167).
No upstream PR is authorized without user review. No new exhaustive model run
is needed for these unchanged Durable protocol transitions.

## Integration advanced, 2026-10-19 15:01 UTC

The preceding running-review/CI checkpoint has settled:

- Cumulative Claude retry session 19572 completed, exit 0. Its nonempty report
  and Codex's source-grounded adjudication are now included. Neither suggestion
  established a blocking defect; no blanket exception-swallowing fallback was
  added for arbitrary replacement-helper errors.
- Explicit workflow 39272493202 passed all three jobs. Exporter PR #139's
  separate PR workflows 39273352679 and 39273352266 passed all four checks.
- Exporter #139 MERGED at `d6b6b594c06ecba9fd9a62395e74be9e9730636f` after
  refreshing exact base/head, every check and empty participant state.
  GitHub's merge tree `0f93a196fde018d02268a965ef4e1a0983fbd975` equals
  the tested head's tree and retains tested `4a42716` as a parent.
- Oscope #169 is open at `70e8ca37414938339692ca8e0d814e779508976f` on
  `deps/default-omission-exporter-20261019`, based on current main `11feb1f5`.
  Three files change: dependency pins, exact-source oracles/immediate-prior
  controls and changelog. Its Claude review completed with no defects.
- Oscope root qualification passed 112 tests / 1,586 assertions. Three serial
  real native fixtures completed exit 0. Embedded and standalone observed
  stream calls/JSON companion use and settled independent fresh readers. The
  typed-manifest fixture observes stream/companion use and live typed results
  in-process; it does NOT independently recover in another process.
- Oscope PR CI has six live checks at this checkpoint. Refresh their actual
  outcomes and participants before guarded merge; do not infer success here.
- Root issue exporter #138 was updated at comment 6270181068, with measured
  performance, exact qualification scope and the pushed evidence checkpoint.

No local benchmark or Claude process remains live from this slice. Native
fixtures own their ordinary cleanup; no raw store or build bulk was added to
this checkpoint. Remaining priority after Oscope integration is exclusive
allocation attribution and the next cumulative encoding/row-shaping improvement,
not a reset of the existing performance baseline. S3 performance, the 25k/20k
objective, latest-runtime helper qualification and minimal embedded profile
#167 remain incomplete.
