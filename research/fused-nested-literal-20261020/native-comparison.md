# Actual confirmed Durable comparison

This packet extends the component experiment to real local Durable writes.
The complete throughput target remains unmet. No exporter option/default or
consumer dependency was changed by this research application.

## Fixed selection and workload

The application reuses the unchanged `perf.public-pipeline` fixture, schema
installation and public synchronous SDK exporter calls. Its consumer deps
explicitly select SDK6cd57c8, driver3314b79, crypto5effcc8 and JSON0f51b99,
plus checkout-local candidate codec/exporter. Actual `-Spath` resolved those
roots before build. Stock Jolt0.8.23 through the Chez10.4.1 wrapper; native
26.9.0 library SHA256a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557.

One default release executable is reused for all arms, SHA256
3e370efdd762447e46e3b23657edc912a0bd3edc9e849af6649c1ca8b45170d9.
Both arms wrap the public nested-text scope function with the same one-counter
shim. Cached delegates to its original function; fused-cached delegates to the
new literal scope. This is an explicit research selection seam, NOT an
uninstrumented production exporter option. No conversion/policy roots, private
exporter functions, persistence operations or acknowledgements are replaced.
Each arm actually observes35 scope calls (3 warmups +32 measured5k callbacks),
not just a label saying a feature was selected. Every callback must return true
after its existing confirmed publication; no preencoded statement is reused.

Direct SDK exporter protocol use is not a live tracer/SDK queue benchmark.
Diagnostics print once per batch inside the measured window. Every physical
store is fresh, retained until independent recovery passes, then retired with
small receipts/checksums. Workload is synthetic rich telemetry, not universal
OTel traffic. All arms share the candidate encoder; common-path overhead versus
the original encoder is not isolated by this comparison.

## Independent physical oracle

The unchanged older exporter/SDK reader runs in a separate process/worktree
at0af1008 with the original full-field GROUP BY/multiplicity/timestamp/type
oracle. Its benchmark-only driver storage helper comes from clean a92f60b
under `chdb-selection-upload-body-20261015/bench` (local arm explicitly forced).
The writer does NOT provide the reader's expected data/counts.

The reader requires a nil `json/*experimental-native-reader*`. First attempt
incorrectly selected JSON0f51, which has no such Var, and failed before any
recovery. It was not counted as success or "fixed" by deleting/interning the
guard. JSON3adc8c5 supplies it with defaultnil; the explicit reader override
preserves the guard and the complete existing oracle. Writer pins are unchanged.
This is independent recovery validation, not a recovery-speed comparison.

`validate-native.bb` checks every callback, geometry, window/rate/quantile
arithmetic, scope count and the independent physical result. A wrong-arm
negative control correctly exits1 with :label :arm; it cannot accept arbitrary
success metadata as the expected arm. Receipt validation supplements, not
replaces, the physical reader's actual data comparison.

## Progress

All four A/B/B/A writer and independent-reader processes completed successfully:
700000 physical rows, full fields/types/exact timestamps/multiplicities. Same
binary and scope-selection count in every run. No native/review work ran in
parallel with the timed writers. `summarize-native.bb` preserves ordered runs,
coarse CPU/GC deltas and explicit unqualified flags; it does not pool a causal
effect out of drifting controls.

Complete ordered observations, not a precise causal effect estimate:

| Arm | Window rows/s | p50 / p90 / p95 / p99 ms | Estimated B/row |
| --- | --- | --- | --- |
| Cached A1 |12183|411.72 /478.71 /485.84 /503.88|15737|
| Fused B1 |13511|364.94 /416.04 /459.06 /473.06|15867|
| Fused B2 |13585|361.97 /413.95 /442.30 /452.42|15866|
| Cached A2 |9953|473.96 /632.91 /671.96 /673.23|15737|

