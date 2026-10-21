# Native Durable syscall diagnostic

Declared before running:profile the existing CURRENT exporter maina6cbf939 and
unchanged public field-slot app, stockJolt0.8.23/Chez10.4.1/native26.9.0. This is
not a rerun to seek a favorable benchmark or a comparison with the new direct
map prototype. No source/default/pin/WAL/ACK or model changes.

Question:can filesystem synchronization/publication calls account for much of
the gap remaining outside the rich normalization/encoding component? Capture
only bounded `strace -f -w -c` aggregates for sync,rename,lock,open/close,read/
write and futex calls. No full trace, SQL values, persisted data, secrets or
credentials are logged. Local backend only; no S3/Langfuse/network workload.

The existing app performs3warmups+32confirmed5k callbacks,175k physical rows.
Retain original writer receipt, followed by the SAME independent older-stack
full-field/type/exact-nanosecond/multiplicity reader used for PR157. Treat all
timings as instrumented diagnostic data, NOT a new throughput/tail baseline.
No Claude or other heavy local workload may run concurrently with the native
job. Build only after the current bounded source review has terminated.

Scope limits:whole-process syscall totals INCLUDE setup,warmups,query and close;
they are not phase-specific or allocation-site profiles. `-w` counts time inside
syscalls, including blocking/wait time, summed over traced threads; totals may
exceed elapsed time. Never subtract/sum them as disjoint callback phases. Small
TOTAL sync time can rule out sync dominance; large totals alone cannot prove
which measured callbacks or background workers caused them. Futex waits are
not automatically CPU work or evidence of a lock bottleneck.

After terminal writer+independent reader validation and copying/cmp of small
receipts/logs, retire only this run's synthetic stores, app binary and dedicated
`.build` intermediates. Preserve source hashes and aggregate reports; no bulk
evidence needs to stay on ext4. Exact process handles/paths/results go into the
continuation before another goal turn or restart.

## Observed result

The newly built binary's SHA256 is IDENTICAL to PR157's previous app:
`702dda478703aae460a28c8ac8276485d16e33b175357331deb13a42831c50eb`.
It uses the unchanged indexed/fused constructor wrapper, selected `fused`, and
the same public exporter app. No direct-map candidate code or private phase
wrapper is included. Selected source hashes were checked before/after; that is
not a complete transitive compiler provenance attestation.

Writer completed0 with35 successful5k calls:3warmups plus32 measured confirmed
callbacks,175000 rows. Independent reader at
exporter0af1008185cf84577298d3a0a0b2db87b97bddd5, JSON3adc8c5d, same stock
runtime/native library, completed0:all physical rows/types/status/timestamps,
5000groups/625traces/35copies and deterministic ties agree. Existing receipt
validator passed; all nine existing receipt mutations were rejected. No
count-only or current-writer readback substitute.

| Whole-process traced calls | Calls | Summed wall time inside calls |
| --- | ---: | ---: |
| fsync |159|0.745492s|
| fdatasync |126|0.238005s|
| write |13648|1.431286s|
| read |19062|1.317527s|
| pread64 |8924|0.580216s|
| flock |192|0.010820s|
| futex |11161|561.948257s|

Full aggregate in `results/syscalls.txt`. fsync+fdatasync totals0.983497s
including setup/warmup/query/close, versus~18.94s for the measured callback
window. That is evidence AGAINST sync-call dominance in THIS INSTRUMENTED run,
not an exact phase attribution or proof of untraced costs. Other I/O/userland
publication work is not thereby ruled out. Do not remove sync/confirmation
invariants in pursuit of the missing factor of two on this evidence.

Futex99.05% is a share of SUMMED threaded syscall wait time, NOT wall time,
CPU usage or a diagnosis of contention. Summed traced time567.31s can exceed
the app's elapsed time because many background workers wait concurrently.
This filter does not observe all syscall kinds or identify individual workers.
Read/write time also contains tracing disturbance; no memcpy, hashing, JSON,
native-engine or per-stage CPU attribution is established.

The instrumented receipt reports8446.52confirmed window rows/s and
p50/p90/p95/p99 566.89/701.09/725.33/794.97ms. These describe the TRACED run only;
they are NOT a regression/new baseline, a production target test or evidence
that the previous untraced measurements changed. Population tails remain
unqualified. No rerun was used to improve these numbers.

Initial sandbox ptrace was denied BEFORE startup (no run directory/child job);
its small log is retained. The same one run then executed with the targeted
escalation needed for ptrace, not an unchanged workload retry after bad timing.

## Custody and next step

Writer/readers/build are terminal. Original writer/reader/syscall receipts were
copied and `cmp` verified; source and binary hashes checked before retirement.
Removed ONLY this generated synthetic run (~728MiB), app.build (~291MiB) and
binary (~77MiB), ~1.07GiB total, AFTER all gates. Exact sizes/paths retained in
`results/retired-kib.txt`. Small external root now36KiB and this packet~60KiB.
Deleted fixtures/builds can be regenerated; no user data or global cache was
removed. `binary-sha256.txt` names the deliberately retired binary, so do not
expect its original path still to exist.

Next diagnosis should attribute actual process/worker CPU in preparation vs
native publication, rather than treating observing-thread counters or futex
wait aggregates as all-process CPU. `perf` is not on PATH; no profiler/system
package was installed and no upstream issue/PR opened. The pending #141 bounded
overlap scope remains separate and still needs its explicit approval before
model logic. No async admission/ACK substitution is justified here.

## Commands

Build from `research/fused-typed-field-slots-20261021/app` using the mandatory
Chez wrapper, `JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0`, pinned stock executable,
`build -m perf.field-slot-pipeline -o /absolute/owned/app`. Keep build first.
Run from neutral/tmp, local backend and explicit native26.9.0:

```sh
strace -f -qq -w -c \
  -e trace=fsync,fdatasync,syncfs,rename,renameat,renameat2,link,linkat,unlink,unlinkat,flock,openat,close,pread64,pwrite64,write,read,futex \
  -o /absolute/owned/syscalls.txt /absolute/owned/app /absolute/fresh/run fused
```

The same wrapper is required before that local command; sandbox ptrace needs
narrow escalation. Independent reader uses the unchanged older worktree's
`research/nested-fragments-20261019/independent-reader.clj`, original test/bench
helper paths and explicit JSON3adc8c5d. Revalidate SMALL retained receipts only:

```sh
bb research/fused-typed-field-slots-20261021/validate-native.bb \
  research/native-publication-syscalls-20261021/results fused
```

## Read-only review

Claude's bounded source-only review found no blocking defect and checked the
arithmetic, binary identity, recovery/validator scope, retirement and limits.
Its unedited report is `claude-review.txt`; it did not run native work.
The recommended clarification now distinguishes35 successful calls from the
32 measured callbacks. Own source check in
`research/scalar-view-exporter-20261020/app/src/perf/public_pipeline.clj`
confirms warmups ALSO require a true confirmed-export return; exclusion from
the timed sample vector does not mean those3 calls were unconfirmed.
The review's shorter app/src path was wrong for this checkout; its inability
to read that reference is not represented as a source audit of the app body.
Nine existing receipt mutants check writer/recovery receipts, NOT the syscall
bottleneck inference. No unchanged native rerun was needed for this prose fix.
