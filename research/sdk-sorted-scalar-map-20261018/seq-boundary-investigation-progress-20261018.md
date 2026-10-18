# Sorted sequence observer investigation

## Negative closure-removal result

Disposable topic `perf/seq-observer-closures-20261018` based on JSON a18ae06
replaced per-operation thunks with five factory-local specialized observers.
It kept every public seq/first/nth/key-policy/next call and flush/sync boundary.
Initial edit had an extra parenthesis; regression suite caught invalid syntax
(terminal 1). After correction, byte/sorted/segmented suites pass 38 tests /
621 assertions (terminal 0). The failed log is retained, not credited as pass.

Same actual SDK-produced 2000 event-rich spans, current SDK7878 and exportera1,
24 rotating pairs, byte parity before/after, all scalar helpers enabled:

| full encoding | baseline | specialized observers |
|---|---:|---:|
| p50 ms |127.953|128.073|
| managed B/row |30305.71|30304.36|
| caller CPU seconds, summed |3.256|3.219|

No useful speed/allocation gain. Source-level closure count did NOT predict
actual allocation; no claim about compiler inlining mechanism is established.
Not adopted into any production source branch or dependency pin. Source copy,
driver, raw counters and tests retained; owned disposable worktree removed.
The leftover local topic ref points at unchanged a18, not an implementation.

## New logical boundary accounting

Instrumented exact a18 byte-writer source, keeping all writer/sequence calls.
Every scenario's complete output equals the uninstrumented factory. Sizes
0/1/8/17/64/128, contexts ordinary nested string vs sorted map directly in batch.
No time measurement or physical-allocation/copy claim from these counters.

At 17 intrinsic string/integer entries:

- Both contexts: 103 sequence observations, 105 publication-flush calls,
  20 nonempty buffer flushes.
- Nested string: 219 UTF-8 output bytes; no batch-prefix publication or rewrite.
- Direct batch: 220 output bytes including newline, 106 row publications,
  11171 total published characters and 10951 resynchronized characters.

At 128 entries, direct batch publishes 715551 cumulative characters for 1830
output bytes and resynchronizes 713721 characters. This is a concrete growing
prefix-amplification problem for that direct sorted-map shape. It is NOT proof
that physical copies equal those logical counts, nor that this quadratic path
causes the event-rich Durable gap: its sorted map lives in NESTED JSON, where
this batch-prefix amplification does NOT occur. Do not conflate the paths.

## Next bounded optimization choice

1. For current event-rich path, reduce the COST of ordinary sorted traversal /
   incremental nested writer publication, while preserving custom operations,
   comparator non-invocation, live dispatch, prefix edits, exceptions, reentry.
2. For direct-batch generic maps, investigate a versioned/validated publication
   capability or equivalent content-coherent scheme to avoid repeated prefix
   reconstruction. Never skip callback visibility or edits based only on type.
3. Prefer a runtime-owned stock collection traversal capability (analogous to
   existing pmap-fold-seq-order), with custom-operation decline, over repeating
   private tree layout in another support library. Measure its upper bound
   before committing to a compiler migration. No product change adopted here.

No persistent store/native workload generated. Local qualified1ae/Chez10.4.1
runtime and all integration pins unchanged. No exhaustive model run: no Durable
protocol transition changed. The prior scalar helper gain remains cumulative.
