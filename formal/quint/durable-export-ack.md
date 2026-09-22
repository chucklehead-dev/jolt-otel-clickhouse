# Durable exporter acknowledgement

This is the authoritative executable model for the persistence boundary in
`otel.exporter.chdb`. The `lmt` tangler extracts the Quint blocks into ignored
files under `target/formal/quint`; generated `.qnt` files are never edited.

## Scope and assumptions

Two concurrent non-empty OTel exports, `A` then `B`, are the actors. They
coordinate with chDB and Durable through writer-owned shared state, not
protocol messages, so plain Quint is clearer than Choreo. The model compares
the unsafe separated `execute!` / `flush!` requests with the proposed
writer-level atomic `execute-and-flush!` group primitive. It deliberately
abstracts serialization, SQL shape, retries, leases, and object-store CAS
reconciliation; those remain in the lower-level Durable model.

The red separated-request mutant makes the bug concrete: `A.execute`,
`B.execute`, and `A.flush` publish the group `{A, B}`. `B.flush` then sees an
empty WAL and settles B false even though B is already committed. The corrected
model admits A and B independently into a FIFO writer queue; each caller's
`execute-and-flush!` request is processed as one non-interleavable worker
transition and is settled only with its own terminal publication result. This
is a writer boundary, not an exporter mutex: other connection users may queue
behind an admitted request but cannot interleave inside it.

The modeled state is one cohesive record:

- `admitted`, `writtenOrder`, and `retainedWal` expose worker admission and
  uncommitted FIFO WAL order;
- `committedGroups` and `committedOrder` record caller-owned
  confirmed/reconciled publications and their replay order;
- `successes` and `failures` are caller-visible terminal outcomes;
- close snapshots fence new groups and force-flush calls after shutdown.

The primary invariants say that every success belongs to exactly one committed
group, no caller is settled twice, the fixed A-then-B replay order is retained,
terminal barrier failure/ambiguity retains its WAL, and shutdown fences both
admission and force flush. The mutant intentionally violates the separate
property that a committed caller cannot be settled false.

| Model boundary | Implementation boundary |
| --- | --- |
| `separateExecute*` / `separateFlush*` | existing separate `execute!` then `flush!` queue requests |
| `atomicGroup*` | proposed writer-level `execute-and-flush!` admission and terminal settlement |
| `atomicGroupFailure*` | retained-WAL terminal failure/ambiguity for every admitted group member |
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
per caller, queued force flush, and shutdown with queued callers. The red
interleave takes four separated requests. This bounded check is not a claim
about the lower-level distributed Durable protocol.

## Executable model

