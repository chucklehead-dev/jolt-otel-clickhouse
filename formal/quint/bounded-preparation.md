# Bounded preparation and confirmed publication

This is the incremental executable companion to the approved
[Revision B scope](bounded-preparation-type-sketch.md). One SDK preparer and
one existing Durable publisher coordinate through shared state. Batch names
are symbolic; acceptance order, not their names, determines publication order.

The lower-level writer is trusted to return a confirmed/reconciled receipt or
an unconfirmed failure for its one atomic request. This model does not prove
lease/CAS correctness, encoding expansion bounds, recovery, delivery under
failure, timing, or native memory safety. Row/byte units are symbolic charges,
not an RSS bound. Raw queued spans are count-bounded only.

The types retain the approved lifecycle fields so later stages cannot silently
replace real publisher termination with an SDK worker wait. Both connection
ownership constants have concrete executable instances.

## State and initial acceptance

Current executable coverage is the reservation/publication/retirement core,
plus one bounded increment: an admission fence and a cancellation transition
that build on the existing `accepting`/`cancelled` fields. Shutdown and
positioned flush transitions are still to be added; their retained fields are
not evidence that those contracts have been checked. Do not enable concurrent
production export based on this partial gate.
Publication completion and SDK settlement are intentionally separate actions.
Fencing stops new SDK acceptance only; it does not itself block exporter
admission or writer submission of already-accepted batches. Cancellation may
only follow an active fence and additionally blocks NEW exporter admission
and NEW writer submission; it does not reach backward into stages already
past submission, so an already writer-admitted request still starts,
completes, settles, releases and retires. A batch that is admitted/prepared
but not yet submitted when cancellation lands can only abort, never submit;
it must never reach `SdkSucceeded`.

