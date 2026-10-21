# Preparation CPU/GC diagnostic

Separate release run of source `a7437ac`, stock Jolt 0.8.23/Chez 10.4.1,
chDB 26.9.0: 3 warmups + 16 measured 5,000-row batches, optional phase counters
enabled. This is not a new matched throughput comparison or tail qualification.
All 95,000 rows were independently recovered with the older ordinary-reader
graph described in README. SDK failures/drops/remaining queue were zero.
Raw scalar samples and physical recovery receipts are in `diagnostic-results/`.

Four of 16 measured preparation intervals included GC, with 17.86-23.87ms of
GC wall time. The longest preparation was 290.05ms (289.47ms CPU), including
23.87ms GC; the next longest was 278.92ms (278.85ms CPU) with NO GC. Thus GC
alone cannot explain all long preparations. This is one diagnostic sample,
not proof of the cause of earlier ABBA outliers or a guarantee about future GC.

## Important counter-scope correction

The measured binary's `:phase-counters-scope :process-wide-not-thread-local`
label was overly broad. Raw values are retained unchanged. For this runtime:

- **CPU is current OS-thread CPU**, not whole-process CPU. Stock Jolt
  `v0.8.23:host/chez/scheme-adapter-runtime.ss` calls `statistics`/`sstats-cpu`;
  [Chez 10.4.1 statistics](https://github.com/cisco/ChezScheme/blob/v10.4.1/s/7.ss#L819-L828)
  populates it with `current-time 'time-thread`.
- Memory/GC observations are process-wide and snapshots are non-atomic.
  They can include concurrent publisher activity and cannot establish exclusive
  preparation-thread allocation.
- There is ONE SDK preparer. Two slots permit preparation to overlap previous
  publication, not two simultaneous preparation callbacks.

Current source labels these scopes separately. No new throughput or native run
is claimed for that metadata-only correction. The small CPU/wall gaps support
targeting actual preparer-thread work rather than explaining these specific
intervals by OS descheduling; memory ownership and whole-process CPU remain
separate questions.

## Review and next action

Claude's initial source review found no blocker in opt-in gating, arithmetic,
or ownership changes. Codex rejected its unsupported process-CPU/concurrent-
preparer interpretations against the runtime source and serial SDK contract,
then corrected metadata/prose. The targeted Claude follow-up found no regression
in those corrections. Focused controls pass 3 tests/26 assertions and explicitly
test that disabled diagnostics take zero phase snapshots. Reviews are source-only;
Codex ran native writer/recovery and controls.

Next: remove repeated projection/encoding/materialization work on the validated
stock-runtime path. Previously rejected row-vector/digit/cache tweaks remain
preserved, not silently adopted or rerun for a favorable sample. Do not revive
the value-reuse/fused-row candidates solely on their allocation figures.
