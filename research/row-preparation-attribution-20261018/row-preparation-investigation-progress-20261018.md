# Row preparation investigation -- bounded negative results

Goal remains active. Previous goal turn integrated reviewed JSONa18 into the
exporter20999f9 topic with exact-head CI success. This turn did NOT adopt a new
production patch: experiments below change the next optimization choices.

## Current unchanged product graph

Exporter topic20999f9c37d01ce1629f9ae2033bd12483030fc7 remains clean/published,
JSONa18, SDK697, driver3d. No PR/merge/submission approval inferred. Previous
validated ~22%Durable gain and175kphysical-row recovery remain the cumulative
product candidate; these new screens are not substitutes for that evidence.
All local workloads below used same pinned1ae/mandatory Chez10.4.1 wrappers.
No runtime/artifact/default installation or canonical aspects ref modified.

## Phase split

Actual SDK2k,16rotating samples, exact full output/field parity:

| phase | p50ms | managedB/row |
|---|---:|---:|
| attribute shaping |63.767|21796|
| nested event JSON |70.601|20520|
| prepare rows |175.046|43370|
| outer encoding |44.636|3653|
| full |217.431|46970|

Fresh producer context mirrors byte hook +empty-link companion. Prebuilt rows
retained in all phases. Times are not additive; do not compare absolute times
between separately scheduled screens as paired results. No p99/SLO claim.
This also confirms the earlier pdata-raw hypothesis was wrong for this scalar
event fixture; that function is not on span-row's event JSON path.

## Attribute reducers -- NOT ADOPTED

Compared original converter with portable reduce/destructure/assoc! and native
into-fold/nth/live converter Vars/transient map. No tree read in native kernel.
Byte parity for same complete records before/after; callback contracts not
qualified, no production source change.16rotating rounds:

| whole encoding | p50ms | managedB/row |
|---|---:|---:|
| baseline |217.307|46970|
| portable |250.311|47996|
| native fold |210.798|35481|

Native fold vs baseline only6/16pairs faster, mean pairedratio1.0298; CPUtotal
3.395->3.421s. Allocation~11.5KB/row saved (~24%), NOT a demonstrated total-time
gain. Portable path slower/more allocation. Retain diagnostics, ship neither.

## Classification upper bound -- NOT ADOPTED

Isolated overlay deliberately bypasses registered receiver class predicates
for stock-tagged TreeMaps and restores roots in finally. This violates custom
predicate contracts: not a proposed optimization. Same default-fixture bytes.
Full p50214.849->214.707ms; mean pairedratio0.9743,10/16pairs faster, CPUtotal
3.646->3.519s, allocation47012->46196B/row. Even this unsafe bypass has only a
small effect; not justification for a compiler/domain-API migration now.

## Native string companion bridge -- REJECTED/CLEANED

Prototype called the existing native hook with a fresh real StringWriter under
the existing public/options/hook/root guards, avoiding public Clojure writer
roundtrip on nonempty values. Existing suites57/887 PASS87240 terminal0.
Full p50147.721->153.306ms; mean pairedratio1.0405,8/16pairs faster, CPUtotal
2.496->2.557s, no allocation saving. Nested JSON46.678->46.997ms.
No win: raw diff preserved as nonempty-string-companion-negative-20261018.patch,
agent-owned one-file edit restored with apply_patch, clean temporary worktree
removed. Unpublished empty topic ref remains at a18. No product adoption or
review/compatibility claim for this rejected prototype.

## Writer monitor upper bound -- NOT ADOPTED

Single-thread-only overlay bypasses synchronized StringWriter seams; first
sb-set! still locked. Deliberately violates concurrent-writer contract. Same
default-fixture bytes before/after;16pairs, full p50147.279->138.689ms, nested
45.762->39.333ms. Allocation47009->43328B/row. This is a modest ceiling (~6%
whole p50) and not permission to drop locks. Private-sink escape proofs would
be necessary for adoption; no such product change was made.

## Native profiler probe

FFI skill used to bind profiler pause/resume void(void) functions after checking
source signatures. Sanitized environment; only warm encoding sampled.17400
terminal0,40krows/byte parity, no source/model claim from successful exit.
gprofng2.42 explicitly warns timer2000->0;3.038swindow yielded only0.062sCPU
samples,0.058sUnknown. Thus capture is NOT usable hotspot attribution. Details
in jolt-native-profile-issue-20261018.md; bounded text reports retained. Exact
owned /tmp/jolt-encoding-gprofng-20261018.nMpDzCNk516KBrawexperiment removed
after terminal/report extraction/cmp; absence verified. No large store created.

## Next actions

1. Resolve submission choice for already validated/reviewable JSON/exporter
   topics; automatic continuation is not submission approval.
2. Avoid more outer-emitter, public-bridge or class-dispatch microfixes without
   new evidence. Most residual CPU is in row preparation; allocation alone has
   not predicted a speed win on this runtime.
3. Next credible higher-level experiment: SDK-owned scalar policy enforcement
   for sorted attributes, with explicit stock-ops/ABI admission and fallback
   before any user callback. Existing primitive private-tree diagnostic is only
   an upper bound, NOT adoptable. Need preserve map kind/order at SDK API and
   exporter HAMT output, metadata, custom ops/comparators/helpers and late root
   changes. Prefer a narrowly named normalization capability over silently
   broadening the existing map-kind-preserving helper. Measure before product.
4. For deeper profiling, address timer interference +generated-code symbol
   support; do not treat raw gprofng unknown percentages or Chez execution
   counts as CPU attribution. No exhaustive model run or new Durable protocol
   logic needed for the experiments completed here.

All local handles75055/48426/91797/87240/69963/17400/53254 are terminal0.
No source topic introduced/merged this turn. Diagnostic overlay files clearly
mark unsafe upper bounds and all restore state before process exit.