```quint target/formal/quint/boundedPreparation.qnt +=
module boundedPreparationOwnership {
  type ConnectionOwner = ExporterOwned | ConsumerOwned
}
module boundedPreparation {
  import boundedPreparationOwnership.*
  type Batch = A | B | C
  type Stage = NotAccepted | Queued | Preparing | Prepared | PublicationQueued | Publishing | AwaitingRetirement | Retired
  type Publication = NotPublished | PublicationPending | Confirmed | Unconfirmed
  type SdkResult = SdkPending | SdkSucceeded | SdkFailed
  type Worker = Preparer | Publisher
  type WorkerPhase = WorkerRunning | WorkerStopping | WorkerTerminal
  type WaitOutcome = WaitNotStarted | WaitPending | WaitSucceeded | WaitFailed
  const CONNECTION_OWNER: ConnectionOwner
  type CloseOutcome = CloseNotCalled | ClosePending | CloseSucceeded | CloseFailed
  type ShutdownOutcome = ShutdownNotCalled | ShutdownPending | ShutdownSucceeded | ShutdownFailed
  type FlushOutcome = FlushNotCalled | FlushPending | FlushSucceeded | FlushFailed
  type BatchState = {
    stage: Stage, rows: int, reservedBytes: int, encodedBytes: int,
    exporterAdmitted: bool, publication: Publication, sdkResult: SdkResult,
    exporterReleased: bool,
  }
  type PipelineState = {
    accepting: bool, cancelled: bool, admittedOrder: List[Batch],
    rawQueue: List[Batch], batches: Batch -> BatchState,
    publicationOrder: List[Batch], workers: Worker -> WorkerPhase,
    shutdown: ShutdownOutcome, workerWait: WaitOutcome,
    exporterClose: CloseOutcome, exporterCloseCalls: int,
    connectionClose: CloseOutcome, connectionCloseCalls: int,
    sdkSettlementObserved: bool, stableSettlementObserved: bool,
    consumerCleaned: bool, flush: FlushOutcome, flushTargets: Set[Batch],
    attemptedRows: int, exportedRows: int, failedRows: int,
  }
  pure val BATCHES = Set(A, B, C)
  pure val WORKERS = Set(Preparer, Publisher)
  pure val ROW_LIMIT = 2
  pure val BYTE_LIMIT = 8
  pure val RESERVATION = 4
  pure val emptyBatch: BatchState = {
    stage: NotAccepted, rows: 1, reservedBytes: 0, encodedBytes: 0,
    exporterAdmitted: false, publication: NotPublished,
    sdkResult: SdkPending, exporterReleased: false,
  }
  pure val initial: PipelineState = {
    accepting: true, cancelled: false, admittedOrder: List(), rawQueue: List(),
    batches: BATCHES.mapBy(_ => emptyBatch), publicationOrder: List(),
    workers: WORKERS.mapBy(_ => WorkerRunning), shutdown: ShutdownNotCalled,
    workerWait: WaitNotStarted, exporterClose: CloseNotCalled,
    exporterCloseCalls: 0, connectionClose: CloseNotCalled,
    connectionCloseCalls: 0, sdkSettlementObserved: false,
    stableSettlementObserved: false, consumerCleaned: false,
    flush: FlushNotCalled, flushTargets: Set(),
    attemptedRows: 0, exportedRows: 0, failedRows: 0,
  }
  pure def canAccept(s: PipelineState, batch: Batch): bool =
    s.accepting and s.batches.get(batch).stage == NotAccepted
  pure def applyAccept(s: PipelineState, batch: Batch): PipelineState = {
    ...s, admittedOrder: s.admittedOrder.append(batch),
    rawQueue: s.rawQueue.append(batch),
    batches: s.batches.set(batch, {...s.batches.get(batch), stage: Queued}),
  }
  // Fence stops NEW SDK acceptance only; already-queued/admitted batches keep
  // draining through reservation, admission, submission and publication.
  pure def canFence(s: PipelineState): bool = s.accepting
  pure def applyFence(s: PipelineState): PipelineState = {...s, accepting: false}
  // Cancellation may only follow an active fence; it additionally blocks NEW
  // exporter admission and NEW writer submission (canAdmit/canSubmit below),
  // without reaching backward into already-submitted stages.
  pure def canCancel(s: PipelineState): bool = not(s.accepting) and not(s.cancelled)
  pure def applyCancel(s: PipelineState): PipelineState = {...s, cancelled: true}
  pure def occupiesSlot(b: BatchState): bool = Set(
    Preparing, Prepared, PublicationQueued, Publishing, AwaitingRetirement
  ).contains(b.stage)
  pure def slots(s: PipelineState): Set[Batch] =
    BATCHES.filter(b => occupiesSlot(s.batches.get(b)))
  pure def chargedRows(s: PipelineState): int =
    slots(s).fold(0, (n, b) => n + s.batches.get(b).rows)
  pure def chargedBytes(s: PipelineState): int =
    slots(s).fold(0, (n, b) => n + s.batches.get(b).reservedBytes)
  pure def canReserve(s: PipelineState, batch: Batch): bool = and {
    s.workers.get(Preparer) == WorkerRunning,
    if (s.rawQueue.length() > 0) s.rawQueue.head() == batch else false,
    not(BATCHES.exists(b => Set(Preparing, Prepared).contains(s.batches.get(b).stage))),
    slots(s).size() < 2,
    chargedRows(s) + s.batches.get(batch).rows <= ROW_LIMIT,
    chargedBytes(s) + RESERVATION <= BYTE_LIMIT,
  }
  pure def applyReserve(s: PipelineState, batch: Batch): PipelineState = {
    ...s, rawQueue: s.rawQueue.tail(),
    attemptedRows: s.attemptedRows + s.batches.get(batch).rows,
    batches: s.batches.set(batch, {...s.batches.get(batch),
      stage: Preparing, reservedBytes: RESERVATION}),
  }
  pure def canAdmit(s: PipelineState, batch: Batch): bool = {
    val b = s.batches.get(batch)
    b.stage == Preparing and not(b.exporterAdmitted) and not(s.cancelled)
  }
  pure def applyAdmit(s: PipelineState, batch: Batch): PipelineState =
    {...s, batches: s.batches.set(batch,
      {...s.batches.get(batch), exporterAdmitted: true})}
  pure def canPrepare(s: PipelineState, batch: Batch, charge: int): bool = {
    val b = s.batches.get(batch)
    b.stage == Preparing and b.exporterAdmitted and charge > 0 and
      charge <= b.reservedBytes
  }
  pure def applyPrepare(s: PipelineState, batch: Batch, charge: int): PipelineState =
    {...s, batches: s.batches.set(batch, {...s.batches.get(batch),
      stage: Prepared, reservedBytes: charge, encodedBytes: charge})}
  pure def canAbort(s: PipelineState, batch: Batch): bool =
    Set(Preparing, Prepared).contains(s.batches.get(batch).stage)
  pure def applyAbort(s: PipelineState, batch: Batch): PipelineState =
    {...s, batches: s.batches.set(batch, {...s.batches.get(batch),
      stage: AwaitingRetirement, publication: Unconfirmed,
      reservedBytes: 0, encodedBytes: 0})}
  // Skip preparation failures, but never submit around an earlier unsubmitted
  // live batch. An already admitted writer request is NOT a confirmed result.
  pure def waitingSubmission(s: PipelineState): List[Batch] =
    s.admittedOrder.select(b => s.batches.get(b).publication == NotPublished)
  pure def canSubmit(s: PipelineState, batch: Batch): bool = and {
    not(s.cancelled), s.batches.get(batch).stage == Prepared,
    if (waitingSubmission(s).length() > 0)
      waitingSubmission(s).head() == batch else false,
  }
  pure def applySubmit(s: PipelineState, batch: Batch): PipelineState =
    {...s, batches: s.batches.set(batch, {...s.batches.get(batch),
      stage: PublicationQueued, publication: PublicationPending})}
  pure def queuedPublication(s: PipelineState): List[Batch] =
    s.admittedOrder.select(b => s.batches.get(b).stage == PublicationQueued)
  pure def canStart(s: PipelineState, batch: Batch): bool = and {
    s.workers.get(Publisher) == WorkerRunning,
    not(BATCHES.exists(b => s.batches.get(b).stage == Publishing)),
    if (queuedPublication(s).length() > 0)
      queuedPublication(s).head() == batch else false,
  }
  pure def applyStart(s: PipelineState, batch: Batch): PipelineState =
    {...s, publicationOrder: s.publicationOrder.append(batch),
      batches: s.batches.set(batch, {...s.batches.get(batch), stage: Publishing})}
  pure def canComplete(s: PipelineState, batch: Batch): bool =
    s.batches.get(batch).stage == Publishing
  pure def applyComplete(s: PipelineState, batch: Batch, confirmed: bool): PipelineState =
    {...s, batches: s.batches.set(batch, {...s.batches.get(batch),
      stage: AwaitingRetirement,
      publication: if (confirmed) Confirmed else Unconfirmed})}
  pure def canSettle(s: PipelineState, batch: Batch): bool = {
    val b = s.batches.get(batch)
    b.stage == AwaitingRetirement and b.sdkResult == SdkPending
  }
  pure def applySettle(s: PipelineState, batch: Batch): PipelineState = {
    val b = s.batches.get(batch)
    val success = b.publication == Confirmed
    {...s, exportedRows: s.exportedRows + (if (success) b.rows else 0),
      failedRows: s.failedRows + (if (success) 0 else b.rows),
      batches: s.batches.set(batch, {...b,
        sdkResult: if (success) SdkSucceeded else SdkFailed})}
  }
  pure def canRelease(s: PipelineState, batch: Batch): bool = {
    val b = s.batches.get(batch)
    b.stage == AwaitingRetirement and b.sdkResult != SdkPending and
      b.exporterAdmitted and not(b.exporterReleased)
  }
  pure def applyRelease(s: PipelineState, batch: Batch): PipelineState =
    {...s, batches: s.batches.set(batch, {...s.batches.get(batch), exporterReleased: true})}
  pure def canRetire(s: PipelineState, batch: Batch): bool = {
    val b = s.batches.get(batch)
    b.stage == AwaitingRetirement and b.sdkResult != SdkPending and
      (not(b.exporterAdmitted) or b.exporterReleased)
  }
  pure def applyRetire(s: PipelineState, batch: Batch): PipelineState =
    {...s, batches: s.batches.set(batch, {...s.batches.get(batch),
      stage: Retired, reservedBytes: 0, encodedBytes: 0})}
  var state: PipelineState
  action init = state' = initial
  action accept(batch: Batch): bool = all {
    canAccept(state, batch), state' = applyAccept(state, batch),
  }
  action fence = all {
    canFence(state), state' = applyFence(state),
  }
  action cancel = all {
    canCancel(state), state' = applyCancel(state),
  }
  action reserve(batch: Batch): bool = all {
    canReserve(state, batch), state' = applyReserve(state, batch),
  }
  action admit(batch: Batch): bool = all {
    canAdmit(state, batch), state' = applyAdmit(state, batch),
  }
  action prepare(batch: Batch, charge: int): bool = all {
    canPrepare(state, batch, charge), state' = applyPrepare(state, batch, charge),
  }
  action abortPreparation(batch: Batch): bool = all {
    canAbort(state, batch), state' = applyAbort(state, batch),
  }
  action submit(batch: Batch): bool = all {
    canSubmit(state, batch), state' = applySubmit(state, batch),
  }
  action startPublication(batch: Batch): bool = all {
    canStart(state, batch), state' = applyStart(state, batch),
  }
  action complete(batch: Batch, confirmed: bool): bool = all {
    canComplete(state, batch), state' = applyComplete(state, batch, confirmed),
  }
  action settle(batch: Batch): bool = all {
    canSettle(state, batch), state' = applySettle(state, batch),
  }
  action release(batch: Batch): bool = all {
    canRelease(state, batch), state' = applyRelease(state, batch),
  }
  action retire(batch: Batch): bool = all {
    canRetire(state, batch), state' = applyRetire(state, batch),
  }
  val accepted = state.admittedOrder.length() > 0
  val noEarlySuccess = BATCHES.forall(b =>
    state.batches.get(b).sdkResult == SdkSucceeded implies
      state.batches.get(b).publication == Confirmed)
  val boundedReservations = slots(state).size() <= 2 and
    chargedRows(state) <= ROW_LIMIT and chargedBytes(state) <= BYTE_LIMIT
  val coveredEncoding = BATCHES.forall(b => {
    val current = state.batches.get(b)
    current.encodedBytes >= 0 and current.reservedBytes >= current.encodedBytes
  })
  val orderedPublication = state.publicationOrder ==
    state.admittedOrder.select(b => state.publicationOrder.select(p => p == b).length() > 0)
  val onePublisher = BATCHES.filter(b => state.batches.get(b).stage == Publishing).size() <= 1
  val countersAgree = and {
    state.exportedRows == BATCHES.filter(b => state.batches.get(b).sdkResult == SdkSucceeded)
      .fold(0, (n, b) => n + state.batches.get(b).rows),
    state.failedRows == BATCHES.filter(b => state.batches.get(b).sdkResult == SdkFailed)
      .fold(0, (n, b) => n + state.batches.get(b).rows),
    state.attemptedRows == BATCHES.filter(b => not(Set(NotAccepted, Queued).contains(state.batches.get(b).stage)))
      .fold(0, (n, b) => n + state.batches.get(b).rows),
  }
  val retirementIsSettled = BATCHES.forall(b => {
    val current = state.batches.get(b)
    current.stage == Retired implies (current.sdkResult != SdkPending and
      (not(current.exporterAdmitted) or current.exporterReleased))
  })
  val overlapReached = BATCHES.exists(b => state.batches.get(b).stage == Publishing) and
    BATCHES.exists(b => Set(Preparing, Prepared).contains(state.batches.get(b).stage))
  val fullSlotsReached = slots(state).size() == 2 and state.rawQueue.length() > 0
  val publicationNotRetiredReached = BATCHES.exists(b =>
    state.batches.get(b).stage == AwaitingRetirement and
    state.batches.get(b).publication == Confirmed and
    state.batches.get(b).sdkResult == SdkPending)
  val allRetiredReached = BATCHES.forall(b => state.batches.get(b).stage == Retired)
  val failureReached = state.failedRows > 0
  val failureBehindPublicationReached = state.admittedOrder.indices().exists(i =>
    state.batches.get(state.admittedOrder.nth(i)).stage == Publishing and
      state.admittedOrder.indices().exists(j => j > i and
        state.batches.get(state.admittedOrder.nth(j)).sdkResult == SdkFailed))
  val fenceActiveReached = not(state.accepting)
  val cancellationActiveReached = state.cancelled
  val blockedAdmissionUnderCancellationReached = state.cancelled and
    BATCHES.exists(b => state.batches.get(b).stage == Preparing and
      not(state.batches.get(b).exporterAdmitted))
  val drainAfterCancellationReached = state.cancelled and
    BATCHES.exists(b => state.batches.get(b).stage == Retired and
      state.batches.get(b).sdkResult == SdkSucceeded)
  action step = {
    nondet batch = BATCHES.oneOf()
    nondet charge = 1.to(RESERVATION).oneOf()
    nondet confirmed = Set(true, false).oneOf()
    any {
      accept(batch), fence, cancel, reserve(batch), admit(batch),
      prepare(batch, charge), abortPreparation(batch), submit(batch),
      startPublication(batch), complete(batch, confirmed), settle(batch),
      release(batch), retire(batch),
    }
  }
}
module boundedPreparationOwned {
  import boundedPreparationOwnership.*
  import boundedPreparation(CONNECTION_OWNER = ExporterOwned).*
}
module boundedPreparationBorrowed {
  import boundedPreparationOwnership.*
  import boundedPreparation(CONNECTION_OWNER = ConsumerOwned).*
}
```

