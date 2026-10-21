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

Initial cached and fused writers/readers have each verified175000 physical
rows. A second fused writer is complete; its reader is running. A final cached
control is still required to finish the planned A/B/B/A process sequence.
Do not pool these partial data into a final causal/tail claim.

First sequential pair, not yet a controlled causal conclusion:

| Arm | Window rows/s | p50 / p90 / p95 / p99 ms | Estimated B/row |
| --- | --- | --- | --- |
| Cached A1 |12183|411.72 /478.71 /485.84 /503.88|15737|
| Fused B1 |13511|364.94 /416.04 /459.06 /473.06|15867|

The observed difference is larger than the component result and could include
process drift, GC and native I/O. Do not assume it is purely escaping cost.
Samplep99 is maximum32, not a population tail; no25k/20k target qualification
or S3/Rust-relative result follows.

From app/, build with the mandatory wrapper/stock executable:

```sh
jolt build -m perf.fused-pipeline -o /absolute/owned/application
```

Run the SAME application from outside the checkout, with explicit local arm
and the qualified native library, fresh roots and `cached` / `fused-cached`.
Use the older independent reader command documented by this packet's metadata;
do not silently substitute a count-only reader or data.json parser mode.
