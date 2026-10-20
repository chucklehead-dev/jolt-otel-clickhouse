# Payload-scoped nested JSON fragment experiment

Base `6fac7448e386383d756cc2148780c0d81a32b985`, official stock Jolt 0.8.23,
Chez 10.4.1. No compiler or data.json patch, new concurrency or WAL change.

## Contract

`with-nested-text-writer` supplies a synchronous writer for one encoding scope.
It caches only independently copied, complete ASCII fragments from immutable
builtin maps with String keys and supported scalar values. Ordinary maps use
their root identity; sorted maps use their immutable tree after stock-ops
admission. No custom map traversal, converter or JSONWriter hook is skipped.
The inputs must stay immutable, and the writer must not be used concurrently
or across fibers. No thread-safety claim is made for a retained scope writer.

Bounds are 64 total insertions, 128KiB retained fragment bytes, and 4KiB per
fragment, excluding table/entry overhead. Keys are weak roots. Maps crossing
output chunks decline caching. A hit still enforces current byte/depth limits.
Scope return/throw clears the cache and retires the writer, even for empty
inputs. Encoded outputs have separate storage and remain valid after close.
This is not raw JSON insertion, a hard RSS bound or global data retention.

The exporter flag `:experimental-span-nested-fragment-cache?` defaults false
and requires its explicit, spans-only codec stack. One writer is installed
only around encoding, then closed before WAL preparation. A per-codec owner
token prevents using another exporter's nested writer during reentry. Binding
and cleanup restore the enclosing scope on normal/exceptional returns. Missing
capability and invalid options reject before storage acquisition. Default and
publication/acknowledgement paths are unchanged; Oscope does not enable it.

## Initial component result

Both arms keep the SDK normalization cache, outer byte-fragment cache and
row-view prototype. Shared/unshared inputs have equal values and identical
full output hashes; a native root-identity control distinguishes their sharing.
Producer source is unchanged; the screen substitutes exactly one row-vector
literal in an isolated function, never a global Var. No native/Durable I/O.
32 ABBA observations per cell; changed-event controls differ in both arms.

| Rich 5k-row shape | Scope off p50 | Scope on p50 | Estimated B/row off/on |
| --- | ---: | ---: | ---: |
| Shared |131.04ms|105.19ms|9,297 / 9,394|
| Unshared |218.19ms|204.91ms|12,855 / 13,053|

Shared median is about 20% lower and unshared about 6% lower. Allocation rises
slightly. Sample p95/p99 do not establish a tail improvement; the 32-sample p99
is a maximum, not a population-tail qualification. `component-v1.edn` retains
all p50/p90/p95/p99 and CPU/GC observations. Do not extrapolate to SDK targets.

## Actual SDK + local Durable ABBA, initial implementation

One SDK worker, one exporter, one writer; 5k rows/callback, 32 measured
callbacks and 3 warmups, four fresh off/on/on/off processes. Both arms keep the
SDK and outer wire caches and prepared WAL; **neither uses row views**. Both
writer and independent older implementation reader use official stock Jolt
0.8.23. Native package 26.9.0. No SDK/driver local-root override in the writer.
Only immutable fixture/test support is borrowed from the pinned older tree.

| Arm | Confirmed window rows/s | Estimated B/row |
| --- | ---: | ---: |
| Off |13,773|17,211|
| On |12,343|17,320|
| On |15,439|17,301|
| Off |11,749|17,229|

Pooled 12,680 -> 13,718 rows/s (+8.2%) versus 14.7% control drift. **No qualified
throughput win, population-tail or target claim.** Callback p50/p90/p95/p99 and
their explicitly labeled reciprocal batch rates are in `durable-v1/summary.edn`;
these are not end-to-end span latency or throughput-quantile SLOs. All 700,000
physical rows independently recovered and checked for every value and expected
multiplicity. Exact owned stores were removed after terminal writer/reader,
successful validator, copied/cmp receipts and source stability checks. Native
binaries/databases are not retained. Each store was around 0.75GiB, not four
simultaneously retained stores.

`run-durable.sh` uses committed fixture scripts, stock runtime and exact reader
SHAs, captures source hashes and cleans only validated owned synthetic stores.
It fails closed and retains an unsuccessful store. The validator is the
existing 20k-queue scaling validator, not the older 10k-queue binary validator.

## Current gates / next slice

Initial library source gate: 28 tests / 270 assertions. Exporter scope/owner
gate: 3 / 17. Covers actual cache walk/store budgets, scalar/sorted maps,
metadata-root reuse, changed values/ops, depth/size hits, close on return/throw,
nested scopes, output isolation, wrong-owner rejection, capability/option
preflight and preserved errors. No new model logic or unchanged exhaustive
model run was requested. Independent review and final packaging are pending.

The next allocation slice is scope-local scratch-buffer reuse: a nested call
still allocates a temporary 1KiB buffer/digit scratch before copying its fresh
result. Reuse must never expose scratch storage, retain stale output, bypass
small byte budgets or weaken the exclusive synchronous-use contract. Initial
results above must remain retained rather than be overwritten by later results.
