# Durable exporter acknowledgement

This is the authoritative executable model for the persistence boundary in
`otel.exporter.chdb`. The `lmt` tangler extracts the Quint blocks into ignored
files under `target/formal/quint`; generated `.qnt` files are never edited.

## Scope and assumptions

Two concurrent non-empty OTel exports, `A` then `B`, are the actors. They
coordinate with chDB and Durable through writer-owned shared state, not
protocol messages, so plain Quint is clearer than Choreo. The model compares
the unsafe separated `execute!` / `flush!` requests with chDB's merged
writer-level atomic `execute-and-flush!` primitive (jolt-chdb #188). It deliberately
abstracts serialization, SQL shape, retries, leases, and object-store CAS
reconciliation; those remain in the lower-level Durable model.

The red separated-request mutant makes the bug concrete: `A.execute`,
`B.execute`, and `A.flush` publish the group `{A, B}`. `B.flush` then sees an
empty WAL and settles B false even though B is already committed. The corrected
model admits A and B independently into a FIFO writer queue of
`Atomic(caller)`, `ForceFlush`, and `Close` requests. Each caller's
`execute-and-flush!` request is processed as one non-interleavable worker
transition and is settled only with its own terminal publication result.
`Close` fences later admission when queued, but waits behind all previously
admitted work; it never clears or falsely settles that work. This is a writer
boundary, not an exporter mutex.

## Runtime integration boundary

chDB now implements the writer-level primitive (jolt-chdb #188); this model is
not evidence that the exporter has adopted or integration-trace-qualified it.
Today the exporter exercises one ordinary insert, persistence barrier, and
return path. The Hegel property therefore validates the checked-in ITF as a
model witness separately from that current single-export runtime contract. It
must not equate `Atomic`, `ForceFlush`, or `Close` model actions with exporter
calls until exporter adoption and integration trace tests land.

The modeled state is one cohesive record:

- `admitted`, `pending`, ordered request `queue`, `writtenOrder`, and
  `retainedWal` expose worker admission and uncommitted FIFO WAL order;
- `committedGroups` and `committedOrder` record caller-owned
  confirmed/reconciled publications and their replay order;
- `successes` and `failures` are caller-visible terminal outcomes;
- close snapshots fence new admission while allowing queued work to drain.

The primary invariants say that every success belongs to exactly one committed
group, no caller is settled twice, the fixed A-then-B replay order is retained,
terminal barrier failure/ambiguity retains its WAL, force flush executes at its
FIFO position, and close fences admission but processes only after earlier
callers settle. The mutant intentionally violates the separate property that a
committed caller cannot be settled false.

| Model boundary | Implementation boundary |
| --- | --- |
| `separateExecute*` / `separateFlush*` | existing separate `execute!` then `flush!` queue requests |
| `Atomic(A)` / `Atomic(B)` | merged writer-level `execute-and-flush!` admission and terminal settlement (#188) |
| `ForceFlush` / `Close` | positioned force flush and shutdown fencing behind admitted requests |
| event ordering | `durable-barrier-history-property` in `test/otel/exporter/chdb_property_test.clj` |

The corrected module disables separated requests. The mutant enables them; its
deterministic test and bounded Apalache check produce the required state in
which B is committed and false. Update this model before changing any named
implementation boundary.

## Commands

Run extraction, typechecking, deterministic tests, sampled reachability, the
corrected bounded check, and the required mutant counterexample with:

```sh
scripts/check-durable-export-quint.sh
```

The corrected path models separate A/B admission, one atomic worker transition
per caller, a positioned force flush, and a FIFO close with queued callers.
The red interleave takes four separated requests. This bounded check is not a
claim about the lower-level distributed Durable protocol.

## Executable model

```quint target/formal/quint/durableExportAck.qnt +=
module durableExportAck {
  const ALLOW_SEPARATE_REQUESTS: bool

  type Caller = A | B
  type Request = Atomic(Caller) | ForceFlush | Close

  type ExportState = {
    admitted: Set[Caller],
    pending: Set[Caller],
    queue: List[Request],
    writtenOrder: List[Caller],
    retainedWal: List[Caller],
    committedOrder: List[Caller],
    committedGroups: Set[Set[Caller]],
    successes: Set[Caller],
    failures: Set[Caller],
    barrierFailed: bool,
    barrierAmbiguous: bool,
    forceFlushes: int,
    forceFlushSettlementSnapshot: Set[Caller],
    closed: bool,
    closeProcessed: bool,
    admittedAtClose: Set[Caller],
  }

  var state: ExportState

  pure val initialState: ExportState = {
    admitted: Set(),
    pending: Set(),
    queue: List(),
    writtenOrder: List(),
    retainedWal: List(),
    committedOrder: List(),
    committedGroups: Set(),
    successes: Set(),
    failures: Set(),
    barrierFailed: false,
    barrierAmbiguous: false,
    forceFlushes: 0,
    forceFlushSettlementSnapshot: Set(),
    closed: false,
    closeProcessed: false,
    admittedAtClose: Set(),
  }

  pure val groupAB: Set[Caller] = Set(A, B)
  pure val orderAB: List[Caller] = List(A, B)

  pure def canAdmitA(s: ExportState): bool =
    not(s.closed) and s.admitted == Set()

  pure def applyAdmitA(s: ExportState): ExportState =
    { ...s,
      admitted: Set(A),
      pending: Set(A),
      queue: s.queue.append(Atomic(A)) }

  pure def canAdmitB(s: ExportState): bool =
    not(s.closed) and s.admitted == Set(A)

  pure def applyAdmitB(s: ExportState): ExportState =
    { ...s,
      admitted: groupAB,
      pending: s.pending.union(Set(B)),
      queue: s.queue.append(Atomic(B)) }

  pure def canQueueForceFlush(s: ExportState): bool =
    not(s.closed)

  pure def applyQueueForceFlush(s: ExportState): ExportState =
    { ...s, queue: s.queue.append(ForceFlush) }

  pure def canRequestClose(s: ExportState): bool = not(s.closed)

  pure def applyRequestClose(s: ExportState): ExportState =
    { ...s,
      closed: true,
      admittedAtClose: s.admitted,
      queue: s.queue.append(Close) }

  // Each process action is one writer request: execution, confirmed/reconciled
  // publication, and caller settlement cannot interleave with the next item.
  pure def canProcessAtomicA(s: ExportState): bool =
    s.queue.length() > 0 and s.queue.head() == Atomic(A)

  pure def applyAtomicA(s: ExportState): ExportState =
    { ...s,
      pending: s.pending.exclude(Set(A)),
      queue: s.queue.tail(),
      writtenOrder: s.writtenOrder.concat(List(A)),
      committedOrder: s.committedOrder.concat(List(A)),
      committedGroups: s.committedGroups.union(Set(Set(A))),
      successes: s.successes.union(Set(A)) }

  pure def canProcessAtomicB(s: ExportState): bool =
    s.queue.length() > 0 and s.queue.head() == Atomic(B) and s.successes == Set(A)

  pure def applyAtomicBSuccess(s: ExportState): ExportState =
    { ...s,
      pending: s.pending.exclude(Set(B)),
      queue: s.queue.tail(),
      writtenOrder: s.writtenOrder.concat(List(B)),
      committedOrder: s.committedOrder.concat(List(B)),
      committedGroups: s.committedGroups.union(Set(Set(B))),
      successes: s.successes.union(Set(B)) }

  pure def applyAtomicBFailure(s: ExportState, ambiguous: bool): ExportState =
    { ...s,
      pending: s.pending.exclude(Set(B)),
      queue: s.queue.tail(),
      writtenOrder: s.writtenOrder.concat(List(B)),
      retainedWal: s.retainedWal.concat(List(B)),
      failures: s.failures.union(Set(B)),
      barrierFailed: not(ambiguous),
      barrierAmbiguous: ambiguous }

  pure def canSeparateExecuteA(s: ExportState): bool =
    ALLOW_SEPARATE_REQUESTS and not(s.closed) and s.admitted == Set()

  pure def canSeparateExecuteB(s: ExportState): bool =
    ALLOW_SEPARATE_REQUESTS and not(s.closed) and
      s.admitted == Set(A) and s.retainedWal == List(A)

  pure def applySeparateExecuteA(s: ExportState): ExportState =
    { ...s,
      admitted: Set(A),
      pending: Set(A),
      writtenOrder: List(A),
      retainedWal: List(A) }

  pure def applySeparateExecuteB(s: ExportState): ExportState =
    { ...s,
      admitted: groupAB,
      pending: groupAB,
      writtenOrder: orderAB,
      retainedWal: orderAB }

  pure def canSeparateFlushA(s: ExportState): bool =
    ALLOW_SEPARATE_REQUESTS and not(s.closed) and s.retainedWal == orderAB

  pure def applySeparateFlushA(s: ExportState): ExportState =
    { ...s,
      pending: s.pending.exclude(Set(A)),
      retainedWal: List(),
      committedOrder: orderAB,
      committedGroups: Set(groupAB),
      successes: Set(A) }

  pure def canSeparateFlushB(s: ExportState): bool =
    ALLOW_SEPARATE_REQUESTS and not(s.closed) and
      s.committedGroups == Set(groupAB) and s.successes == Set(A) and
      s.retainedWal == List()

  pure def applySeparateFlushB(s: ExportState): ExportState =
    { ...s,
      pending: s.pending.exclude(Set(B)),
      failures: Set(B) }

  pure def canForceFlushEmpty(s: ExportState): bool =
    s.queue.length() > 0 and s.queue.head() == ForceFlush and
      s.retainedWal == List()

  pure def applyForceFlushEmpty(s: ExportState): ExportState =
    { ...s,
      queue: s.queue.tail(),
      forceFlushes: s.forceFlushes + 1,
      forceFlushSettlementSnapshot: s.successes.union(s.failures) }

  pure def canProcessClose(s: ExportState): bool =
    s.queue.length() > 0 and s.queue.head() == Close

  pure def applyClose(s: ExportState): ExportState =
    { ...s,
      queue: s.queue.tail(),
      closeProcessed: true }

  action init: bool = state' = initialState

  action admitA: bool = all {
    canAdmitA(state),
    state' = applyAdmitA(state),
  }

  action admitB: bool = all {
    canAdmitB(state),
    state' = applyAdmitB(state),
  }

  action queueForceFlush: bool = all {
    canQueueForceFlush(state),
    state' = applyQueueForceFlush(state),
  }

  action requestClose: bool = all {
    canRequestClose(state),
    state' = applyRequestClose(state),
  }

  action atomicA: bool = all {
    canProcessAtomicA(state),
    state' = applyAtomicA(state),
  }

  action atomicBSuccess: bool = all {
    canProcessAtomicB(state),
    state' = applyAtomicBSuccess(state),
  }

  action atomicBFailure: bool = all {
    canProcessAtomicB(state),
    state' = applyAtomicBFailure(state, false),
  }

  action atomicBAmbiguous: bool = all {
    canProcessAtomicB(state),
    state' = applyAtomicBFailure(state, true),
  }

  action separateExecuteA: bool = all {
    canSeparateExecuteA(state),
    state' = applySeparateExecuteA(state),
  }

  action separateExecuteB: bool = all {
    canSeparateExecuteB(state),
    state' = applySeparateExecuteB(state),
  }

  action separateFlushA: bool = all {
    canSeparateFlushA(state),
    state' = applySeparateFlushA(state),
  }

  action separateFlushB: bool = all {
    canSeparateFlushB(state),
    state' = applySeparateFlushB(state),
  }

  action forceFlushEmpty: bool = all {
    canForceFlushEmpty(state),
    state' = applyForceFlushEmpty(state),
  }

  action processClose: bool = all {
    canProcessClose(state),
    state' = applyClose(state),
  }

  action step: bool = any {
    admitA,
    admitB,
    queueForceFlush,
    requestClose,
    atomicA,
    atomicBSuccess,
    atomicBFailure,
    atomicBAmbiguous,
    separateExecuteA,
    separateExecuteB,
    separateFlushA,
    separateFlushB,
    forceFlushEmpty,
    processClose,
  }

  pure def committedGroupCount(s: ExportState, caller: Caller): int =
    s.committedGroups.filter(group => group.contains(caller)).size()

  val successBelongsToExactlyOneCommittedGroup: bool =
    state.successes.forall(caller => committedGroupCount(state, caller) == 1)

  val noPreCommitSuccess: bool =
    (not(state.successes.contains(A)) or
      state.committedOrder == List(A) or state.committedOrder == orderAB) and
    (not(state.successes.contains(B)) or state.committedOrder == orderAB)

  val pendingCallersAreOnlyUnsettled: bool = and {
    state.successes.intersect(state.failures) == Set(),
    state.successes.intersect(state.pending) == Set(),
    state.failures.intersect(state.pending) == Set(),
    state.admitted == state.successes.union(state.failures).union(state.pending),
  }

  val closeProcessesOnlyAfterCallerSettlement: bool =
    not(state.closeProcessed) or and {
      state.pending == Set(),
      state.successes.intersect(state.failures) == Set(),
      state.admitted == state.successes.union(state.failures),
    }

  val fifoReplayOrder: bool =
    state.committedOrder == List() or state.committedOrder == List(A) or
      state.committedOrder == orderAB

  val forceFlushRunsAtItsQueuePosition: bool =
    state.forceFlushes == 0 or and {
      state.forceFlushSettlementSnapshot.exclude(
        state.successes.union(state.failures)) == Set(),
    }

  val closeFencesFutureAdmission: bool =
    not(state.closed) or and {
      state.admitted == state.admittedAtClose,
    }

  val terminalFailureRetainsWal: bool =
    not(state.barrierFailed or state.barrierAmbiguous) or and {
      state.retainedWal == List(B),
      state.failures == Set(B),
      not(state.committedGroups.contains(Set(B))),
    }

  val committedCallerNeverFails: bool =
    state.committedGroups.forall(group =>
      group.intersect(state.failures) == Set())

  val exporterSafety: bool = and {
    successBelongsToExactlyOneCommittedGroup,
    noPreCommitSuccess,
    pendingCallersAreOnlyUnsettled,
    closeProcessesOnlyAfterCallerSettlement,
    fifoReplayOrder,
    terminalFailureRetainsWal,
    forceFlushRunsAtItsQueuePosition,
    closeFencesFutureAdmission,
    committedCallerNeverFails,
  }

  val independentlySettledCallersReached: bool =
    state.committedGroups == Set(Set(A), Set(B)) and state.successes == groupAB

  val queuedBothCallersReached: bool =
    state.admitted == groupAB and state.pending == groupAB and
      state.queue == List(Atomic(A), Atomic(B))

  val atomicASettledBStillQueuedReached: bool =
    state.successes == Set(A) and state.pending == Set(B) and
      state.queue == List(Atomic(B))

  val atomicBFailureRetainedReached: bool =
    state.barrierFailed and state.retainedWal == List(B) and state.failures == Set(B)

  val atomicBAmbiguousRetainedReached: bool =
    state.barrierAmbiguous and state.retainedWal == List(B) and state.failures == Set(B)

  val forceFlushBetweenAAndBReached: bool =
    state.forceFlushes == 1 and state.successes == Set(A) and state.pending == Set(B) and
      state.queue == List(Atomic(B)) and state.forceFlushSettlementSnapshot == Set(A)

  val closeQueuedBehindAdmittedWorkReached: bool =
    state.closed and not(state.closeProcessed) and state.pending == groupAB and
      state.queue == List(Atomic(A), Atomic(B), Close)

  val closeAfterAdmittedWorkReached: bool =
    state.closeProcessed and state.pending == Set() and state.successes == groupAB and
      state.queue == List()

  val separatedRequestsCommitBThenFailB: bool =
    state.committedGroups == Set(groupAB) and state.failures == Set(B)
}

module durableExportAckCorrected {
  import durableExportAck(ALLOW_SEPARATE_REQUESTS = false).*
}

module durableExportAckMutant {
  import durableExportAck(ALLOW_SEPARATE_REQUESTS = true).*
}
```

## Deterministic boundaries

```quint target/formal/quint/durableExportAckTest.qnt +=
module durableExportAckCorrectedTest {
  import durableExportAck(ALLOW_SEPARATE_REQUESTS = false).* from "./durableExportAck"

  run independentlySettledCallerTest =
    init
      .then(admitA)
      .then(admitB)
      .then(atomicA)
      .then(atomicBSuccess)
      .expect(and {
        independentlySettledCallersReached,
        exporterSafety,
        successBelongsToExactlyOneCommittedGroup,
        closeProcessesOnlyAfterCallerSettlement,
      })

  run queuedAdmissionTest =
    init
      .then(admitA)
      .then(admitB)
      .expect(and {
        queuedBothCallersReached,
        exporterSafety,
        pendingCallersAreOnlyUnsettled,
      })

  run atomicBFailureRetainsWalTest =
    init
      .then(admitA)
      .then(admitB)
      .then(atomicA)
      .then(atomicBFailure)
      .expect(and {
        atomicBFailureRetainedReached,
        exporterSafety,
        terminalFailureRetainsWal,
      })

  run atomicBAmbiguityRetainsWalTest =
    init
      .then(admitA)
      .then(admitB)
      .then(atomicA)
      .then(atomicBAmbiguous)
      .expect(and {
        atomicBAmbiguousRetainedReached,
        exporterSafety,
        terminalFailureRetainsWal,
      })

  run forceFlushIsPositionedBetweenCallersTest =
    init
      .then(admitA)
      .then(queueForceFlush)
      .then(admitB)
      .then(atomicA)
      .then(forceFlushEmpty)
      .expect(and {
        forceFlushBetweenAAndBReached,
        exporterSafety,
        forceFlushRunsAtItsQueuePosition,
      })

  run closeQueuesBehindAdmittedCallersTest =
    init
      .then(admitA)
      .then(admitB)
      .then(requestClose)
      .expect(and {
        closeQueuedBehindAdmittedWorkReached,
        exporterSafety,
      })

  run closeProcessesAfterAdmittedCallersTest =
    init
      .then(admitA)
      .then(admitB)
      .then(requestClose)
      .then(atomicA)
      .then(atomicBSuccess)
      .then(processClose)
      .expect(and {
        closeAfterAdmittedWorkReached,
        exporterSafety,
        closeProcessesOnlyAfterCallerSettlement,
      })
}

module durableExportAckMutantTest {
  import durableExportAck(ALLOW_SEPARATE_REQUESTS = true).* from "./durableExportAck"

  run separatedRequestsCommitBThenFailBMutantTest =
    init
      .then(separateExecuteA)
      .then(separateExecuteB)
      .then(separateFlushA)
      .then(separateFlushB)
      .expect(and {
        separatedRequestsCommitBThenFailB,
        state.committedOrder == orderAB,
        state.failures == Set(B),
        not(committedCallerNeverFails),
      })
}
```
