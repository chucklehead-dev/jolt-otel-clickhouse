# Declared string collector: active checkpoint

## Source and publication

Worktree worktrees/exporter-declared-string-collector-20261018, branch
perf/declared-string-collector-20261018, clean at
57415464e2685703dc8a8d49a017ed5f12317ef0 (parent e1723f4e836497638c89ec8c63fe9dbe031aa1fa;
base main e3750302c44cd10273c6e1277dc3912975cac8f5). Both commits pushed with
expected-remote-head leases. User noreply author; no compiler/aspects ref moved.

PR creation was REJECTED before process execution by automatic approval review:
it interprets the earlier user request to see branch/text before submission as
requiring exact-text approval here. No PR exists; do not bypass/retry through
another tool. Present exact diff and proposed text and ask user confirmation.
Existing owned-repository testing/issue work remains allowed and unblocked.
Proposed text: declared-string-collector-pr-text-20261018.md.

Source diff:
https://github.com/chucklehead-dev/jolt-otel-clickhouse/compare/e3750302c44cd10273c6e1277dc3912975cac8f5...57415464e2685703dc8a8d49a017ed5f12317ef0

Owned #91 participant-checked (author/all19comment authors casselc; no assignees),
progress posted comment6249175206. It records first candidate, not final-head
qualification. Issue remains open; no end-to-end target/issue closure claim.

## Implementation / controls

Five-file source change including changelog. Defining namespace owns original
key normalizer identity. Pure native scan only for builtin maps<=128, builtin
wanted string sets<=8, live original normalizer, stock contains?/string?.
Source-order grouped values and ordinary map shape retained; all other cases
use unchanged collector. Post-scan root checks are NOT a redefinition lock.

First head focused22tests310assertions PASS; full maintained stock0.8.22
aggregate90352 TERMINAL0,3m15.51s, default JSON/deps/native26.7.3 and matching
child wrapper, normal exit. Hosted exact-head39122457621 all3jobs SUCCESS.

Final5741546 moves stable Var REFERENCES into a native factory (not values),
hot2arg rather than5arg bridge. Focused48583 TERMINAL0:24tests321assertions PASS,
including positive8/negative9/nonstring wanted, root change after factory, and
lexical fold-injected post-scan policy change followed by once-only fallback.

Claude72383 first review TERMINAL124 empty; NOT credited. Narrow guard audit
50791 TERMINAL0 no concrete defect; one suggested9-key gap already covered,
other useful boundaries added. Final constant-binding audit91329 TERMINAL0 no
findings. Main accepts bounded code review but not a universal ABA/concurrent
redefinition guarantee; guards observe particular reads, not all mutations.
After terminal reviews, all six source copies cmp-verified against final5741546
and exact temporary packet /tmp/declared-string-review-20261018.ayhzmWIO removed.

Final-head manual CI39123527387 at5741546: COMPLETED/SUCCESS, all three jobs:
native-json-backends, scoped-s3-sdk, test. Verified from exact run/head API.
These scoped S3 jobs are not real AWS throughput. No other dispatch is needed.

## Performance evidence: allocation benefit, no throughput win established

Recovery note: older related investigation8a8fe12e0507dbafb55f91c198a62a87ea629802
was found while collecting receipts. It already bound stable native collector
references and skipped the original string normalizer per entry, while still
calling live membership per entry. Its normal-path ABBA was not a gain and it
was not integrated into this base. Current work is not a newly invented binding
technique or a blind resurrection of that fix: it adds a separately guarded
pure normalizer+membership scan. The current prototype's five-argument cost
was corrected using that established binding pattern. Prior Oct17 source and
receipts are retained as historical context, not pooled with current results.

Qualified1ae / JSON8d / SDK37 / driver3d / native26.9 / Chez10.4.1 unchanged.
Component script declared-string-collector-screen-20261018.clj uses actual
indexed projector, fixture5k/wide16,2fields,3warmups and24pairs/phase, exact
typed/UTF8 parity before/after. Private fixture compiler bypasses capability
installation only for this component, NOT schema proof. Source scripts
capture body handles outside loops; no per-row Var-literal invocation cost.

