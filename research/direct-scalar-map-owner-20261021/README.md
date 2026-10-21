# Direct ordinary-map encoding with the real SDK owner

UNADOPTED prototype, exporter basea6cbf939, SDKbase6cd57c86 with local candidate
`/home/chuck/ai-src/worktrees/otel-ordinary-scalar-map-views-20261021`.
SDKmaincd55981f lacks these stacked APIs; no ancestor rollout is authorized by
this experiment. Existing exporter source/options/defaults/pins and durable
formats/ACKs are unchanged. Only the optional library has a new trusted native
box; SDK admission stays SDK-owned. No compiler or data.json modification.

Unlike the previous strict inline sink, this uses the actual SDK public
projector, all eight original policy anchors, its configured bound and EXACT
original ordinary-map admission. Collision buckets still decline. The codec
owns a metadata-free header sharing admitted immutable input nodes; output is
fresh, numbers/Booleans become the SAME JSON strings, and original map order,
depth/output budgets apply. It creates no numeric Strings or normalized HAMT.
It is not an enforced linear loan:caller must keep nodes/Strings immutable
while using the opaque encoder-only view. No new global or codec value cache.
The SDK/projector retains existing sorted-map plans and original generic
fallback; direct ordinary views bypass normalized-root caching. Repeated-root
performance therefore still needs independent qualification before adoption.

`context.clj` selects the actual SDK option using PUBLIC SDK factories, not
consumer-captured policy roots or Scheme source patches. Private exporter Vars
are used only for the component fixture/row encoding. Both arms use the SAME
candidate SDK+codec and differ only in that explicit option. The parent graph
still resolves ecosystem forks; not a wholly fork-free app or baseline against
the old binary. Original earlier sink/prefix evidence remains independently
retained; no comparison of their absolute timings is causal here.

## Result and decision

One stockJolt0.8.23 source process/Chez10.4.1, one local workload, no Claude
concurrently. Fixed baseline/direct/direct/baseline,16observations/arm,5k rich
spans, realSDK policy/conversion and typed projection/nested+outer encoding.
Every complete8647784-byte output matches. Changed attribute/event controls
change output and match across arms. No source mutation after timing except
Boolean-only failure reporting in the SDK tests.

| Arm | p50 /p90 /p95 /p99 ms | Median estimated B/row |
| --- | --- | ---: |
| Original copying path |178.13 /195.79 /196.93 /196.93|8983.04|
| SDK-owned direct view |172.94 /292.40 /317.63 /317.63|8464.59|

Allocation5.77% lower, median2.91% lower; slowest sample61.29% WORSE. Retain the
code/owner integration but do NOT roll out as a throughput fix or enable a
default. At n16,p95/p99 are the sample maximum, not population tails. Counter
snapshots are non-atomic allocation estimates, not native/RSS/object-site
profiles. CPU is observing-thread CPU, not whole-process.

Candidate sample25:317.63ms wall/~317.64ms threadCPU, GC1/~1.84ms. Sample26:
292.40ms/~292.42msCPU, GC2/~10.48ms. These pauses do not account for the large
slowdown; no scheduler, host-frequency or CPU-site diagnosis is established.
No unchanged favorable rerun/native sweep, target qualification, SDK live queue,
independent recovery, S3, Oscope or packaging inference from this component.

## Checks and reproduction

Actual exporter1test/11assertions passes:ordinary, changed values, structured/
floating/oversize values, small maps, collision buckets and custom key/value
policies preserve full output. The copying helper is not called for the actual
admitted ordinary fixture. SDK gates10/172,27/389,stock cache runner19/162 pass;
see that branch's research README for bounds, incorrect-runner attempt and
rejected quote/metadata mutants. Independent library source gate43/596 passes
without SDK/exporter dependencies. No new AOT/native claim.

From exporter root, prefix the pinned stock executable with the mandatory
`/home/chuck/ai-src/tools/jolt-with-chez-10.4.1` wrapper:

```sh
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src" "research/scalar-view-exporter-20261020/app/src"] :deps {io.github.casselc/otel {:local/root "/home/chuck/ai-src/worktrees/otel-ordinary-scalar-map-views-20261021" :exclusions [jolt-lang/jolt-crypto]}}}' \
  research/direct-scalar-map-owner-20261021/check.clj
```

Use `screen.clj RESULT.edn` for the fixed screen. The named workspace paths
are explicit development selections, not public dependency coordinates.
Keep small source/checksum/scalar logs only. No native build/store was created.
The remaining throughput gap needs whole-pipeline diagnosis; this experiment
shows removing this map-copy allocation alone is not the hoped-for large win.

## Independent review

Bounded Claude read-only review found no blocker to publishing the UNADOPTED
SDK/codec prototype, not approval to deploy it. Raw report is copied here as
`claude-review.txt`; no commands/tests were executed by Claude. Its only evidence
gap was the missing43/596 raw source-gate log, now captured as
`codec-source-controls.log`. This is not a new compiled standalone qualification.
The SDK README corrects its overbroad description of the old projector as
always map-returning; sorted-map opaque views already existed. Neither the
review nor the allocation gain overrides the negative tail/adoption decision.
