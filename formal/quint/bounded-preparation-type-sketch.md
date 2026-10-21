# Bounded span preparation: proposed scope and types

**Revision B: type/scope and implementation scope approved by the user on
2026-10-21.** Exporter #141; approved proposal `1ac581f` (parent `05fa8b8`).
The adjacent `bounded-preparation.md` records incremental executable coverage;
approval does not itself qualify a concurrent production implementation.

## Why this is adjacent to the existing models

`durable-export-ack.md` protects the atomic writer request/confirmed receipt.
The SDK's `formal/quint/shutdown-lifecycle.md` protects one worker's ownership
and release. Neither represents a prepared batch outside the writer queue or
the distinction between submitting an atomic publication request and awaiting
its confirmed result. There are two EXISTING worker roles: SDK preparer and
Durable publisher, not two SDK workers.
Keep those contracts; add this small model rather than weakening or silently
relabeling either existing model.

## Proposed implementation boundary

One SDK processor owns one preparer; the existing Durable writer owns the
serialized publisher. Preparation of B may overlap confirmed publication of A.
Preparation itself stays serial;
this is not a pool invoking arbitrary conversion callbacks concurrently.
The SDK must participate: returning true from ordinary `export-spans!` after
just enqueueing work would falsely count admission as delivery. The concrete
optional preparation/publication interface is not committed by this sketch.

The current SDK drain loop is `take A -> export A -> count A -> take B`.
While synchronous export A blocks for confirmation, B is still in the SDK
queue and unavailable to an exporter-private helper. Preserving that return
contract alone is correct, but cannot overlap preparation of the NEXT SDK
batch. Intra-batch chunking is a different experiment, not silently equivalent
to this same-batch-size pipeline. The proposed SDK cooperation supplies the
next batch before waiting; it must NOT change what counts as success.

The driver already implements `enqueue-open-ticket! -> await-result-settled`
inside synchronous `execute-owned-and-flush!`. The proposed optional path
exposes a separately sealed confirmed-request ticket around that same atomic
writer operation; it does NOT decompose mutation and flush or start another
writer. Submission establishes only admission. A separate settled await
returns the same confirmed/reconciled receipt or throws the exact failure,
waiting through interruption after admission. Current synchronous driver
calls must retain their behavior. The new ticket cannot be substituted by a
`BufferedTicket`/`await-local-execution!` result.

Start opt-in, span-only, owned-byte Durable compact insertion with the explicit
stock-runtime codec and fixed schema/normalization configuration. No concurrent
schema administration, custom JSONWriter hooks, redefined converters, general
exporter auto-detection, metrics/logs overlap or default behavior change.
Unsupported requested configurations must fail before acquiring resources;
existing synchronous paths remain available. Do not force unrelated consumers
to resolve a new protocol/library dependency just to load their old path.
Only the owned-byte codec representation is admitted to this path; String and
no-codec fallbacks remain synchronous, not silently charged by the same budget.
Keep BOTH existing connection ownership modes: an exporter-created connection
and a borrowed consumer-owned connection (used by the actual SDK benchmark).
Borrowed exporter shutdown must not close the caller's connection or claim
its native writer has terminated. Model that additional consumer close before
cleanup, rather than narrowing qualification to an easier owned-only path.

Batch order comes from SDK queue removal, not thread scheduling or UUID order.
At most two batches may hold preparation/publication slots, counting a reserved
in-progress preparation, a ready prepared value, an executing publication and
a writer-admitted request awaiting execution, or a completed publication
awaiting settlement/release. Start with one publisher,
not parallel writer requests. The current private prepared value is NOT
single-use; the new adapter must explicitly claim/retire or abort it exactly
once and balance the exporter's in-flight admission on every path.

Slot-occupying stages are explicitly `Preparing`, `Prepared`,
`PublicationQueued`, `Publishing` and `AwaitingRetirement`; completed
publication does NOT itself free a slot.
`AwaitingRetirement` retains its reservation until SDK settlement and
exporter admission release finish. All other stages occupy no prepared slot.
The exporter submission seam must restore its actual dynamic configuration and
instrumentation-suppression bindings for the late snapshot/plan check and
writer admission; a saved typed-plan closure does not carry those bindings by
itself. The existing Durable worker retains its own backend context; do not
pretend all SDK thread-local configuration transfers across the queue.

