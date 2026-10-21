# Compiled scalar-key emission experiment

This is a stock-Jolt library experiment, not a new exporter option/default.
Base e1f35c7 is the reviewed scalar-view stack subsequently merged by PR154
as f0aa75dd595dff82290fb2c19389d2a6ad02c1e6. No compiler or data.json change.

The additive trusted schema compiler encodes static keys once in both ASCII
and UTF-8 modes, preserving the original persistent template's traversal order
and value-slot ordinals. Each private fragment includes its comma/key/colon.
An invocation owns fresh value slots; no input map/tree or telemetry value is
retained by the schema. Existing plain-map and value-view constructors keep
their signatures and behavior. The native factory-maker's private test source
now has a second mode argument; the tests' isolated instrumentation follows it.

The new public experimental bridges are
`codec/native-compiled-scalar-text-view-factory` and
`shapes/native-compiled-view-plan-factory`. They are trusted Scheme bridges,
not an application-facing permission to skip SDK policy. Existing original
policy anchors, before/after admission, max8 schemas/8192 key characters,
128 keys/schema and original fallback remain unchanged. Compiled fragments add
bounded schema storage: JSON can use12 ASCII bytes per supplementary scalar,
and UTF-8 up to4; these are not free and character bounds are not an RSS limit.
The shared encoder uses the same depth/byte checks and independently owned
output. Direct copies only bypass the chunk loop when the whole key fits the
existing private buffer capacity, which is already clamped to the byte budget.

## Measured result, not a throughput claim

The first version copied every tiny key through the general byte copier.
Rich component p50 map/view/compiled:161.98/159.76/178.29ms per5000 rows;
compiled was slower and was not adopted. Final variant combines punctuation
and uses a direct block copy when capacity permits.

Final rich component (16 interleaved samples/arm, same candidate encoder):

| Arm | p50 / p90 / p95 / p99 ms | Estimated bytes/row |
| --- | --- | --- |
| Plain map |165.49 /189.77 /197.80 /197.80|11338|
| Value slots |161.99 /206.17 /232.76 /232.76|9801|
| Compiled keys |157.25 /185.67 /186.51 /186.51|9814|

About3% lower observed median than value slots, not a demonstrated causal
Durable throughput or tail improvement. p99 is the maximum of16, not a
population tail. Keep every GC-affected sample. It does NOT close25k/20k.
Schema compilation is included anew for each payload; allocation is slightly
higher. Policy/cache/typed projection and nested/outer encoding are included;
SDK queue, native/Durable I/O, application AOT and S3 are NOT. Context injection
uses a test-only redefinition while constructing the compiled context. Nothing
changes an application's runtime defaults or dependency pins.

Both screens compare full output bytes and changed-event controls. Both share
the candidate encoder across arms; neither isolates any regression from its
additional compiled-schema dispatch/helper factoring against the base encoder.
Do that comparison before proposing product adoption.

## Correctness

Stock Jolt0.8.23 source aggregate45tests/755assertions passes. Tests include
the previous scalar admission/declines, policy changes, empty/flat/HAMT maps,
reverse ordering, raw UTF-8 vs escaped Unicode, nontrivial slot order,
depth/byte limits, failure before requesting the next row, independent output,
and an exact8192 supplementary-scalar key crossing64KiB. The latter initially
exposed an incorrectly assumed six-byte-per-character compile budget, fixed to
allow surrogate pairs before qualification. Mechanism control compares the
real old output path's one template walk with the compiled path's zero walks.

No persistence/lifecycle/model logic changes or unchanged exhaustive run.
The same45tests/755assertions also pass in a default Linux standalone binary
executed from `/tmp`, outside checkout-relative resource resolution. Binary
SHA256ff67b4fa434ea6872f7b4dcbd90182f697fdb1401f6d51163de94afc391097a9.
Independent peer review and an initial base-encoder comparison are recorded
below. Neither establishes an adoption-worthy time win; keep this unadopted.

## Review and follow-up: still unadopted

Claude's read-only review of350135e approved the bounded implementation, found
no blockers, and correctly called out the missing base-encoder comparison and
small noisy sample. It did not run tests. Its "old path" finding means retained
APIs/semantics, not zero shared-path performance overhead. Codex ran the gates.
The subsequent screen-only baseline control/receipts were added after that
review; no reviewed implementation changed in the final tree.

An unchanged encoder control reads the actual clean e1f35c7 json.ss from the
separate scalar-view worktree; SHA256
`5b9cc251976491faa16c51d493e1fd32a90c13108cb1ea1f70e3facdd18e9166`.
The current loader references the additive schema function; the old source gets
only an unused false definition for that symbol. The compiled arm is excluded
from this control; SDK/plans/callers remain current. An assertion proves the
codec backend has not already been realized before swapping the source.

Control p50 map/view164.88/160.15ms; estimated allocation11306/9769 B/row.
Repeat candidate p50 map/view/compiled172.90/164.64/154.76ms, estimated
11338/9801/9813 B/row. These are sequential process runs, NOT an ABBA causal
comparison; they reveal a consistent estimated extra32B/row in common candidate
paths, whose cause is NOT established. They do not prove a time regression.

A further test replaced the new `bytes!` and `scalar-text!` local procedures
with hygienic syntax-rules expansions to test the captured-helper hypothesis.
It passed45/755 but did not remove the allocation difference (11338/9802/9813
B/row), and all arms were slower (208.13/201.15/199.15ms). No causal compiler or
closure diagnosis follows; the macro change was reverted exactly to reviewed
350135e source rather than carried as an unmeasured "fix". Small raw reports
are retained, and no unpaired process timing is promoted into a win.

Thus compiled-key emission remains a reviewed research branch, not a proposed
product adoption or a new exporter flag. Preserve the existing scalar-view
gain and pursue larger pass fusion/ordered overlap, not more retries of this
unchanged small mechanism. The old-helper/global registration approach was
not restored; stock Jolt remains sufficient for these library paths.

## Separate stock-runtime overlap diagnostic

`runtime-overlap/` contains a tiny C11 atomic-state helper and Jolt runner.
A sequential negative control performs work after native exit. In the positive
case, a future enters an actual collect-safe C call and publishes state1 from
inside C; the main thread computes and forces an observed GC while C still
reports state1. One collection and the full1million-iteration result were
observed before native exit. The runner awaits the exact worker and calls
shutdown-agents before exit. All arguments are scalar, no callbacks/managed
buffer loans/chDB handles, no production concurrency or model changes.

This demonstrates stock0.8.23 runtime potential, NOT chDB pointer lifetime,
data-race freedom, SDK settlement, bounded queues or Durable pipeline safety.
Existing driver stream FFI calls already carry blocking flags; no missing-flag
fix is justified. Initial diagnostic also passed/joined but waited Jolt's
documented60s future-pool keepalive before exiting. Explicit shutdown-agents
removed that diagnostic exit delay; neither run was killed/restarted on silence.
This is not a throughput benchmark, and its elapsed time is not a speed claim.

Reproduce from the repository root using the mandatory Chez wrapper and exact
stock Jolt binary:

```sh
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src" "incubator/codec/test" "research/scalar-view-exporter-20261020/app/src"]}' \
  research/compiled-scalar-keys-20261020/rich-screen.clj /absolute/result.edn
```

No large dataset/store is generated. Preserve small scripts, reports and logs;
retire only the exact successful standalone build after its receipt/hash.