Both fused observations exceed both controls, and fused rates are close to
each other. But cached control rate drift is -18.3%; do not advertise a precise
percentage gain or assume the difference is purely escaping cost. Its CPU time
also increases7469->9285ms, whereas GC time increases503->644ms; GC alone does
not account for the ~2943ms wall-time increase. The cause is not established.
Fused allocation is about0.83% higher in the first pair. Samplep99 is maximum32,
not a population tail; inverse-p50 is only about13.7-13.8k rows/s in fused arms,
and inverse-p99 about10.6-11.1k. No25k/20k, S3 or Rust-relative target follows.

The first uncommitted summary had malformed nested parentheses that were still
legal because keyword/get-in default-value arities accepted them. It produced
invalid derived fractions, caught by inspecting the report; raw writer/reader
data were unaffected. The invalid report is explicitly retained, not promoted.
The corrected relative-change helper has known positive/negative/identity
numeric controls. Do not trust derived summary fields just because a script
parsed and returned0. No compiler/data.json defect is implied by this mistake.

All writer/reader data stores were selected for retirement only after their
exact processes exited0, receipt validation and copied-file comparisons. Only
small reports/logs/sources remain, plus one explicitly retained control binary
for a possible pristine-encoder regression comparison; build intermediates
are reproducible and selected for removal after that control is relocated.

The current comparison isolates old/new strategies WITHIN the candidate
library, not the original pre-change encoder. Before public adoption, check
original/default-path cost and freeze the actual final review packet. No new
exporter flag/default/PR was added just because these data look promising.

After these measured runs, a small frontend follow-up closes prior review
notes: keep literal/scope functions in the private Codec capability and smoke
both at backend construction, with scope cleanup in finally. New source gate
46/769 passes; the native encoder json.ss is byte-for-byte unchanged. The four
run timings belong to the pre-follow-up application executable/hash above,
not a fabricated fresh timing of the new preflight. Qualification still does
not establish an improvement against the original pre-change encoder.
The preflight follow-up also passes the actual neutral-directory standalone
46/769, not just source evaluation. Latest stable release APIs were refreshed
after the fixed experiment: Jolt still0.8.23 and chDB-core still26.9.0, so no
new stable runtime was deliberately left unused or swapped mid-comparison.

## Next larger synchronous target

SDK6cd57c8 `scalar-text-payload-projector` checks root cache, then its original
plain-map converter BEFORE learned sorted-map views. The plan compiler only
accepts sorted maps. Thus the fixture's ordinary span-attribute maps still
construct normalized maps/numeric text even when resource/scope sorted maps use
value-slot views. Extending the existing bounded representation bridge to
ordinary persistent maps could eliminate that remaining structure; this is
source-grounded opportunity, not an implemented/measured speedup. Keep original
policy anchors, native collision/iteration order, unsupported fallbacks, eight
schemas/character limits and the separate public plain-map-returning contract.
Do not reorder callbacks or silently use an opaque view as an ordinary map.

From app/, build with the mandatory wrapper/stock executable:

```sh
jolt build -m perf.fused-pipeline -o /absolute/owned/application
```

Run the SAME application from outside the checkout, with explicit local arm
and the qualified native library, fresh roots and `cached` / `fused-cached`.
Use the older independent reader command documented by this packet's metadata;
do not silently substitute a count-only reader or data.json parser mode.

The actual reader command (prefix it with the mandatory wrapper and explicit
stock Jolt executable), run from `exporter-stock-nested-fragments-20261019`:

```sh
jolt -Srepro -Sdeps '{:paths ["src" "bench" "test" "/home/chuck/ai-src/worktrees/chdb-selection-upload-body-20261015/bench"] :deps {org.clojure/data.json {:git/url "https://github.com/casselc/data.json.git" :git/sha "3adc8c5d3a57a30d18f62bad9d1c7a5c1f6857e0"}}}' \
  research/nested-fragments-20261019/independent-reader.clj /absolute/store
```

Set `SDK_STORAGE_ARM=local` and `JOLT_CHDB_LIB` explicitly for both writer and
reader; no cloud credentials, remote collector or external model is involved.