```quint target/formal/quint/durableExportAck.qnt +=
module durableExportAck {
  const ALLOW_SEPARATE_REQUESTS: bool

  type Caller = A | B

  type ExportState = {
    admitted: Set[Caller],
    queued: List[Caller],
    forceFlushQueued: bool,
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
    admittedAtClose: Set[Caller],
    forceFlushesAtClose: int,
  }

  var state: ExportState

  pure val initialState: ExportState = {
    admitted: Set(),
    queued: List(),
    forceFlushQueued: false,
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
    admittedAtClose: Set(),
    forceFlushesAtClose: 0,
  }

  pure val groupAB: Set[Caller] = Set(A, B)
  pure val orderAB: List[Caller] = List(A, B)

  pure def canAdmitA(s: ExportState): bool =
    not(s.closed) and s.admitted == Set()

  pure def applyAdmitA(s: ExportState): ExportState =
    { ...s,
      admitted: Set(A),
      queued: List(A) }

  pure def canAdmitB(s: ExportState): bool =
    not(s.closed) and not(s.forceFlushQueued) and s.admitted == Set(A) and
      (s.queued == List(A) or (s.queued == List() and s.successes == Set(A)))

  pure def applyAdmitB(s: ExportState): ExportState =
    { ...s,
      admitted: groupAB,
      queued: if (s.queued == List(A)) orderAB else List(B) }

  pure def canQueueForceFlush(s: ExportState): bool =
    not(s.closed) and not(s.forceFlushQueued)

  pure def applyQueueForceFlush(s: ExportState): ExportState =
    { ...s, forceFlushQueued: true }

  // Each process action is one writer request: execution, confirmed/reconciled
  // publication, and caller settlement cannot interleave with the next item.
  pure def canProcessAtomicA(s: ExportState): bool =
    not(s.closed) and (s.queued == List(A) or s.queued == orderAB)

  pure def applyAtomicA(s: ExportState): ExportState =
    { ...s,
      queued: if (s.queued == orderAB) List(B) else List(),
      writtenOrder: s.writtenOrder.concat(List(A)),
      committedOrder: s.committedOrder.concat(List(A)),
      committedGroups: s.committedGroups.union(Set(Set(A))),
      successes: s.successes.union(Set(A)) }

  pure def canProcessAtomicB(s: ExportState): bool =
    not(s.closed) and s.queued == List(B) and s.successes == Set(A)

  pure def applyAtomicBSuccess(s: ExportState): ExportState =
    { ...s,
      queued: List(),
      writtenOrder: s.writtenOrder.concat(List(B)),
      committedOrder: s.committedOrder.concat(List(B)),
      committedGroups: s.committedGroups.union(Set(Set(B))),
      successes: s.successes.union(Set(B)) }

  pure def applyAtomicBFailure(s: ExportState, ambiguous: bool): ExportState =
    { ...s,
      queued: List(),
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
      writtenOrder: List(A),
      retainedWal: List(A) }

  pure def applySeparateExecuteB(s: ExportState): ExportState =
    { ...s,
      admitted: groupAB,
      writtenOrder: orderAB,
      retainedWal: orderAB }

  pure def canSeparateFlushA(s: ExportState): bool =
    ALLOW_SEPARATE_REQUESTS and not(s.closed) and s.retainedWal == orderAB

  pure def applySeparateFlushA(s: ExportState): ExportState =
    { ...s,
      retainedWal: List(),
      committedOrder: orderAB,
      committedGroups: Set(groupAB),
      successes: Set(A) }

  pure def canSeparateFlushB(s: ExportState): bool =
    ALLOW_SEPARATE_REQUESTS and not(s.closed) and
      s.committedGroups == Set(groupAB) and s.successes == Set(A) and
      s.retainedWal == List()

  pure def applySeparateFlushB(s: ExportState): ExportState =
    { ...s, failures: Set(B) }

  pure def canForceFlushEmpty(s: ExportState): bool =
    not(s.closed) and s.forceFlushQueued and s.queued == List() and
      s.retainedWal == List()

  pure def applyForceFlushEmpty(s: ExportState): ExportState =
    { ...s,
      forceFlushQueued: false,
      forceFlushes: s.forceFlushes + 1,
      forceFlushSettlementSnapshot: s.successes.union(s.failures) }

  pure def canShutdown(s: ExportState): bool = not(s.closed)

  pure def applyShutdown(s: ExportState): ExportState =
    { ...s,
      closed: true,
      queued: List(),
      forceFlushQueued: false,
      failures: s.failures.union(s.admitted.exclude(s.successes)),
      admittedAtClose: s.admitted,
      forceFlushesAtClose: s.forceFlushes }

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

  action shutdown: bool = all {
    canShutdown(state),
    state' = applyShutdown(state),
  }

  action step: bool = any {
    admitA,
    admitB,
    queueForceFlush,
    atomicA,
    atomicBSuccess,
    atomicBFailure,
    atomicBAmbiguous,
    separateExecuteA,
    separateExecuteB,
    separateFlushA,
    separateFlushB,
    forceFlushEmpty,
    shutdown,
  }

  pure def committedGroupCount(s: ExportState, caller: Caller): int =
    s.committedGroups.filter(group => group.contains(caller)).size()

  pure def queuedMembers(s: ExportState): Set[Caller] =
    if (s.queued == List()) Set()
    else if (s.queued == List(A)) Set(A)
    else if (s.queued == List(B)) Set(B)
    else groupAB

  val successBelongsToExactlyOneCommittedGroup: bool =
    state.successes.forall(caller => committedGroupCount(state, caller) == 1)

  val noPreCommitSuccess: bool =
    (not(state.successes.contains(A)) or
      state.committedOrder == List(A) or state.committedOrder == orderAB) and
    (not(state.successes.contains(B)) or state.committedOrder == orderAB)

  val queuedCallersAreOnlyUnsettled: bool = and {
    state.successes.intersect(state.failures) == Set(),
    state.successes.intersect(queuedMembers(state)) == Set(),
    state.failures.intersect(queuedMembers(state)) == Set(),
    state.admitted == state.successes.union(state.failures).union(queuedMembers(state)),
  }

  val exactlyOneSettlementWhenQueueDrained: bool =
    state.queued == List() implies and {
      state.successes.intersect(state.failures) == Set(),
      state.admitted == state.successes.union(state.failures),
    }

  val fifoAdmissionOrder: bool =
    state.queued == List() or state.queued == List(A) or state.queued == orderAB or
      state.queued == List(B)

  val fifoReplayOrder: bool =
    state.committedOrder == List() or state.committedOrder == List(A) or
      state.committedOrder == orderAB

  val forceFlushWaitsForQueuedCallers: bool =
    state.forceFlushes == 0 or and {
      state.forceFlushSettlementSnapshot.exclude(
        state.successes.union(state.failures)) == Set(),
    }

  val shutdownSettlesQueuedCallers: bool =
    not(state.closed) or and {
      state.queued == List(),
      not(state.forceFlushQueued),
      state.admitted == state.successes.union(state.failures),
      state.admitted == state.admittedAtClose,
    }

  val noPostCloseGroupOrForceFlush: bool =
    not(state.closed) or and {
      state.admitted == state.admittedAtClose,
      state.forceFlushes == state.forceFlushesAtClose,
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
    queuedCallersAreOnlyUnsettled,
    exactlyOneSettlementWhenQueueDrained,
    fifoAdmissionOrder,
    fifoReplayOrder,
    terminalFailureRetainsWal,
    forceFlushWaitsForQueuedCallers,
    shutdownSettlesQueuedCallers,
    noPostCloseGroupOrForceFlush,
    committedCallerNeverFails,
  }

  val independentlySettledCallersReached: bool =
    state.committedGroups == Set(Set(A), Set(B)) and state.successes == groupAB

  val queuedBothCallersReached: bool =
    state.admitted == groupAB and state.queued == orderAB

  val atomicASettledBStillQueuedReached: bool =
    state.successes == Set(A) and state.queued == List(B)

  val atomicBFailureRetainedReached: bool =
    state.barrierFailed and state.retainedWal == List(B) and state.failures == Set(B)

  val atomicBAmbiguousRetainedReached: bool =
    state.barrierAmbiguous and state.retainedWal == List(B) and state.failures == Set(B)

  val forceFlushAfterQueuedCallersReached: bool =
    state.forceFlushes == 1 and state.successes == groupAB and state.queued == List() and
      state.forceFlushSettlementSnapshot == groupAB

  val shutdownSettlesQueuedCallersReached: bool =
    state.closed and state.failures == groupAB and state.queued == List()

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
        exactlyOneSettlementWhenQueueDrained,
      })

  run queuedAdmissionTest =
    init
      .then(admitA)
      .then(admitB)
      .expect(and {
        queuedBothCallersReached,
        exporterSafety,
        queuedCallersAreOnlyUnsettled,
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

  run forceFlushWaitsForQueuedCallersTest =
    init
      .then(admitA)
      .then(admitB)
      .then(queueForceFlush)
      .then(atomicA)
      .then(atomicBSuccess)
      .then(forceFlushEmpty)
      .expect(and {
        forceFlushAfterQueuedCallersReached,
        exporterSafety,
        forceFlushWaitsForQueuedCallers,
      })

  run shutdownSettlesQueuedCallersTest =
    init
      .then(admitA)
      .then(admitB)
      .then(queueForceFlush)
      .then(shutdown)
      .expect(and {
        shutdownSettlesQueuedCallersReached,
        exporterSafety,
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
