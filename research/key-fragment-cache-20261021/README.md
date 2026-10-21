# Bounded pre-escaped key cache: negative component screen

2026-10-21, based on exporter `f0aa75dd595dff82290fb2c19389d2a6ad02c1e6`,
after phase-cost checkpoint `0a85e6e`. Research only: no product source, library
option/default, dependency pin, compiler or model change. Official stock
Jolt0.8.23 /Chez10.4.1; SDK6cd57c86 /driver3314b79 /JSON0f51b991.

## Hypothesis and layering

Pre-escape each repeated key once, then copy its complete private byte fragment
instead of re-running the String escape loop. Unlike value reuse this works
with varying values and could benefit nested JSON as well as outer maps.
Values still follow original normalization, type/depth/output checks and order.

The candidate retains at most64 immutable key Strings of at most64 scalar
characters each, with at most1024bytes per fragment and64KiB retained fragment
bytes, plus bounded table/record overhead. These are not RSS bounds. Cache state
belongs to one encoding invocation or its explicit shared nested-text scope;
scope finish clears table/counters and retained producers reject. Inputs must
remain immutable during use, as in the existing codec. No input map wrapper,
tree, metadata, attribute values or mutable output buffer is cached.

The control compiles the EXACT unchanged `json.ss` resource. The candidate is
an exact-match-guarded research source transformation: replace only key writing,
extend candidate fragment state and add its cleanup. Both kernels share native
text/scalar-map/row types lexically, but have separate fragment-state types.
The SAME facade wrappers select either native kernel under the actual SDK-owned
converter and typed projector. No private SDK policy Var is rebound/snapshotted.
Neither modified encoder nor facade is installed globally or shipped as a
production feature. The source patch is deliberately not a proposed library API.

## Result: do not adopt

5k rich ended spans, same distinct sorted context shape as the phase work.
Original/candidate/candidate/original,16observations per arm, one source-mode
process; full normalization/projection/nested+outer encoding included. No
simultaneous local heavy workload or Claude review during the timed screen.

| Arm | p50 /p90 /p95 /p99 ms | Median estimated B/row |
| --- | --- | ---: |
| Unchanged kernel |196.54 /254.68 /398.29 /398.29|9,768.84|
| Key fragments |202.81 /301.54 /311.69 /311.69|9,883.56|

Median~3.2% slower; estimated allocation~1.2% higher. The lower sample maximum
is NOT a demonstrated tail improvement. p95 and p99 both equal the maximum of
16; no population claim. This supplies no case for production integration or
an additional unchanged native sweep. Do not compare these absolute source
timings to the previous process's projection-floor screen as a causal gain.

Every complete8,647,784-byte output matches the published codec's output;
changed event/attribute inputs change output in BOTH arms. The typed source
attribute also changes generic attributes, not an isolated typed-only oracle.
Raw scalar samples/counters are in `screen.edn`; counter snapshots are non-atomic
estimates, not allocation-site/RSS measurements. Checksums identify the exact
unchanged control resource and screened prototype sources.

## Functional and resource controls

`check.clj` passes15scalar cases, Unicode/escaping/numeric boundaries, ordered
maps, symbols/keywords, sorted and wide128-key maps, equal-but-distinct String
keys and changed values. Six exact/one-under byte-limit and chunk-scale cases
pass; failure stops before a later row. A completed output stays unchanged
across subsequent calls. Nested opaque text survives producer close, while a
retained producer rejects.

A separately compiled hit-only throwing mutant accepts a unique key but rejects
repeated content, witnessing the actual cache-hit branch. Another TEST-only
variant exposes only scope key-count/byte counters:128distinct keys admit64,
oversized keys leave counters unchanged, and finish clears them. An off-by-one
65-key mutant reaches65 and fails the64-entry budget oracle. Those instrumented
variants were NOT the timed candidate. This is bounded prototype evidence, not
full JSON conformance, every layout/platform, or a heap-alias proof.

## Reproduce and retain

From exporter root, use the mandatory Chez wrapper and pinned stock executable:

```sh
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src" "research/scalar-view-exporter-20261020/app/src"]}' \
  research/key-fragment-cache-20261021/check.clj
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src" "research/scalar-view-exporter-20261020/app/src"]}' \
  research/key-fragment-cache-20261021/screen.clj /absolute/owned/screen.edn
```

The prototype intentionally names this workspace's source file; these are
research scripts, not a standalone distribution. It creates no native build,
database, bulk evidence or telemetry capture. Keep the small sources, scalar
receipts and negative result; do not rerun it unchanged seeking a favorable
timing or advertise it as the fix for preprojection allocation.

## Next direction

The prior floor shows~6302B/row attributable to work excluded by preprojection,
including normalization, row construction and nested-text preparation. Faster
key formatting alone did not remove that work. The larger existing bounded
preparation/publication design (exporter#141) is still gated on explicit
Revision B type/scope approval; no concurrent implementation/model logic has
been added. Its existing one-SDK-preparer/one-Durable-publisher design keeps
ordered confirmed receipts and owned/borrowed cleanup, rather than treating
enqueue as delivery. That request is separate from this negative experiment.
No S3, SDK queue, Oscope, Langfuse, native throughput or target qualification.

Claude's bounded source/receipt review found no structural blockers and agreed
with keeping this unadopted (`review.txt`). Its alias wording needs correction:
encoded fragments are independent copies, but the table DOES retain references
to original immutable key Strings, as disclosed above; it does not copy those
Strings. This is safe only under the existing input-immutability contract, not
an absence-of-alias proof. Its word "interpreted" also must not be read as a
JVM/interpreter comparison: this is Jolt source mode with native Chez kernels,
not a standalone release-application qualification. Codex ran the controls and
screen; source review is neither execution nor a protocol/resource proof.
