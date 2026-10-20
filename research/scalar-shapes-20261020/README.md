# Bounded inferred scalar key shapes: integrated stock-Jolt qualification

This extends the separately preserved fixed-schema experiment at exporter
`0e6bd22`. It is an SDK-owned integration, not an application reading private
SDK Vars or manually supplying a schema. Independent library/opt-in exporter
implementation `6288a7ccd04087f01061275367f7dabffd39cdba`, SDK implementation
`14f58e5a57d6cd1760451a426e40d8cddcf53949` (based on the qualified consumer
SDK3366, retaining native helpers and both decoder improvements).

## Contract / layering

The independent `jolt.codec.scalar-text.experimental` namespace has no SDK,
exporter or data.json dependency. Its trusted factory receives original owner
policy anchors, scalar limits and trusted sorted prototype. It learns at most
8 ordered key shapes /8192 key characters /128 keys per shape, retaining only
schema keys/ordinal hash geometry, never input wrappers/trees or value storage.
The SDK decides when to learn: only after its original sorted converter admits.
Root-cache hits run first; plain maps keep their original path. Plans validate
each new input and recheck all eight original policy roots. Every result still
passes SDK store admission/budgets. Full shape budgets preserve old conversion
for new shapes. No global cache/helper, concurrent-use support or WAL change.

The default-off exporter option is `:experimental-span-shape-plans? true`, with
the existing explicit codec +SDK payload-cache +owned compact Durable options.
Old SDKs/libraries reject explicitly before JDBC; no silent downgrade. Additional
template overhead is bounded, not a hard RSS limit. Immutable inputs/Strings,
private runtime layouts, original core and injected native factory bodies are
trusted. Only the named stock runtime is qualified, not arbitrary future nightlies.

## Functional / review gates

- Official stock Jolt0.8.23, pinned Chez10.4.1, no compiler/data.json changes.
- Independent library source: **37 tests /435 assertions**, zero failures/errors.
- New SDK option: **6 /73**, including real bounded-learning rejection (new cold
  shapes still use original fallback), original eight-root changes/restoration,
  root-cache-first behavior and plan use with source-value caching disabled.
- Full SDK regression suite: **476 /5484**, zero failures/errors.
- Exporter integration: **4 /15**, including two actual learned plans/four hits
  across two payloads and exact wire equality. Publication and schema checks are
  stubbed in that unit gate; it is NOT native persistence proof.
- Actual older SDK3366 and older library7ab selected in separate processes:
  explicit unavailable-capability failure, no JDBC acquisition/no error cause.
- Claude read-only review **APPROVE**: kernel, learning bounds, SDK integration,
  exporter selection, tests and docs. Codex owns executed gates. The review's
  phrase "end-to-end" refers only to genuine conversion/encoding selection;
  publication is stubbed there, as the source/test and task explicitly state.
  Its optional stronger prototype geometry self-check remains a known future
  hardening opportunity; exact runtime layout and source/standalone tests are
  the present qualification boundary, not a universal ABI claim.

- Linux standalone library, run from a neutral directory: **37 /435**. The
  exact owned 127192KiB build directory was retired after a successful gate;
  build/run logs remain. This is not complete app packaging.
- Genuine published Git SDK14f/library628 exporter gate: **4 /15**; existing
  published-component regression suite **71 /553**. No local source shadowing.
- SDK option with the published independent Git library: **6 /73**.
- Existing dependency-resolution gate: **61 checks**, including actual
  correct/old/wrong providers, canonical src/resources identity and the new
  pre-shape-plan SDK3366 negative. No declaration-only provenance claim.
- Hosted CI wiring is added for these native gates. Hosted green is not
  inferred from local runs or from the earlier coordinate's historical status.

## Actual SDK queue + local Durable ABBA

One serial SDK worker/exporter/connection. 5k ended records per callback,
3 warmups +32 measured callbacks per process, off/on/on/off fresh processes.
Use the previous unchanged generator/oracle, explicitly converting equal
resource/scope/event maps to **distinct sorted roots** before queue ingestion.
Values compare equal to the shared representation, and native identity checks
confirm genuinely different roots. These are deterministic SDK-shaped ended
records, not live tracer/app or OTLP capture. Schema inference is included in
each payload's measured work; no precompiled manually supplied key schema.

Both modes keep the explicit byte codec, root cache, outer fragment cache,
nested fragments, typed columns, owned WAL preparation and same limits/receipts.
Only shape plans change. The observer counts construction (36: one preflight
plus35 actual payloads), and retains the latest planner for scalar budget stats;
it returns the actual native dispatcher directly, adding no per-map observer.
The enabled final planner contains one schema/123 key characters. Off constructs
none. Unit hit witnesses and real allocation changes remain separate evidence.

| Arm | Confirmed window rows/s | Estimated B/row | Callback p50 /p90 /p95 /p99 ms |
|---|---:|---:|---|
| Off |10,160|20,061|497.30 /593.55 /601.04 /630.01|
| On |11,770|18,532|401.99 /489.65 /510.04 /565.76|
| On |12,047|18,531|387.44 /489.42 /523.30 /575.21|
| Off |9,563|20,061|493.40 /650.65 /660.38 /685.79|

Pooled confirmed window **9,852 ->11,907 rows/s (+20.9%)**; controls differ
about5.9%. Both enabled processes are faster than both controls. Estimated
allocation falls about **7.6%** (~1530B/row). These are coarse runtime allocation
counter estimates, not object profiles or heap/RSS bounds. Small-sample p99 is
a maximum, not a population tail; reciprocal callback rates in `durable/summary.edn`
are explicitly NOT throughput quantiles or end-to-end span latency.

The independent older reader recovered and checked **700,000 rows** total:
every physical field, typed value/status, event representation and multiplicity.
Writers/SDK stats also check queue empty, zero dropped/failed rows, true callback
results, per-trace callback order, stream calls and trace/row counts. This is
native persistence/recovery evidence for this workload, not proof of all fault
histories or protocol conformance. No new publication/concurrency logic or
unchanged exhaustive model run was introduced.

All four exact owned stores were retired only after terminal writer/reader,
independent validator, copied/cmp receipts and source stability checks. Small
reports/hashes/counters remain; no database or native build artifact is retained.
The reused validator's static `source-overlay:false` label is not dependency
qualification: these writers explicitly used two local-root candidate libraries,
with actual source URIs/hashes recorded. Separate published-Git gates are needed
before claiming dependency-resolution qualification.

Native package26.9.0 checksum and official runtime checksum are enforced by
`run-durable.sh`; the older reader/SDK/JSON/support checkout SHAs are frozen and
must be clean. `durable/` holds all raw samples, full percentile summary, reader
receipts, validation and source postchecks. The sealed timed sources are the
initial implementation commits above; subsequent dependency/CI-only pin changes
must not be relabeled as that historical source identity.

This is an **additional gain on the unshared shape**, not a comparison with the
earlier shared-root14k-15k result or Rust/BB/JVM. The **25k inverse-p50 /20k
inverse-p99 target is unmet**. No S3, real app ingestion, Langfuse, sustained
arrival or deployment-wide tail qualification. Local DSL upload10Mbps cannot
qualify sustained S3 target throughput; use a hosted near-S3 lane later.

Next performance targets: remaining map/row materialization (an explicit codec
projection view or fused field plan), followed by matched WAL/native phase
allocation/copy attribution. Do not repeat the disproven policy-anchor tweak or
slower SQL-wrapped RowBinary route unchanged. Preserve these cumulative gains
and the original cross-platform/Cheshire/Rust baselines.
