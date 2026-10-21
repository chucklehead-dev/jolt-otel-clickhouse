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

Both connection ownership constants have concrete executable instances.
Preparer termination, its successful ownership wait, exporter release,
publisher termination and final consumer cleanup are distinct observations.

## State and initial acceptance

Executable coverage includes reservation/publication/retirement, admission
fencing/cancellation, one positioned flush and one shutdown/close attempt.
These design checks do not qualify the native implementation or justify
enabling concurrent production export without runtime lifecycle controls.
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
  // without reaching backward into already-submitted stages. The SDK wait's
  // interruption loop cannot initiate cancellation AFTER its outcome. It CAN
  // race thread exit before the successful wait observation (isAlive/fence).
  pure def canCancel(s: PipelineState): bool =
    not(s.accepting) and not(s.cancelled) and
      not(Set(WaitSucceeded, WaitFailed).contains(s.workerWait))
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
  pure def preparerLive(s: PipelineState): bool =
    s.workers.get(Preparer) != WorkerTerminal
  pure def canReserve(s: PipelineState, batch: Batch): bool = and {
    preparerLive(s),
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
    preparerLive(s) and b.stage == Preparing and not(b.exporterAdmitted) and not(s.cancelled)
  }
  pure def applyAdmit(s: PipelineState, batch: Batch): PipelineState =
    {...s, batches: s.batches.set(batch,
      {...s.batches.get(batch), exporterAdmitted: true})}
  pure def canPrepare(s: PipelineState, batch: Batch, charge: int): bool = {
    val b = s.batches.get(batch)
    preparerLive(s) and b.stage == Preparing and b.exporterAdmitted and charge > 0 and
      charge <= b.reservedBytes
  }
  pure def applyPrepare(s: PipelineState, batch: Batch, charge: int): PipelineState =
    {...s, batches: s.batches.set(batch, {...s.batches.get(batch),
      stage: Prepared, reservedBytes: charge, encodedBytes: charge})}
  pure def canAbort(s: PipelineState, batch: Batch): bool =
    preparerLive(s) and Set(Preparing, Prepared).contains(s.batches.get(batch).stage)
  pure def applyAbort(s: PipelineState, batch: Batch): PipelineState =
    {...s, batches: s.batches.set(batch, {...s.batches.get(batch),
      stage: AwaitingRetirement, publication: Unconfirmed,
      reservedBytes: 0, encodedBytes: 0})}
  // Skip preparation failures, but never submit around an earlier unsubmitted
  // live batch. An already admitted writer request is NOT a confirmed result.
  pure def waitingSubmission(s: PipelineState): List[Batch] =
    s.admittedOrder.select(b => s.batches.get(b).publication == NotPublished)
  pure def canSubmit(s: PipelineState, batch: Batch): bool = and {
    preparerLive(s), not(s.cancelled), s.batches.get(batch).stage == Prepared,
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
    preparerLive(s) and b.stage == AwaitingRetirement and b.sdkResult == SdkPending
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
    preparerLive(s) and b.stage == AwaitingRetirement and b.sdkResult != SdkPending and
      b.exporterAdmitted and not(b.exporterReleased)
  }
  pure def applyRelease(s: PipelineState, batch: Batch): PipelineState =
    {...s, batches: s.batches.set(batch, {...s.batches.get(batch), exporterReleased: true})}
  pure def canRetire(s: PipelineState, batch: Batch): bool = {
    val b = s.batches.get(batch)
    preparerLive(s) and b.stage == AwaitingRetirement and b.sdkResult != SdkPending and
      (not(b.exporterAdmitted) or b.exporterReleased)
  }
  pure def applyRetire(s: PipelineState, batch: Batch): PipelineState =
    {...s, batches: s.batches.set(batch, {...s.batches.get(batch),
      stage: Retired, reservedBytes: 0, encodedBytes: 0})}
  // One bounded positioned flush: later acceptance is outside this snapshot,
  // but already-observed lifetime delivery failures cannot disappear.
  pure def canBeginFlush(s: PipelineState): bool =
    s.flush == FlushNotCalled and s.shutdown == ShutdownNotCalled
  pure def applyBeginFlush(s: PipelineState): PipelineState =
    {...s, flush: FlushPending,
      flushTargets: s.admittedOrder.foldl(Set(), (xs, b) => xs.union(Set(b)))}
  pure def flushConfirmed(s: PipelineState): bool =
    s.failedRows == 0 and s.flushTargets.forall(b =>
      s.batches.get(b).stage == Retired and s.batches.get(b).sdkResult == SdkSucceeded)
  pure def canFinishFlush(s: PipelineState, success: bool): bool =
    s.flush == FlushPending and (not(success) or flushConfirmed(s))
  pure def applyFinishFlush(s: PipelineState, success: bool): PipelineState =
    {...s, flush: if (success) FlushSucceeded else FlushFailed}
  pure def allAcceptedRetired(s: PipelineState): bool =
    s.rawQueue.length() == 0 and BATCHES.forall(b =>
      Set(NotAccepted, Retired).contains(s.batches.get(b).stage))
  pure def canBeginShutdown(s: PipelineState): bool = s.shutdown == ShutdownNotCalled
  pure def applyBeginShutdown(s: PipelineState): PipelineState =
    {...s, accepting: false, shutdown: ShutdownPending,
      workers: s.workers.set(Preparer, WorkerStopping)}
  // Termination can be premature: a dead preparer with unretired work is a
  // quarantine, not permission to prepare/retire on the shutdown caller.
  pure def canTerminatePreparer(s: PipelineState): bool =
    s.workers.get(Preparer) == WorkerStopping
  pure def applyTerminatePreparer(s: PipelineState): PipelineState =
    {...s, workers: s.workers.set(Preparer, WorkerTerminal)}
  pure def canBeginWait(s: PipelineState): bool =
    s.shutdown == ShutdownPending and s.workerWait == WaitNotStarted
  pure def applyBeginWait(s: PipelineState): PipelineState = {...s, workerWait: WaitPending}
  pure def canFinishWait(s: PipelineState, success: bool): bool =
    s.workerWait == WaitPending and
      (not(success) or s.workers.get(Preparer) == WorkerTerminal)
  pure def applyFinishWait(s: PipelineState, success: bool): PipelineState =
    {...s, workerWait: if (success) WaitSucceeded else WaitFailed,
      shutdown: if (success) s.shutdown else ShutdownFailed}
  // SDK admitted-operation quiescence is a trusted composition contract here;
  // this small state does not independently model caller admission counters.
  pure def canObserveSdkSettlement(s: PipelineState): bool =
    not(s.sdkSettlementObserved) and not(s.accepting) and allAcceptedRetired(s) and
      s.workerWait == WaitSucceeded and s.workers.get(Preparer) == WorkerTerminal
  pure def applyObserveSdkSettlement(s: PipelineState): PipelineState =
    {...s, sdkSettlementObserved: true}
  pure def canBeginExporterClose(s: PipelineState): bool =
    s.shutdown == ShutdownPending and s.sdkSettlementObserved and allAcceptedRetired(s) and
      s.workerWait == WaitSucceeded and s.workers.get(Preparer) == WorkerTerminal and
      s.exporterClose == CloseNotCalled
  pure def applyBeginExporterClose(s: PipelineState): PipelineState =
    {...s, exporterClose: ClosePending, exporterCloseCalls: s.exporterCloseCalls + 1}
  pure def canBeginOwnedConnectionClose(s: PipelineState): bool =
    CONNECTION_OWNER == ExporterOwned and s.exporterClose == ClosePending and
      s.connectionClose == CloseNotCalled
  pure def canBeginConsumerClose(s: PipelineState): bool =
    CONNECTION_OWNER == ConsumerOwned and s.sdkSettlementObserved and
      s.exporterClose == CloseSucceeded and s.shutdown != ShutdownPending and
      s.connectionClose == CloseNotCalled
  pure def applyBeginConsumerClose(s: PipelineState): PipelineState =
    {...s, connectionClose: ClosePending, connectionCloseCalls: s.connectionCloseCalls + 1,
      workers: s.workers.set(Publisher, WorkerStopping)}
  pure def canTerminatePublisher(s: PipelineState): bool =
    s.connectionClose == ClosePending and s.workers.get(Publisher) == WorkerStopping and
      not(BATCHES.exists(b => Set(PublicationQueued, Publishing).contains(s.batches.get(b).stage)))
  pure def applyTerminatePublisher(s: PipelineState): PipelineState =
    {...s, workers: s.workers.set(Publisher, WorkerTerminal)}
  pure def canFinishConnectionClose(s: PipelineState, success: bool): bool =
    s.connectionClose == ClosePending and
      (not(success) or s.workers.get(Publisher) == WorkerTerminal)
  pure def applyFinishConnectionClose(s: PipelineState, success: bool): PipelineState =
    {...s, connectionClose: if (success) CloseSucceeded else CloseFailed}
  pure def canFinishExporterClose(s: PipelineState, success: bool): bool =
    s.exporterClose == ClosePending and (not(success) or CONNECTION_OWNER == ConsumerOwned or
      (s.connectionClose == CloseSucceeded and s.workers.get(Publisher) == WorkerTerminal))
  pure def applyFinishExporterClose(s: PipelineState, success: bool): PipelineState =
    {...s, exporterClose: if (success) CloseSucceeded else CloseFailed}
  pure def canFinishShutdown(s: PipelineState): bool =
    s.shutdown == ShutdownPending and Set(CloseSucceeded, CloseFailed).contains(s.exporterClose)
  pure def applyFinishShutdown(s: PipelineState): PipelineState =
    {...s, shutdown: if (s.exporterClose == CloseSucceeded and s.failedRows == 0 and not(s.cancelled))
      ShutdownSucceeded else ShutdownFailed}
  pure def stableResources(s: PipelineState): bool =
    Set(ShutdownSucceeded, ShutdownFailed).contains(s.shutdown) and
      not(s.accepting) and allAcceptedRetired(s) and s.sdkSettlementObserved and
      s.workerWait == WaitSucceeded and s.exporterClose == CloseSucceeded and
      s.connectionClose == CloseSucceeded and WORKERS.forall(w => s.workers.get(w) == WorkerTerminal)
  pure def canObserveStableSettlement(s: PipelineState): bool =
    not(s.stableSettlementObserved) and stableResources(s)
  pure def applyObserveStableSettlement(s: PipelineState): PipelineState =
    {...s, stableSettlementObserved: true}
  pure def canCleanConsumer(s: PipelineState): bool =
    s.stableSettlementObserved and not(s.consumerCleaned)
  pure def applyCleanConsumer(s: PipelineState): PipelineState = {...s, consumerCleaned: true}
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
  action beginFlush = all {
    canBeginFlush(state), state' = applyBeginFlush(state),
  }
  action finishFlush(success: bool): bool = all {
    canFinishFlush(state, success), state' = applyFinishFlush(state, success),
  }
  action beginShutdown = all { canBeginShutdown(state), state' = applyBeginShutdown(state) }
  action terminatePreparer = all { canTerminatePreparer(state), state' = applyTerminatePreparer(state) }
  action beginWait = all { canBeginWait(state), state' = applyBeginWait(state) }
  action finishWait(success: bool): bool = all {
    canFinishWait(state, success), state' = applyFinishWait(state, success),
  }
  action observeSdkSettlement = all {
    canObserveSdkSettlement(state), state' = applyObserveSdkSettlement(state),
  }
  action beginExporterClose = all { canBeginExporterClose(state), state' = applyBeginExporterClose(state) }
  action beginOwnedConnectionClose = all {
    canBeginOwnedConnectionClose(state), state' = applyBeginConsumerClose(state),
  }
  action beginConsumerClose = all { canBeginConsumerClose(state), state' = applyBeginConsumerClose(state) }
  action terminatePublisher = all { canTerminatePublisher(state), state' = applyTerminatePublisher(state) }
  action finishConnectionClose(success: bool): bool = all {
    canFinishConnectionClose(state, success), state' = applyFinishConnectionClose(state, success),
  }
  action finishExporterClose(success: bool): bool = all {
    canFinishExporterClose(state, success), state' = applyFinishExporterClose(state, success),
  }
  action finishShutdown = all { canFinishShutdown(state), state' = applyFinishShutdown(state) }
  action observeStableSettlement = all {
    canObserveStableSettlement(state), state' = applyObserveStableSettlement(state),
  }
  action cleanConsumer = all { canCleanConsumer(state), state' = applyCleanConsumer(state) }
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
  val flushNeverConfusesAdmissionWithSuccess = state.flush == FlushSucceeded implies
    state.flushTargets.forall(b => state.batches.get(b).stage == Retired and
      state.batches.get(b).sdkResult == SdkSucceeded)
  val flushPendingWorkReached = state.flush == FlushPending and
    state.flushTargets.exists(b => state.batches.get(b).stage != Retired)
  val flushSucceededReached = state.flush == FlushSucceeded
  val flushFailedReached = state.flush == FlushFailed
  val exporterCloseIsOwned = state.exporterCloseCalls <= 1 and
    (state.exporterCloseCalls > 0 implies (state.sdkSettlementObserved and
      allAcceptedRetired(state) and state.workerWait == WaitSucceeded and
      state.workers.get(Preparer) == WorkerTerminal))
  val waitSuccessIsTermination = state.workerWait == WaitSucceeded implies
    state.workers.get(Preparer) == WorkerTerminal
  val resourceProofIsStable = state.stableSettlementObserved implies stableResources(state)
  val cleanupRequiresProof = state.consumerCleaned implies
    (state.stableSettlementObserved and stableResources(state))
  val closeIsSingleUse = state.connectionCloseCalls <= 1 and
    (state.connectionClose == CloseSucceeded implies state.workers.get(Publisher) == WorkerTerminal)
  val shutdownStartedReached = state.shutdown != ShutdownNotCalled
  val preparerTerminalReached = state.workers.get(Preparer) == WorkerTerminal
  val waitStartedReached = state.workerWait != WaitNotStarted
  val waitFailedReached = state.workerWait == WaitFailed
  val sdkSettledReached = state.sdkSettlementObserved
  val exporterCloseStartedReached = state.exporterCloseCalls == 1
  val exporterCloseFailedReached = state.exporterClose == CloseFailed
  val connectionCloseStartedReached = state.connectionCloseCalls == 1
  val connectionCloseFailedReached = state.connectionClose == CloseFailed
  val publisherTerminalReached = state.workers.get(Publisher) == WorkerTerminal
  val shutdownSucceededReached = state.shutdown == ShutdownSucceeded
  val shutdownFailedReached = state.shutdown == ShutdownFailed
  val stableProofReached = state.stableSettlementObserved
  val cleanupReached = state.consumerCleaned
  action coreStep = {
    nondet batch = BATCHES.oneOf()
    nondet charge = 1.to(RESERVATION).oneOf()
    nondet confirmed = Set(true, false).oneOf()
    any {
      accept(batch), fence, cancel, reserve(batch), admit(batch),
      prepare(batch, charge), abortPreparation(batch), submit(batch),
      startPublication(batch), complete(batch, confirmed), settle(batch),
      release(batch), retire(batch), beginFlush, finishFlush(confirmed),
    }
  }
  action step = {
    nondet success = Set(true, false).oneOf()
    any {
      coreStep, beginShutdown, terminatePreparer, beginWait, finishWait(success),
      observeSdkSettlement, beginExporterClose, beginOwnedConnectionClose, beginConsumerClose,
      terminatePublisher, finishConnectionClose(success), finishExporterClose(success),
      finishShutdown, observeStableSettlement, cleanConsumer,
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

  run positionedFlushTest = init.then(accept(A)).then(beginFlush).then(accept(B))
    .expect(flushPendingWorkReached and state.flushTargets == Set(A) and
      not(canFinishFlush(state, true)))
    .then(reserve(A)).then(admit(A)).then(prepare(A, 2)).then(submit(A))
    .then(startPublication(A)).then(complete(A, true)).then(settle(A)).then(release(A))
    .expect(not(canFinishFlush(state, true)))
    .then(retire(A)).then(finishFlush(true))
    .expect(flushSucceededReached and state.batches.get(B).stage == Queued and
      flushNeverConfusesAdmissionWithSuccess)

  run flushRetainsLifetimeFailureTest = init.then(accept(A)).then(reserve(A))
    .then(abortPreparation(A)).then(settle(A)).then(retire(A))
    .then(beginFlush)
    .expect(not(canFinishFlush(state, true)))
    .then(finishFlush(false)).expect(flushFailedReached and state.failedRows == 1)

  run flushAdmissionControlTest = init.then(accept(A)).then(beginFlush)
    .then(all { state' = {...state, flush: FlushSucceeded} })
    .expect(not(flushNeverConfusesAdmissionWithSuccess))
}
```

## Two-role shutdown and consumer cleanup

Shutdown fences SDK acceptance but allows the live preparer to finish accepted
work. A successful worker wait proves only preparer termination. It does not
retire an orphaned capability or stop the Durable publisher. Premature worker
termination with outstanding work deliberately reaches a quarantined state.
There is no post-worker caller that starts new preparation.

Exporter close starts only after the SDK wait succeeds and every accepted
batch is retired. The owned case then initiates connection close, which stops
the idle publisher; exporter close cannot succeed before that termination.
The borrowed case leaves the publisher running and requires the consumer's
separate connection close after SDK shutdown finishes. Stable resource proof
and final cleanup remain separate transitions after both roles are terminal.
Delivery failure can coexist with safe resource cleanup; a failed wait or
failed resource release cannot manufacture that proof.

The SDK's admitted public-operation counters are an explicit composition
assumption at `observeSdkSettlement`, not independently represented by this
small record. Existing SDK lifecycle models/tests remain responsible for that
boundary. This model covers one flush and one shutdown/close attempt; retry
policy, total process/native memory and arbitrary callback failures are not
newly proved. False flush can represent timeout/failure without resource proof.

```quint target/formal/quint/boundedPreparation_test.qnt +=
module boundedPreparationOwnedLifecycleTest {
  import boundedPreparationOwnership.* from "./boundedPreparation"
  import boundedPreparation(CONNECTION_OWNER = ExporterOwned).* from "./boundedPreparation"

  run ownedShutdownDrainsPositionedFlushTest = init.then(accept(A)).then(accept(B)).then(beginFlush)
    .then(reserve(A)).then(admit(A)).then(beginShutdown).then(beginWait)
    .expect(not(canAccept(state, C)) and not(canBeginExporterClose(state)))
    .then(prepare(A, 2)).then(submit(A)).then(startPublication(A))
    .then(reserve(B)).then(admit(B)).then(prepare(B, 3))
    .expect(overlapReached and not(canFinishWait(state, true)))
    .then(complete(A, true)).then(settle(A)).then(release(A)).then(retire(A))
    .then(submit(B)).then(startPublication(B)).then(complete(B, true))
    .then(settle(B)).then(release(B))
    .expect(not(canBeginExporterClose(state)))
    .then(retire(B)).then(finishFlush(true)).then(terminatePreparer).then(finishWait(true))
    .then(observeSdkSettlement).then(beginExporterClose)
    .expect(state.workers.get(Publisher) == WorkerRunning and
      not(canFinishExporterClose(state, true)))
    .then(beginOwnedConnectionClose)
    .expect(not(canFinishConnectionClose(state, true)) and not(canBeginExporterClose(state)))
    .then(terminatePublisher).then(finishConnectionClose(true)).then(finishExporterClose(true))
    .then(finishShutdown).then(observeStableSettlement).then(cleanConsumer)
    .expect(cleanupReached and shutdownSucceededReached and resourceProofIsStable and
      exporterCloseIsOwned and closeIsSingleUse and state.exportedRows == 2 and flushSucceededReached)

  run cancellationMayRacePreparerExitTest = init.then(beginShutdown).then(beginWait)
    .then(terminatePreparer)
    .expect(canCancel(state))
    .then(cancel).then(finishWait(true))
    .expect(state.cancelled and state.workerWait == WaitSucceeded)

  run cancellationCannotFollowWaitOutcomeTest = init.then(beginShutdown).then(beginWait)
    .then(terminatePreparer).then(finishWait(true))
    .expect(not(canCancel(state)))

  run deadPreparerCannotRetireOrphanTest = init.then(accept(A)).then(reserve(A))
    .then(admit(A)).then(prepare(A, 2)).then(submit(A)).then(startPublication(A))
    .then(beginShutdown).then(beginWait).then(terminatePreparer).then(finishWait(true))
    .then(complete(A, true))
    .expect(not(canSettle(state, A)) and not(canReserve(state, B)) and
      not(canObserveSdkSettlement(state)) and not(canBeginExporterClose(state)) and
      not(canCleanConsumer(state)) and slots(state).size() == 1 and state.exportedRows == 0)

  run failedWaitIsNotQuiescenceTest = init.then(beginShutdown).then(beginWait)
    .then(finishWait(false)).then(terminatePreparer)
    .expect(waitFailedReached and shutdownFailedReached and
      not(canObserveSdkSettlement(state)) and not(canBeginExporterClose(state)) and
      not(canCleanConsumer(state)))

  run connectionCloseFailureWithholdsProofTest = init.then(beginShutdown).then(beginWait)
    .then(terminatePreparer).then(finishWait(true)).then(observeSdkSettlement)
    .then(beginExporterClose).then(beginOwnedConnectionClose)
    .then(finishConnectionClose(false)).then(finishExporterClose(false)).then(finishShutdown)
    .expect(connectionCloseFailedReached and exporterCloseFailedReached and
      not(canObserveStableSettlement(state)) and not(canCleanConsumer(state)) and
      not(canBeginOwnedConnectionClose(state)))

  run deliveryFailureStillAllowsSafeCleanupTest = init.then(accept(A)).then(reserve(A))
    .then(abortPreparation(A)).then(settle(A)).then(retire(A))
    .then(beginShutdown).then(beginWait).then(terminatePreparer).then(finishWait(true))
    .then(observeSdkSettlement).then(beginExporterClose).then(beginOwnedConnectionClose)
    .then(terminatePublisher).then(finishConnectionClose(true)).then(finishExporterClose(true))
    .then(finishShutdown).then(observeStableSettlement).then(cleanConsumer)
    .expect(shutdownFailedReached and cleanupReached and state.failedRows == 1 and
      state.exportedRows == 0 and cleanupRequiresProof)

  run falseWaitProofControlTest = init.then(beginShutdown).then(beginWait)
    .then(all { state' = {...state, workerWait: WaitSucceeded} })
    .expect(not(waitSuccessIsTermination))

  run prematureExporterCloseControlTest = init.then(accept(A)).then(beginShutdown)
    .then(all { state' = {...state, exporterClose: ClosePending, exporterCloseCalls: 1} })
    .expect(not(exporterCloseIsOwned))
}

module boundedPreparationBorrowedLifecycleTest {
  import boundedPreparationOwnership.* from "./boundedPreparation"
  import boundedPreparation(CONNECTION_OWNER = ConsumerOwned).* from "./boundedPreparation"

  run borrowedConsumerCloseIsSeparateTest = init.then(accept(A)).then(reserve(A))
    .then(admit(A)).then(prepare(A, 2)).then(submit(A))
    .then(beginShutdown).then(beginWait).then(startPublication(A))
    .then(complete(A, true)).then(settle(A)).then(release(A)).then(retire(A))
    .then(terminatePreparer).then(finishWait(true)).then(observeSdkSettlement)
    .then(beginExporterClose).then(finishExporterClose(true)).then(finishShutdown)
    .expect(shutdownSucceededReached and state.workers.get(Publisher) == WorkerRunning and
      state.connectionCloseCalls == 0 and not(canObserveStableSettlement(state)) and
      not(canCleanConsumer(state)) and not(canBeginOwnedConnectionClose(state)))
    .then(beginConsumerClose)
    .expect(not(canFinishConnectionClose(state, true)) and not(canBeginConsumerClose(state)))
    .then(terminatePublisher).then(finishConnectionClose(true))
    .then(observeStableSettlement).then(cleanConsumer)
    .expect(cleanupReached and cleanupRequiresProof and closeIsSingleUse and
      state.exporterCloseCalls == 1 and state.connectionCloseCalls == 1)

  run borrowedConsumerCloseFailureTest = init.then(beginShutdown).then(beginWait)
    .then(terminatePreparer).then(finishWait(true)).then(observeSdkSettlement)
    .then(beginExporterClose).then(finishExporterClose(true)).then(finishShutdown)
    .then(beginConsumerClose).then(finishConnectionClose(false))
    .expect(not(canObserveStableSettlement(state)) and not(canCleanConsumer(state)))

  run livePublisherCleanupControlTest = init.then(beginShutdown).then(beginWait)
    .then(terminatePreparer).then(finishWait(true)).then(observeSdkSettlement)
    .then(beginExporterClose).then(finishExporterClose(true)).then(finishShutdown)
    .then(all { state' = {...state, stableSettlementObserved: true, consumerCleaned: true} })
    .expect(not(resourceProofIsStable) and not(cleanupRequiresProof))
}
```

Run the checks with `bash scripts/check-bounded-preparation-quint.sh`.
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

The positioned-flush/two-role lifecycle increment was separately checked on
Quint 0.32.0: 29 deterministic scenarios/controls; 10,000 core samples of at
most 40 steps with eight safety predicates and all 14 required witnesses;
3,000 full-lifecycle samples per ownership instance, at most 60 steps, with
13 safety predicates and all 14 lifecycle witnesses. No counterexample was
observed in these runs (input seed 141). Core overlap was reached in four
traces and failure behind publication in two; both also have explicit scenarios.
The full lifecycle sampler aggressively chooses shutdown/cancellation and
reached no overlap: it is not a replacement for that core coverage.

Successful shutdown/cleanup witnesses were 24/143 for the owned instance and
96/225 for the borrowed instance. An earlier gate failed its mandatory normal
shutdown witness because cancellation remained enabled after the ownership
wait outcome. The corrected guard preserves cancellation racing thread exit
BEFORE that outcome, and new controls check both sides. Independent Claude
source review and a focused follow-up reported no blockers; Codex ran the
checks. No exhaustive run, delivery/liveness guarantee, caller-counter proof,
native fault qualification or performance improvement follows from these results.
