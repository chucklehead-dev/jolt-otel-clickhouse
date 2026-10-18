# Read-only prototype review: stock sorted scalar folding

Question: is the bounded native fold a credible optional runtime-owned
capability for JSON, or does it skip user-observable behavior even under the
explicit trust boundary? Do not implement/edit or broaden into an ecosystem
survey. Report concrete defects and counterexample scenarios; max 900 words.

Read ONLY these source files:

1. `/home/chuck/ai-src/evidence/stock-sorted-fold-probe-20261018.ss`
2. `/home/chuck/ai-src/evidence/install-stock-sorted-fold-probe-20261018.clj`
3. `/home/chuck/ai-src/worktrees/data-json-sorted-map-byte-seq-20261018/src/main/clojure/clojure/data/json/jolt_byte_writer.ss`
4. `/home/chuck/ai-src/worktrees/jolt-0821-unitcontext-guarded-writer-20261014/host/chez/host-table.ss` lines 145-215 (stock sorted seq)

Do not read secrets, environment files, credentials, telemetry or persisted
stores. Source/tests only. No Bash, fetching, tools outside Read/Glob/Grep,
publication, other agents or edits. Exact JSON base a18ae06, runtime source
1ae7e0d. Both are unchanged by this probe; installer replaces one branch in a
disposable process and references an evidence helper, not a shipped API.

Trust/scope:

- Private/bootstrap core roots (including sm-ops) and immutable native node
  contents are trusted. No security claim against forged private internals or
  concurrent direct mutation through native/reflection escape hatches. Public
  JSONWriter methods/dispatcher, custom per-object :ops, comparator callbacks,
  output prefix reads/edits, errors and reentry ARE part of the contract.
- Helper admits only exact stock operation-table identity, intrinsic string
  keys, strings/Booleans/fixnum values, <=128 nodes. Full pure bounded validation
  precedes traversal; custom maps decline WITHOUT trial-calling operations.
- Native JSON branch selects the LIVE Map implementation before helper
  admission. Per-child emit! still resolves live JSONWriter and publishes the
  real writer before observable classification/custom dispatch. Unsupported
  options still use the original whole writer; no option/backend floor changes.
- Existing stock seq constructs entry chunks without comparator calls from a
  captured immutable tree. Prototype fold captures that root instead, avoids
  native entry/seq allocation and pure stock sequence publication boundaries.
- Runtime-owned integration is only proposed. Library duplication of private
  tree layout is NOT the desired product design. Current helper has no
  independent loader geometry probe and is marked diagnostic.

Measured initial SAME COMPLETE BYTES on 2000 actual SDK-ended event-rich spans,
24 rotating pairs, SDK7878 +exportera1 +JSONa18: full encoding p50 131.6->108.2ms,
managed allocation30.3->23.1KB/row. No callback/tail/Durable qualification inferred.
Please focus on semantic viability and missing tests, not performance approval.