## Executable scenarios and controls

These tests demonstrate overlap, admission without success, held reservations
after confirmation, reuse for the third batch, and failure before exporter
admission. Mutation-specific assertions show the safety predicates reject
false success, out-of-order publication and an unbudgeted third preparation.
They are predicate sensitivity controls, not runtime fault-injection tests.
`noEarlySuccess` is a design obligation enforced by the settlement guard and
update, not an independent oracle for the implementation. In particular the
abstract writer's `Confirmed` outcome trusts the receipt classification; the
SDK adapter still needs runtime controls rejecting local/admission receipts.
Preparation failure disposes its encoded-byte charge immediately, while the
slot remains occupied through counter settlement and admission release.
Two further scenarios demonstrate the fence/cancellation increment: a
pre-writer-admission trace, where a batch cancelled before submission can
only abort and never reach `SdkSucceeded`, while a different not-yet-admitted
batch is blocked from new exporter admission; and a post-writer-admission
trace, where a batch already submitted before cancellation still starts,
completes, settles, releases and retires successfully, while a different
batch reserved after cancellation is blocked from both admission and
submission. Negative controls show cancellation guarded strictly behind an
active fence, and a fence alone blocking only new acceptance.

```quint target/formal/quint/boundedPreparation_test.qnt +=
module boundedPreparationCoreTest {
  import boundedPreparationOwnership.* from "./boundedPreparation"
  import boundedPreparation(CONNECTION_OWNER = ExporterOwned).* from "./boundedPreparation"

  run overlapAndRetirementTest = init
    .then(accept(A)).then(accept(B)).then(accept(C))
    .then(reserve(A)).then(admit(A)).then(prepare(A, 4)).then(submit(A))
    .expect(state.exportedRows == 0 and noEarlySuccess)
    .then(startPublication(A))
    .then(reserve(B)).then(admit(B)).then(prepare(B, 4))
    .expect(overlapReached and fullSlotsReached and not(canReserve(state, C)))
    .then(complete(A, true))
    .expect(publicationNotRetiredReached and state.exportedRows == 0 and
      not(canReserve(state, C)))
    .then(settle(A))
    .expect(state.exportedRows == 1 and not(canReserve(state, C)))
    .then(release(A))
    .expect(not(canReserve(state, C)))
    .then(retire(A))
    .expect(slots(state).size() == 1 and canSubmit(state, B) and not(canRetire(state, A)))
    .then(submit(B)).then(startPublication(B))
    .then(reserve(C)).then(admit(C)).then(prepare(C, 3))
    .expect(overlapReached and boundedReservations)
    .then(complete(B, true)).then(settle(B)).then(release(B)).then(retire(B))
    .then(submit(C)).then(startPublication(C)).then(complete(C, true))
    .then(settle(C)).then(release(C)).then(retire(C))
    .expect(allRetiredReached and state.exportedRows == 3 and
      state.failedRows == 0 and countersAgree and orderedPublication)

  run unadmittedFailureTest = init.then(accept(A)).then(reserve(A))
    .then(abortPreparation(A))
    .expect(chargedBytes(state) == 0 and slots(state).size() == 1)
    .then(settle(A))
    .expect(state.failedRows == 1 and not(canRelease(state, A)))
    .then(retire(A))
    .expect(retirementIsSettled and countersAgree and slots(state).size() == 0)

  run failureBehindPublicationTest = init.then(accept(A)).then(accept(B)).then(accept(C))
    .then(reserve(A)).then(admit(A)).then(prepare(A, 4))
    .then(submit(A)).then(startPublication(A))
    .then(reserve(B)).then(admit(B)).then(abortPreparation(B))
    .expect(chargedBytes(state) == 4 and slots(state).size() == 2)
    .then(settle(B))
    .expect(failureBehindPublicationReached and noEarlySuccess and countersAgree)
    .then(release(B)).then(retire(B))
    .then(complete(A, true)).then(settle(A)).then(release(A)).then(retire(A))
    .expect(state.exportedRows == 1 and state.failedRows == 1 and countersAgree)

  run failedPublicationTest = init.then(accept(A)).then(reserve(A))
    .then(admit(A)).then(prepare(A, 2)).then(submit(A))
    .expect(not(canAbort(state, A)) and not(canSettle(state, A)))
    .then(startPublication(A)).then(complete(A, false))
    .then(settle(A)).then(release(A)).then(retire(A))
    .expect(state.exportedRows == 0 and state.failedRows == 1 and countersAgree)

  run orderComesFromAcceptanceTest = init.then(accept(B)).then(accept(A))
    .then(reserve(B)).then(admit(B)).then(prepare(B, 1)).then(submit(B))
    .then(startPublication(B))
    .then(reserve(A)).then(admit(A)).then(prepare(A, 1)).then(submit(A))
    .expect(not(canStart(state, A)))
    .then(complete(B, true)).then(startPublication(A))
    .expect(state.publicationOrder == List(B, A) and orderedPublication)

  run earlySuccessControlTest = init.then(accept(A)).then(reserve(A))
    .then(admit(A)).then(prepare(A, 1)).then(submit(A))
    .then(all { state' = {...state, batches: state.batches.set(A,
      {...state.batches.get(A), sdkResult: SdkSucceeded})} })
    .expect(not(noEarlySuccess))

  run publicationOrderControlTest = init.then(accept(A)).then(accept(B))
    .then(all { state' = {...state, publicationOrder: List(B, A)} })
    .expect(not(orderedPublication))

  run reservationBypassControlTest = init.then(accept(A)).then(accept(B)).then(accept(C))
    .then(all { state' = {...state, batches: BATCHES.mapBy(b =>
      {...state.batches.get(b), stage: Preparing, reservedBytes: RESERVATION})} })
    .expect(not(boundedReservations))

  run unsettledRetirementControlTest = init.then(accept(A)).then(reserve(A))
    .then(admit(A)).then(all { state' = {...state, batches: state.batches.set(A,
      {...state.batches.get(A), stage: Retired})} })
    .expect(not(retirementIsSettled))

  run fenceAllowsDrainTest = init.then(accept(A))
    .then(fence)
    .expect(fenceActiveReached and not(canAccept(state, B)))
    .then(reserve(A)).then(admit(A)).then(prepare(A, 4)).then(submit(A))
    .then(startPublication(A)).then(complete(A, true)).then(settle(A))
    .then(release(A)).then(retire(A))
    .expect(state.exportedRows == 1 and state.batches.get(A).stage == Retired)

  run preSubmissionCancellationTest = init.then(accept(A)).then(accept(C))
    .then(reserve(A)).then(admit(A)).then(prepare(A, 4))
    .then(fence).then(cancel)
    .expect(cancellationActiveReached and not(canSubmit(state, A)))
    .then(abortPreparation(A)).then(settle(A)).then(release(A)).then(retire(A))
    .expect(state.exportedRows == 0 and state.failedRows == 1 and
      state.batches.get(A).sdkResult == SdkFailed)
    .then(reserve(C))
    .expect(not(canAdmit(state, C)) and blockedAdmissionUnderCancellationReached)

  run postSubmissionCancellationDrainTest = init.then(accept(A)).then(accept(B))
    .then(reserve(A)).then(admit(A)).then(prepare(A, 4)).then(submit(A))
    .then(fence).then(cancel)
    .expect(cancellationActiveReached and canStart(state, A))
    .then(startPublication(A))
    .then(complete(A, true)).then(settle(A)).then(release(A)).then(retire(A))
    .expect(drainAfterCancellationReached and state.exportedRows == 1 and
      state.batches.get(A).stage == Retired)
    .then(reserve(B))
    .expect(not(canAdmit(state, B)) and not(canSubmit(state, B)))

  run cancelRequiresFenceControlTest = init
    .expect(not(canCancel(state)))
    .then(fence)
    .expect(canCancel(state))

  run fenceBlocksNewAcceptanceControlTest = init.then(fence)
    .expect(not(canAccept(state, A)))
}
```

Run the core checks with `bash scripts/check-bounded-preparation-quint.sh`.
This command runs only sampled simulation and deterministic controls, never
Apalache. Update the model before changing a modeled implementation boundary.

The reservation/publication/retirement core was previously validated on
Quint 0.32.0 (nine deterministic scenarios/controls and 10,000 sampled traces
of at most 40 steps, seed 141, seven safety predicates and all seven required
witnesses reached). The fence/cancellation increment separately passed all
14 deterministic scenarios/controls and 10,000 sampled traces with the same
40-step bound and input seed 141. All 11 required witnesses were reached.
Cancellation is chosen aggressively by this sampler: only five traces reached
actual overlap, versus 1,194 in the prior unfenced core run. The explicit
scenarios retain the all-success overlap/reuse path and demonstrate native
publication starting AFTER cancellation for a request admitted BEFORE it.
These remain bounded design checks, not runtime qualification, a liveness
proof or exhaustive coverage of interleavings.
