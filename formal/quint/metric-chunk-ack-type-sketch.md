# Proposed bounded #100 acknowledgement extension — approval sketch

Requirements authority: exporter #100 and the existing
[Durable acknowledgement model](durable-export-ack.md). No executable model
transitions or production chunk submission have been added yet.

## Proposed scope

Two logical metric callers, each with one or two immutable prepared physical
chunks, share one writer FIFO. Plain Quint remains appropriate: coordination
is writer/exporter-owned shared state, not a distributed message protocol.
Opaque chunk IDs retain caller and ordinal; payload text, encoding loops,
timestamps, object-store CAS and lease internals remain out of scope.

Preparation is inside exporter admission. Failure during validation/encoding
admits no writer chunk. A logical success requires an observed confirmed or
reconciled receipt for every planned chunk. Failure after a confirmed prefix
does not roll it back. An ambiguous failing chunk is not assumed absent from
storage. Retrying the entire logical batch is a new attempt which can duplicate
already persisted rows; exactly-once and resumable retry are not promised.

## State shape for approval

One cohesive state record groups:

| Component | Proposed fields |
| --- | --- |
| Per logical caller | phase (`new`, `admitted`, `prepared`, `failed`, `all-confirmed`, `released`); ordered chunk plan; confirmed-receipt set; terminal result; release flag |
| Writer | ordered queue of atomic chunk, force-flush and close requests; committed chunk order; retained/uncertain chunk after publication failure; worker terminal state |
| Exporter lifecycle | admission fence; admitted/in-flight caller set; close requested; native connection released |

Maps are pre-populated for both callers. Chunk plan size is bounded to two for
exploration, not a product limit. Force-flush and each individual chunk request
remain separate FIFO positions; chunks belonging to different logical callers
may interleave. The queue must not collapse a whole multi-chunk export into a
new atomic transaction that the implementation does not supply.

Close fences new logical admission, but admitted callers may still prepare and
enqueue their remaining chunks. Native close cannot be queued/released merely
because the writer queue is momentarily empty: all admitted callers must reach
release and their queued work must settle. This explicitly fills the current
writer-only model's documented in-flight-drain gap.

## Properties and controls to add after approval

- Logical success implies every planned chunk has its own confirmed receipt;
  an error/ambiguous result is never counted as confirmation.
- A caller settles once. Successful chunk receipts remain owned by that caller
  even when another caller's chunks/force flush interleave.
- A failing logical call retains its already committed prefix. The model must
  not inherit the single-chunk invariant that any persisted caller cannot fail.
- Close never drops admitted preparation or queued chunks and never releases
  the native connection before exporter release/worker settlement.
- Reachable witnesses: two-chunk success, interleaved callers, failure after a
  confirmed prefix, ambiguous chunk, and close while an admitted caller has not
  yet enqueued its next chunk.
- Required mutants: success after only the first chunk; native close on empty
  queue while a logical caller is still preparing; and erasing a committed
  prefix on logical failure.

The lower-level native/storage contract remains trusted here; this extension
does not prove delivery, exactly-once, timing, durable format, or native crash
recovery. Implementation traces and full-value fresh-reader/fault-injection
tests remain separate required evidence.

After type/state sign-off, build the executable extension incrementally with
typecheck, deterministic tests and bounded sampled runs. Do not repeat unchanged
exhaustive checks or enable submission solely because the preparation tests pass.
