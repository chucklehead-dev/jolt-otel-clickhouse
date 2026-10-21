# Native user-CPU sampling

Declared before the app run:unchanged current exporter maina6cbf939 public
field-slot app; official stockJolt0.8.23/Chez10.4.1/native26.9.0. Research only,
no direct-map candidate/default/pin/model/ACK or source instrumentation change.
No unchanged retry to obtain better throughput.

Question:where does USER CPU execute across the actual app's threads? The
prior syscall packet cannot answer this; futex wait time is not CPU time and
the calling thread's host counters do not observe every native/publisher worker.

Locally extracted official Ubuntu perf6.8.12 package6.8.0-147.147, SHA256
7bf31034e1914ff171c2b82c48e1f17b5123b7e63e1d4ef0582877c45cc17cd0;
libtraceevent1 epoch1:1.8.2-1ubuntu2.1, SHA256
fb03496824a22ffb70c67a92a66c4e369bbbefc934078a1fecda564364793d56.
No apt install, system setting/capability change or global PATH change. One
dependency download used an epoch-escaped filename; no unverified extraction.
Profiler and library stay in a bounded user cache for reuse, no compiler build.

An independent one-second synthetic OpenSSL child probe recorded198user-CPU
samples, zero lost samples,~86%in its expected SHA256 kernel. This establishes
sampling works, not application attribution. WSLkernel6.6.87.2, perf paranoid2.
The package lives under `/home/chuck/.cache/native-perf-tools-20261021`; use its
actual executable, not Ubuntu's kernel-version-dispatch wrapper.

One app run: `perf record --no-buildid-cache -e cpu-clock:u -F 199 -m 128`.
Flat instruction-pointer/TID/DSO sampling only, NO user stack-memory dumps,
kernel/system-wide profiling, environment, telemetry or persisted-object values.
No new code/Var wrapper to alter release optimization. Whole-process sampling
includes setup,warmups and close; unless a reliable boundary is established,
do not call it only the32measured callbacks. Keep lost/unresolved samples visible.
Anonymous/JIT code cannot automatically be labeled Scheme or an individual
function; native engine JIT may also execute anonymously. Sampling percentages
are not exact disjoint wall-clock stage durations or a population-tail result.

Use the existing3warmup+32measured5k confirmed app with fused field slots,
175k total stored rows, then the SAME independent older reader and receipt
validator/mutation controls as priorPR157. Profiling timing is diagnostic, NOT a
new throughput comparison or qualification. No concurrent heavy workload/Claude.
Retain binary until all planned reports are extracted; after terminal recovery,
copy/cmp/source/binary hash checks, retire its exact build/store paths. Keep
bounded raw profile/report evidence; no duplicated build-id library cache.

## Observed CPU attribution

One successful actual app run, same binary SHA702dda478703aae460a28c8ac8276485d16e33b175357331deb13a42831c50eb
as PR157 and the syscall diagnostic. 3451samples, ZERO lost samples, approximate
event period total17.341706375seconds USER CPU, not elapsed time. No stack
capture or kernel/system-wide event was enabled. No concurrent agent-started
heavy job/Claude; host frequency/load was not controlled.

| Sampled code mapping | Samples | User-CPU period share |
| --- | ---: | ---: |
| Anonymous/JIT, unresolved names |1872|54.25%|
| libchdb.so |1194|34.60%|
| App image, including Chez runtime C |128|3.71%|
| libcrypto.so.3 |114|3.30%|
| libc.so.6 |78|2.26%|
| Dynamic loader |63|1.83%|
| vdso |2|0.06%|

These are sampled USER CPU shares across all app threads, NOT disjoint
wall-clock stages. Anonymous/JIT is not automatically JSON, Scheme or even
the preparation thread; native query code can also be generated. No real
`/tmp/perf-2778170.map` exists for resolving those names.

Thread split from explicit perf-script PID/TID fields (one PID2778170):

| OS thread | Samples | Period share | Observed names |
| --- | ---: | ---: | --- |
| Main2778170 |1875|54.33%|app|
|2778189|513|14.87%|app|
|2778187+2778188|572|16.57%|MergeMutate|
|2778265|483|14.00%|ThreadPool /RuntimeData|
|Other threads|8|0.23%|background/query workers|

Non-main threads account for45.67%of sampled user CPU. Do not label the second
app-named thread the confirmed publisher without an independent role witness;
native names also change when pool workers are reused. The current host
counter's calling-thread attribution is therefore insufficient for whole-app
CPU. Exact source chain:stock Jolt rt.ss CPU→sa-stats→sstats-cpu, Chez10.4.1
`s/7.ss:858–867` uses `current-time 'time-thread`. OTelmaincd55981f currently
labels that primitive `process.runtime.jolt.cpu.time`, issue casselc/otel#70.
This profile is NOT a live reproduction/test of that instrument's callback or
a fix; the public app does not enable those runtime metrics.

## A resolved native copying block, not a CPUID diagnosis

113samples(3.27%of total period) fall in one native256-byte vector-copy loop,
across5TIDs and timestamps577169.43..577185.91, not just the first startup
instant. It is NOT the entire copying cost or an established redundant copy.
No caller stack/function name is known. The small assembly/load-segment files
in results preserve the evidence without downloading a large debug package.