## Actors, communication and bounds

This is shared-memory coordination through an SDK-owned queue/state, not a
distributed message protocol. Use plain Quint, one cohesive state record, and
three symbolic batches A/B/C so slot reuse and ordering can be exercised.
`workerWait` is the SDK preparer's ownership wait; the publisher's actual
termination is established through the existing Durable connection-close
path, not by reusing that single-worker wait result.
Small row/encoded-byte units stand for actual checked budgets. Reserve a slot
and the worst-case permitted prepared-byte charge BEFORE preparation starts;
shrink the reservation only after successful materialization. Failed preparation
returns no partial capability; dispose its reservation on the failure path.

The encoded-byte budget must account for retained SQL AND precomputed WAL,
not just SQL length. Runtime work must derive a conservative charge/limit from
actual representations before claiming a concrete memory budget. Symbolic
budgets in this model do not prove the encoder's numeric expansion bound.
Raw spans already waiting in the SDK queue are count-bounded, NOT byte-bounded;
arbitrary caller data, projection temporaries, allocator overhead and total RSS
remain outside that encoded-byte claim. Do not advertise a hard heap bound.

## Types only

```quint
module boundedPreparationTypes {
  type Batch = A | B | C
  type Stage = NotAccepted | Queued | Preparing | Prepared | PublicationQueued | Publishing | AwaitingRetirement | Retired
  type Publication = NotPublished | PublicationPending | Confirmed | Unconfirmed
  type SdkResult = SdkPending | SdkSucceeded | SdkFailed
  type Worker = Preparer | Publisher
  type WorkerPhase = WorkerRunning | WorkerStopping | WorkerTerminal
  type WaitOutcome = WaitNotStarted | WaitPending | WaitSucceeded | WaitFailed
  type ConnectionOwner = ExporterOwned | ConsumerOwned
  const CONNECTION_OWNER: ConnectionOwner
  type CloseOutcome = CloseNotCalled | ClosePending | CloseSucceeded | CloseFailed
  type ShutdownOutcome = ShutdownNotCalled | ShutdownPending | ShutdownSucceeded | ShutdownFailed
  type FlushOutcome = FlushNotCalled | FlushPending | FlushSucceeded | FlushFailed
  type BatchState = {
    stage: Stage,
    rows: int,
    reservedBytes: int,
    encodedBytes: int,
    exporterAdmitted: bool,
    publication: Publication,
    sdkResult: SdkResult,
    exporterReleased: bool,
  }
  type PipelineState = {
    accepting: bool,
    cancelled: bool,
    admittedOrder: List[Batch],
    rawQueue: List[Batch],
    batches: Batch -> BatchState,
    publicationOrder: List[Batch],
    workers: Worker -> WorkerPhase,
    shutdown: ShutdownOutcome,
    workerWait: WaitOutcome,
    exporterClose: CloseOutcome,
    exporterCloseCalls: int,
    connectionClose: CloseOutcome,
    connectionCloseCalls: int,
    sdkSettlementObserved: bool,
    stableSettlementObserved: bool,
    consumerCleaned: bool,
    flush: FlushOutcome,
    flushTargets: Set[Batch],
    attemptedRows: int,
    exportedRows: int,
    failedRows: int,
  }
  var state: PipelineState
}
```

This fence contains declarations only: no guards, actions, invariants or
initial state. Types may be extracted and typechecked before approval; a
successful typecheck is NOT a simulation or proof of the proposal.

## Observable transitions to keep separate after approval

SDK acceptance/fence; slot and budget reservation; exporter admission or
rejection; preparation finish/failure; atomic writer submission; ordered
publication start; actual
confirmed/unconfirmed writer outcome; SDK counter settlement; exporter
in-flight release; slot retirement; each worker's real termination; shutdown
wait success/failure; exporter close invocation/outcome; stable settlement
observation; consumer cleanup. Also a positioned flush snapshot and outcome.
Do not fuse publication return, SDK settlement, exporter release and consumer
cleanup into an atomic transition: the intermediate windows are the point.

