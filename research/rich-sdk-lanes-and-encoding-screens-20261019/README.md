# Rich SDK lane overlap and small encoding screens

Previous goal turn made progress by publishing negative WAL-copy evidence and
bounding repeated conversion costs. This turn completed a real rich SDK/Durable
ABBA comparison, two guarded encoding experiments, focused/red controls and
targeted review. Targets and app integration remain incomplete; goal active.

## Selected code, topology-only ABBA

No product source overlay or candidate helper was selected. Hosted Jolt7098,
exporter2c54b98d35464c8fc7c737b0c92a0042272b589a, SDK7878, nativeJSON217,
driver3d766fa, explicit native26.9.0, pinned Chez10.4.1. Binary/native library
checksums and worktree SHA/dirty guards are enforced by the copied runner.
The fixture supplies5000rich spans,625traces/eight distinct siblings per trace,
mixed sorted resource/event attrs and typed span fields. It is SDK-shaped,
not OTLP/socket ingestion or a live app workload.

One or two DISTINCT exporters, one serial SDK worker per exporter, one borrowed
Durable connection/native actor. Trace affinity preserves callback order within
each trace; no global ordering claim. Shared total queue capacity10000, maxbatch
5000; two-worker callbacks can overlap but the same exporter is never called
concurrently. Saturated producer explicitly waits for capacity; no open-loop
20k arrival acceptance claim. Three warmups use the first exporter only, so the
second exporter is initially cold; this is not sustained-tail qualification.

| Arm order | Workers | Confirmed rows/s | Callback p50/p90/p95/p99 ms |
| --- | ---: | ---: | --- |
| A1 | 1 | 5407.4 | 917.1 /986.0 /1076.8 /1090.0 |
| B1 | 2 | 9255.7 | 963.9 /1147.0 /1299.2 /1401.7 |
| B2 | 2 | 9143.5 | 977.6 /1210.3 /1304.5 /1362.7 |
| A2 | 1 | 5397.0 | 920.0 /987.2 /1029.2 /1089.3 |

Pooled windows:5402.22→9199.23rows/s, +70.29%. Estimated allocation
24437.34→24449.86B/row, essentially unchanged. Each1-worker arm has32measured
callbacks/35native streams; each2-worker arm35callbacks/38streams (partial
callback sizes are retained). All callback rows are1..5000; p50/p90/p95/p99
size5000in these arms. Actual callback overlap is observed only with2workers.
Callback latency gets worse even as aggregate throughput increases; do not
convert this into a p99throughput acceptance claim or use pooled windows as
latency measurements. Small sample tails remain descriptive only.

Both bracketing controls are consistent in THIS slower host window. Earlier
normal rich runs around10.4k are not contemporaneous controls, and multiplying
that historical rate by1.70 would not be a verified result. No cause for the
between-window slowdown is established. Nearby component CPU≈wall with varying
GC does not prove descheduling explains it. No library regression/frequency
diagnosis is claimed from that observation.

All four writers, older-stack independent readers and validators completed0.
Each reader verifies all175000physical rows, full values/types/typed statuses,
event ticks, trace grouping and35copies per source record:700000rows total.
All queues drain,160000measured rows/arm are attempted/exported, no failures or
drops, per-trace routing/order counts hold. Original stronger1-worker checks
remain; new2-worker checks validate per-lane serial callbacks/positive routing,
all stream counts and full readback rather than assuming32fixed callbacks.

Four exact task-created rootsUh143wo0/KhPhzzy4/d6THYUwD/yFmgGc5r under
/tmp/rich-sdk-lanes-abba-20261019. were retired ONLY after terminal children,
validated receipts and byte-identical copied writer/reader reports. The runner
records each retirement; no bulk store remains. Oldruntime1ae/exporter473/SDK37/
JSON8d readers are independent of the writer graph. No Oscope/public processor
mode, S3/Rust/service p99qualification, protocol/model change or app repin.

## Small encoding candidates remain unselected

1. Local dataJSON experiment95a6a5d, branchperf/ascii-private-flush-20261019:
   checked direct ASCII scratch→String construction removes one temporary byte
   copy/decode without skipping publication.3tests/34assertions pass, including
   Unicode/control/boundary, rawUnicode custom writer/reentry/prefix and live
   mechanism controls. Full64-sample encoding output hashes match. Allocation
   falls about480B/row; timings mixed (lazy p50433.4→482.5ms, prepared513.0→493.1).
   No pipeline, full native suite, independent review or large-string performance
   qualification. No PR or adoption.
2. Local SDK experiment37d35ec, branchperf/scalar-policy-anchor-decode-20261019:
   decode immutable policy storage once; retain every live Var check BEFORE and
   AFTER each admission and fresh output maps.12tests/166assertions pass; old
   source fails exactly4decode-mechanism assertions with18passes/0errors. Source
   review26768 found no semantic defect; its one-anchor coverage note prompted
   six distinct policy slots, independently replaced/restored and rerun. Main
   independently inspected unchanged admission/conversion bodies and live guards.
   Unsupported malformed external binding vectors are not an internal API promise.
   Full encoding source-overlay screen keeps original SDK layout fences/loaders:
   lazy p50469.2→458.6ms, prepared503.0→479.2; upper quantiles mixed/allocation
   unchanged. This does not establish a repeatable pipeline win; no PR/adoption.

All small-screen output hashes are the full8,648,398-byte fixture digest
862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39. The
preparation/emission phases overlap; don't subtract their medians as proof.
Source snapshots, focused/red logs, raw samples, local experiment notes and
the completed SDK review are included. Selected cumulative gains remain intact.

## Next concrete tasks and boundaries

- Quantify1/2/4workers with a common bounded queue budget large enough that
  batch5000<=each queue (e.g.20000total); retain fresh writer/readers, real CPU/
  heap/latency data, per-trace ordering and stable owner retirement. Do not edit
  the frozen two-worker helpers/runners in place. This is consumer topology
  research before any supported SDK/Oscope lifecycle/config implementation.
- Avoiding full nested EventsJSON Unicode String materialization may remove
  substantially more copying than these small edits. Source-only Claude20727
  is live at this checkpoint, reviewing a separate owned pre-encoded-fragment
  API idea. No implementation or soundness claim yet. Inner callbacks must
  finish at the original row-construction point; outer custom String AND
  ancestor/vector writers must see logical Strings, never internal markers.
  Unknown observable classification must fall back without placeholder/replayed
  predicates. Runtime-owned callback-free family probes may be necessary.
- Oscope169 is merged/tested treef9d8af0, exporter4a/JSON217. Singleton exporter2c
  follow-up and separate embedded-profile167 remain pending. Don't conflate
  root qualification with that profile, OTLP/Langfuse or a live Samizdat run.
- For this uncompressed measured WAL shape10,508,410bytes/5000rows,10Mbps upload
  has an IDEAL ceiling594.76rows/s before request/confirmation overhead. This
  is bandwidth arithmetic conditional on this payload, not a Jolt limit or a
  benchmark. Assess S3 CPU/pipeline potential on existing faster-network CI;
  local DSL remains useful for correctness/failure tests. Compression would
  require its own supported storage/protocol qualification, not silent gzip.

No new approval blocker. No upstream PR opened, no dependency or compiler
installation changed, no exhaustive checks rerun, no target/tail gate claimed.