Critical address caveat:perf's DSO offsets here are FILE offsets. libchdb's RX
LOAD segment has VMA−fileoffset=0x1000, so hot fileoffset0x902041f corresponds
to ELF VMA0x902141f. The loop is file[0x9020400,0x902044a),
VMA[0x9021400,0x902144a). Disassembling the unadjusted number accidentally shows
CPU-feature-detection code. That was rejected before any diagnosis:there is NO
evidence here of repeated CPUID as the bottleneck. `query_stable@@Base-...` is
objdump's distant nearest-export label, NOT this routine's recovered name.

## Timing, clocks and recovery

The profiled receipt is11590.72confirmed window rows/s;
p50/p90/p95/p99 436.59/491.16/511.35/584.77ms,32measured samples, maximum atp99.
These are INSTRUMENTED diagnostic observations, not a new baseline or causal
comparison to the syscall run/previous unprofiled measurements. Allocation
estimate14812.53B/row remains non-atomic managed counters, not native/RSS data.
The25k inverse-p50 /20k inverse-p99 goal remains unmet/unqualified.

No per-callback CPU attribution is claimed. App System.nanoTime before/after
are5.11/18.91s from its arbitrary origin, while perf sample times are around
577167s. Their raw timestamps cannot be joined. Two-second bins in the sample
summary are relative to the FIRST SAMPLE, not declared application phases.
No clock alignment was measured and no offset was guessed.

Writer exited0; old independent reader0af1008 with JSON3adc8c5d exited0 after
verifying ALL175000physical rows, types/status/exact nanoseconds,5000groups,
625traces,35copies and tie order. Existing validator and9receipt mutations pass.
No fixture-count-only/current-writer substitute. All original receipts, full
compressed profile and samples, reports, checksums and source probe are retained.
One unsupported `tid` sort-key report attempt was corrected to perf's supported
`pid` column; explicit script PID/TID output disambiguates it. No app rerun.

## Stock-library profiling capability and research adjudication

The bounded Claude source audit is retained unedited as `jit-symbol-research.txt`.
It found no demonstrated PUBLIC code-address/range feed for GC-safe perf JIT
names in the inspected Chez10.4.1 files. Own checks confirm public inspector
names/source metadata are different from addresses, and code can move until
static promotion (`c/gc.c:36–41`). This is not a proof no private hook exists,
nor permission to pin/promote application heaps or start a runtime fork.

Its statement that there is no time/CPU profiling facility is TOO BROAD:
`csug/system.stex:5263–5370` and `s/costctr.ss` document PUBLIC cost centers.
`with-cost-center #t cc thunk` measures CPU for selected regions, including
uninstrumented/non-Scheme code on executing threads. It does NOT sample PCs,
name JIT code, or implicitly include work on other workers. Allocation and
instruction counts require their respective compilation flags; zero default
counts do not mean no allocation/instructions. Basic code names need not be
treated as requiring the source-info instrumentation flags either.

Own `cost-center-smoke.clj` runs on EXACT stockJolt0.8.23, no compiler/data.json
change or global helper installation:one million iterations return the same
sum, timedCPU positive(~2ms), explicit untimedCPU0, multiple values, exact
expected-error propagation and reset controls pass. This is a CAPABILITY/correctness probe,
not overhead qualification or fiber/carrier-transfer integration. Do not call
it process CPU. It offers a library-level route to measuring named pure CPU
regions without pretending execution-count instrumentation is sampled CPU.

The same probe also passes from neutral `/tmp` with `-Srepro -Sdeps '{:deps {}}'`.
Explicit checks confirm no SDK, exporter, data.json or codec namespace is loaded.
See `results/isolated-cost-center-smoke.log`. This proves the narrow capability
needs no ecosystem fork; it does not qualify a complete application or packaging.

## Next bounded action

Measure a few COARSE synchronous encoder/normalization regions with public
cost centers, unchanged outputs and an explicit observation-overhead control,
instead of naming anonymous samples by assumption or adding another key cache.
Avoid per-byte/key clocks. Preserve caller-thread vs worker CPU distinctions;
do not sum nested inclusive regions as disjoint stages. No pipeline/ACK/model
change implied. The separate #141 overlap RevisionB approval remains pending.

## Retention and reanalysis

The writer, independent reader, build and source-research jobs are terminal.
The profile and decoded samples were compressed losslessly; decompressed copies
match the originals. Recomputing the summary directly from compressed samples
also produces a byte-identical report:

```sh
bb research/native-cpu-attribution-20261021/summarize.bb \
  research/native-cpu-attribution-20261021/results/samples-offsets.txt.gz \
  research/native-cpu-attribution-20261021/results/writer.edn /absolute/owned/summary.edn
```

After validation, receipt comparisons and source checks, removed this run's
synthetic store (745,352 KiB), build intermediates (298,404 KiB), local binary
(78,912 KiB), and redundant uncompressed samples/profile. These are reproducible
fixtures, not user data. Exact paths/sizes are retained in `results/retired-kib.txt`.
The small external evidence root is about 24 KiB and this packet about 230 KiB.

One matching binary is preserved on the export mount for reuse, avoiding another
build of unchanged code. Its complete path and SHA are in
`results/archived-binary-sha256.txt` under
`/mnt/d/wsl-export/native-perf-apps/702dda.../app` (78 MiB).
To resolve app symbols again, restore that verified binary to the original path
recorded in the raw profile. Large native-library/build-ID copies were not kept.
The local reusable profiler cache remains about 24 MiB; no system install or
global cache cleanup was performed.