Workers may fail or be interrupted; this is not permission to free an executing
publication or replay it. A failed/timed/interrupted join does not prove either
worker terminal and must not authorize exporter/consumer cleanup. A prepared
value can be aborted before writer admission; an admitted writer ticket must
be settled, not abandoned or resubmitted. Lower-level
Durable classification, lease/CAS, sealed-spool, recovery and reconciliation
remain the existing writer contract. A receipt of `:committed` or `:reconciled`
is confirmation; `:admitted` and `:executed-local` are not substitutes.

## Proposed properties and controls

- SDK success implies that SAME batch has confirmed publication; settlement
  and admission-release happen at most once. Confirmation does not imply
  exactly-once application delivery or eventual success under failure.
- Actual publication is an ordered subsequence of SDK acceptance, without
  duplicates; a preparation failure does not cause a later batch to overtake
  an earlier still-pending publication.
- At most two reserved slots and the configured row/encoded-byte reservation
  limits; no hidden third prepared batch before acquiring its reservation.
- Counters reconcile with per-batch terminal results, not queue admission;
  flush targets cannot be reported successful while any remains unresolved or
  failed. Existing lifetime failure evidence must remain visible.
- Fence new admission on shutdown; admitted work either confirms or explicitly
  fails, without release of active native users. No NEW writer submission after
  cancellation fences publication; already-admitted requests must still drain
  and settle, even if their native execution begins after that fence.
- Exporter shutdown invocation at most once, only after the SDK preparer
  REALLY terminated, its ownership wait succeeded, and all admitted
  capabilities retired. The existing Durable writer may still be alive and
  idle when shutdown begins: its close request drains/stops that worker.
  For an owned connection, completed exporter resource release requires
  confirmed writer termination;
  do not incorrectly require an idle writer thread to terminate before the
  very close call that stops it. Consumer cleanup additionally requires a
  stable settlement observation after release. For a borrowed connection, SDK
  settlement authorizes the consumer's separate connection close, not final
  database cleanup; connection-close completion and actual publisher
  termination must precede the final stable proof/cleanup. Delivery failure
  and resource quiescence stay distinct; false delivery is not necessarily a
  resource leak.

Reachability must demonstrate actual preparation/publication overlap, both
workers blocked by a full reservation, slot reuse for C, failure behind an
active A, flush with pending work, an admitted request executing after a
cancellation fence, shutdown in each stage and eventual normal cleanup in
BOTH ownership modes. Instantiate `CONNECTION_OWNER` explicitly in each
executable instance rather than leaving an unbound constant. Negative
controls: early success, B-before-A publication, reservation
bypass, double capability retirement and cleanup while either role remains
capable of accessing consumer resources.
Use deterministic traces plus bounded sampled `quint run`, and causal runtime
barriers/trace validators. No exhaustive run is proposed.

## Correspondence and next gate

- SDK queue/counters/worker ownership: `otel.sdk.export` and
  `otel.sdk.lifecycle` in SDK `26186ce`.
- Existing `BatchSpanProcessor`, `owned-settlement` and `await-owned-worker!`
  continue to represent ONE SDK worker. Driver close/result settlement must
  account for the separate Durable worker; SDK `.isAlive` alone is not proof
  of its termination. Require actual two-role shutdown/cancellation tests and
  capability retirement on preparation/submission/startup failure. Preserve
  other SDK owners' behavior and constructor-receipt cleanup guarantees.
- Exporter preparation/publication, bindings and admission release:
  `otel.exporter.chdb` at `d75a0ab` (runtime), through parent `05fa8b8`.
- Actual persistence: driver `3314b79`, same worker atomic
  `execute-owned-and-flush!` operation. The NEW internal confirmed-ticket
  submission/settled-await facade is explicitly in this model's scope;
  buffered-local ticket semantics and the lower-level protocol stay unchanged.
- Oscope cleanup remains a consumer obligation, not silently qualified here.

After approval, add a literate executable model and sampled/negative controls,
then implement the opt-in SDK/exporter path against it. Native validation must
exercise actual SDK counters/flush/shutdown, independent full-row recovery,
and p50/p90/p95/p99 completion cadence AND batch latency. The measured
pre-encoded ~32.4k rows/s baseline is not the proposed pipeline's throughput.
