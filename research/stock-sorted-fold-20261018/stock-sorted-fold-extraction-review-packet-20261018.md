# Review: generic runtime extraction and optional native JSON consumer

Read-only source review, <=1000 words. Do not edit, execute commands, delegate,
fetch, publish, inspect environment/credentials or read other project data.
Only the following source files are in scope:

- `/home/chuck/ai-src/worktrees/jolt-0823-stock-sorted-fold-20261018/host/chez/host-table.ss`
  lines 145-263 (stock seq and new native helpers; other lines only if necessary).
- `/home/chuck/ai-src/worktrees/jolt-0823-stock-sorted-fold-20261018/test/chez/sorted-map-fold-test.ss`.
- `/home/chuck/ai-src/worktrees/data-json-optional-stock-sorted-fold-20261018/src/main/clojure/clojure/data/json/jolt_byte_writer.ss`.
- `/home/chuck/ai-src/worktrees/data-json-optional-stock-sorted-fold-20261018/src/test/jolt/clojure/data/json_stock_sorted_fold_test.clj`.
- `/home/chuck/ai-src/worktrees/data-json-optional-stock-sorted-fold-20261018/src/test/jolt/clojure/data/json_sorted_byte_test.clj`.
- If needed for Var-cell boot/restore identity only, `host/chez/state-image.ss`
  and `host/chez/vars.ss` in the Jolt worktree. Do not read unlisted files.

Jolt base is latest stable v0.8.23/a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5.
JSON base a18ae06c787e6e03709bd78f1b92911130a613f1. Owned new worktrees contain
only intended edits; no source commits/PRs/merge yet. Changelogs and Makefile/
strict alias are also added (not semantic changes). Do not infer release-ready.

Question: any concrete semantic/correctness gap in extracting the bounded
scalar diagnostic into a GENERIC runtime-owned stock sorted-map predicate and
fold, then consuming it optionally from native JSON? Distinguish source defects
from missing qualification. No security proof or performance review requested.

Contract/trust:

- Native helpers are NOT portable Clojure APIs. No ordinary path switched.
- `stock-sorted-map?` checks native sorted tag and exact current defining-core
  sm-ops identity using the interned Var cell. Core-private roots and immutable
  collection internals are trusted. Per-object custom :ops ARE supported and
  must decline without trial calls. Forged private nodes/concurrent direct
  mutation of internal trees are not promised; ordinary runtime trusts them too.
- Fold snapshots the root before callbacks, same structural in-order traversal
  as stock seq, no comparator calls, native visitor(k,v,acc), not Reduced-aware.
- Unlike diagnostic, generic helper admits arbitrary valid keys/values/sizes.
  No private tree layout in data.json; child JSONWriter remains live and its
  observable classification/custom calls preserve real-writer prefix/edits.
- Native JSON first selects LIVE Map writer. Custom map methods/dispatcher
  still bypass both stock folds. Unsupported options preserve fallback. Both
  runtime helpers required; absent/unsupported arity keeps old sequence path.
- Existing source mechanism test now uses a distinct per-object ops table with
  identical seq semantics, so it continues to prove GENERIC fallback selection
  even on a runtime with stock folds. New test separately proves fast selection
  against a disabled-branch same-output mutant.

Validation actually completed:

- New Jolt test79/79 on actual v0.8.23 source boot: actual constructor maps,
  boundary sizes/chunks, desc comparator, unsupported/custom op rejection,
  exception prefixes/identity, nested folds and checked entry-constructor trap.
- Consumer strict4/12 + byte36/799 + sorted10/34 + segmented14/149 =64/994,
  using exact new native helper source overlaid on the frozen qualified1ae
  runtime. This IS source-overlay evidence, NOT a rebuilt latest compiler or
  latest-runtime full encoder qualification. Explicit entry/completion markers
  and exact namespace counts verified. Earlier alias-selected38/621 ran the
  ordinary native suite instead of the requested driver, not credited as this.
- Initial bounded diagnostic component: identical full bytes,132->108ms/2k.
  Actual queue/Durable paired local5k p50620->546ms,175k independently recovered;
  mean gain smaller and upper observations worse. No p99/SLO claim.

Earlier review of diagnostic found no admitted-map ordering defect; it requested
empty-return convention clarification, admission bounds, and actual ops-anchor
selection controls. All now covered for diagnostic; generic runtime fold avoids
nil/false admission ambiguity with separate predicate/operation.

Pay special attention to source boot vs restored images (interned cell anchor),
generic non-scalar key/value callbacks, per-object ops mutation after seq capture,
custom comparator semantics and whether optional arity detection is sufficient.
