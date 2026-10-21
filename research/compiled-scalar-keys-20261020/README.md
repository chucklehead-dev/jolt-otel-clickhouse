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
Independent peer review and a base-encoder comparison are still required before
proposing adoption. Prefer keeping this unadopted if larger work has more payoff.

Reproduce from the repository root using the mandatory Chez wrapper and exact
stock Jolt binary:

```sh
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src" "incubator/codec/test" "research/scalar-view-exporter-20261020/app/src"]}' \
  research/compiled-scalar-keys-20261020/rich-screen.clj /absolute/result.edn
```

No large dataset/store is generated. Preserve small scripts, reports and logs;
retire only the exact successful standalone build after its receipt/hash.