| component p50ms/5k | baseline | first head | final head |
| --- | ---: | ---: | ---: |
| typed, first screen |12.927|10.838|--|
| fresh row+encoding, first screen |75.227|72.691|--|
| typed, final paired screen |14.053|--|12.205|
| fresh row+encoding, final paired screen |87.862|--|78.454|

Actual final allocation: typed653.17->540.62B/row, full3904.43->3792.03.
First head saved~32B/row; binding constant refs removes a further~80B/row,
consistent with avoiding the measured runtime's5arg variadic rest-list path.
Stock0.8.23 seq.ss still has fixed-arity jolt-invoke0..4 only; extending those
general paths is a possible upstream extraction, not implemented or measured
here. No claim this small allocation change closes the Rust gap.

Actual SDK unpaired A/B/A (first head):26,521/25,847/28,819confirmedrows/s,
32x5k callbacks each,0drops/fails;175k full physical/typed independent unchanged
reader per arm =525krows. Processes90386/52532/16484 all TERMINAL0.
Candidate below controls: no speedup/no-regression proof, no blame assigned.
All77GCs;97/90/83ms GC does not explain6.03/6.19/5.55s windows. Caller CPU is
not worker/native CPU. All3x266MiB stores removed after independent proof/cmp.

First-head same-process SDK/exporter/connection ABBA76997 TERMINAL0:175krows
independently recovered;16samples/arm. Candidate full-body p50170.18ms versus
baseline157.58ms; mean worker CPU101.35 versus91.49ms. No win.
Raw receipt declared-string-sdk-paired-20261018.paired.edn.
266MiB store removed. Original paired source/launcher preserved withv1 suffix.

Final same-process coarse-stage ABBA51013 TERMINAL0:175krows recovered,
64stage intervals nested in32actual SDK callback/insert windows,16samples/arm.
Independent BB verifier checks ordinals/labels/rows/time enclosure; normal
verifier checks ACK/stats/nativecalls and full unchanged-reader rows/types.
Total independent recovery across all5native runs =875krows. Final266MiB
store removed. No current local heavy workload.

| final instrumented stage | baseline mean wall/workerCPU ms | candidate mean wall/workerCPU ms |
| --- | --- | --- |
| projection+encoding |87.597/87.241|89.290/88.940|
| native+confirmation |82.095/0.142|79.191/0.141|
| full insert body |174.549/92.223|174.076/94.656|

Nested counters/timings NOT additive or uninstrumented throughput. Worker CPU
excludes native engine worker CPU, so native0.14ms is not total native work.
End-to-end essentially flat; encoding CPU not demonstrably better. Allocation
within global cross-thread phase snapshots is not exclusive per-stage ownership.
No sustained/p99/app/S3/Rust qualification. Existing full-compatible mode stays;
optional fast-mode product question remains unanswered, not a blocker.

## Next bounded actions

1. Final-head CI is green. No PR creation without resolving automatic approval
   gate and user review of branch/text; no bypass and no PR currently exists.
2. Preserve small sources/scalars; copied review packet is cleaned.
   Local evidence checkpoint research/declared-string-collector-evidence-20261018
   is separate from the reviewed source branch; no generated native stores.
3. Inspect stock0.8.23 relevancy before proposing upgrade/extractions. Live API
   confirms latest tag a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5, released
   2026-10-17T19:41:22Z. Unqualified local jolt resolves0.8.10; current gates use
   explicit checked .22/qualified1ae artifacts, not PATH. No install/rebuild yet.
   Source/API triage recorded at
   https://github.com/chucklehead-dev/jolt-aspect-packs/issues/178 . Runtime
   direct-linking is opt-in and not automatically compatible with live callback
   semantics; masked-GC-lock and pipe/reader fixes merit targeted qualification.
4. Prefer larger compatible producer/encoding improvements or measured compiler
   fixes next. Two rejected map/staging experiments remain saved separately.
