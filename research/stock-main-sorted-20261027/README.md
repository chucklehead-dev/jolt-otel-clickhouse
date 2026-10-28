# Qualifying Jolt's fixed-field sorted collections

This is a compatibility/performance investigation, not a throughput result or
an instruction to upgrade an installed compiler. No compiler-fork patch or
default exporter setting changes are involved.

## Why this slice

Current upstream Jolt main `8ba11735f581c458f0a8c8146c170de2e81a6d41`
uses immutable `chez-scoll-v1` sorted wrappers (a subtype of `chez-htable-v2`)
with fixed fields and native five-slot vector tree nodes. The earlier stock
0.8.23 layout uses tagged host tables and persistent-vector nodes. This avoids
some runtime bookkeeping we had been considering optimizing ourselves.

The existing optional library correctly declined the new layout. This change
qualifies the two known representations explicitly rather than removing its
ABI guard. Native node access still follows type/size/count bounds; scalar
limits, key schemas, live policy fences, retention budgets and byte semantics
remain in force. No publication/ownership/settlement contract is changed.

The SDK also stamps sorted root-cache entries with their source wrapper's
record descriptor. Tree/operations/count identity alone is not sufficient:
a forged legacy tagged table can share all three with a valid native sorted
wrapper. That input must not replay the valid wrapper's cached output.

## Exact implementation checkpoints

- Exporter/codec: `f9ac949fb1fb7a54620ae7550336b0581a123d22`, parent
  `e5190e8573328ddc03912da227e5f48d00bab6d8`.
- SDK: `02a642a9f9efdd07eff495b8742f7b7b4825168b`, parent
  `8cd99fe4d2333e7ac5b1a2fd5aab009bd05acfc0`.
- Selected driver: `312812f0dfeff5498a6d1aa29b0476a97d8b65f3`.
- Ordinary JSON: `0f51b99101bc5e840f957c073f87b6f877309a25`;
  stock crypto: `5effcc89a3258499a79a2a3d69edad9e7800d1bf`.
- Native chDB: 26.9.0; mandatory local Chez: 10.4.1.

The upstream source checkout is clean and isolated. Neither the installed Jolt
nor the canonical `integration/aspects` compiler branch is modified.

## Completed local evidence

Evidence directory: `evidence/stock-main-sorted-20261027.oPG5Z0qr` in the parent
workspace. Source-mode checks are not standalone or performance qualification.

| Gate | Upstream main 8ba11735 | Official stock 0.8.23 |
| --- | --- | --- |
| Final SDK sorted conversion/projector, codec shape/view, traversal suite | 29 tests / 510 assertions, pass | 29 tests / 486 assertions, pass |
| Injected-folder payload cache suite | 19 tests / 162 assertions, pass | Not rerun in this slice |
| Separate traversal-only namespace gate | 5 tests / 51 assertions, pass | Covered by combined suite |

The assertion-count differences are runtime-specific controls, not missing
failures: the new immutable wrapper rejects public mutation, and native vector
malformations/forged-wrapper controls run on that representation. Legacy
malformed-node, operation/count corruption and vector-slice controls remain.

Two source mutation controls are load-bearing:

1. Alter the actual SDK geometry probe's left traversal into a second right
   traversal: the original loads, the mutated loader rejects.
2. Remove only the wrapper-descriptor cache check: the isolated mutant returns
   a stale hit for the forged wrapper; the real cache rejects it.

The real SDK queue/overlap source smoke confirmed three batches of eight rows.
An independent older driver/SDK/ordinary-JSON reader graph on official 0.8.23
recovered all 24 rows against an ordinary JSONEachRow oracle, comparing every
physical field/type, exact timestamp values and per-row multiplicity.

Claude's bounded read-only source review completed successfully and reported
no blockers. It did not run code and did not inspect the traversal kernel;
Codex's separate traversal and combined executable gates cover that boundary.
Its prose overgeneralizes constructor pinning: the root cache stores the
admitted source's descriptor at insertion and compares it at lookup; unlike
the converters/plans, it does not capture a trusted sorted prototype itself.

## Remaining performance gate

Update: the interrupted build was recovered through lower-memory object
embedding. A first release screen and independent recovery are now complete;
see [results and remaining matched-control gate](RELEASE-SCREEN.md). The target
remains unmet. The paragraph below describes the earlier checkpoint.

One standalone build from the exact upstream main source is in progress at
this checkpoint. Before any throughput claim: standalone capability/functional
smoke, then an isolated release measurement with p50/p90/p95/p99 and independent
physical recovery. Compare the same canonical SDK/codec graph on both runtimes;
do not compare only against the unadopted HAMT prototype or treat old historical
22.4k rows/s as a current matched control. No new 25k/20k target claim is made.

Earlier HAMT and fused-probe experiments remain unadopted. They must not become
dependencies of this compatibility slice.
