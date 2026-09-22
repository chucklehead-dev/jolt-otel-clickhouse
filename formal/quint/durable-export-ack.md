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
model admits and settles `{A, B}` as one worker request. This is a writer
boundary, not an exporter mutex: other connection users cannot interleave
inside an admitted atomic group.

The modeled state is one cohesive record:

- `admitted`, `writtenOrder`, and `retainedWal` expose worker admission and
  uncommitted FIFO WAL order;
- `committedGroups` and `committedOrder` record a confirmed/reconciled
  publication and its replay order;
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

The corrected success/failure/ambiguity paths take one atomic group transition;
the red interleave takes four separated requests. This bounded check is not a
claim about the lower-level distributed Durable protocol.

## Executable model

```quint target/formal/quint/durableExportAck.qnt +=
module durableExportAck {
  const ALLOW_SEPARATE_REQUESTS: bool

  type Caller = A | B

  type ExportState = {
    admitted: Set[Caller],
    writtenOrder: List[Caller],
    retainedWal: List[Caller],
    committedOrder: List[Caller],
    committedGroups: Set[Set[Caller]],
    successes: Set[Caller],
    failures: Set[Caller],
    barrierFailed: bool,
    barrierAmbiguous: bool,
    forceFlushes: int,
    closed: bool,
    admittedAtClose: Set[Caller],
    forceFlushesAtClose: int,
  }

  var state: ExportState

  pure val initialState: ExportState = {
    admitted: Set(),
    writtenOrder: List(),
    retainedWal: List(),
    committedOrder: List(),
    committedGroups: Set(),
    successes: Set(),
    failures: Set(),
    barrierFailed: false,
    barrierAmbiguous: false,
    forceFlushes: 0,
    closed: false,
    admittedAtClose: Set(),
    forceFlushesAtClose: 0,
  }

  pure val groupAB: Set[Caller] = Set(A, B)
  pure val orderAB: List[Caller] = List(A, B)

  pure def canAdmitAtomic(s: ExportState): bool =
    not(s.closed) and s.admitted == Set()

  pure def applyAtomicSuccess(s: ExportState): ExportState =
    { ...s,
      admitted: groupAB,
      writtenOrder: orderAB,
      committedOrder: orderAB,
      committedGroups: Set(groupAB),
      successes: groupAB }

  pure def applyAtomicFailure(s: ExportState, ambiguous: bool): ExportState =
    { ...s,
      admitted: groupAB,
      writtenOrder: orderAB,
      retainedWal: orderAB,
      failures: groupAB,
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
    not(s.closed) and s.retainedWal == List()

  pure def applyForceFlushEmpty(s: ExportState): ExportState =
    { ...s, forceFlushes: s.forceFlushes + 1 }

  pure def canShutdown(s: ExportState): bool = not(s.closed)

  pure def applyShutdown(s: ExportState): ExportState =
    { ...s,
      closed: true,
      failures: s.failures.union(s.admitted.exclude(s.successes)),
      admittedAtClose: s.admitted,
      forceFlushesAtClose: s.forceFlushes }

  action init: bool = state' = initialState

  action atomicGroupSuccess: bool = all {
    canAdmitAtomic(state),
    state' = applyAtomicSuccess(state),
  }

  action atomicGroupFailure: bool = all {
    canAdmitAtomic(state),
    state' = applyAtomicFailure(state, false),
  }

  action atomicGroupAmbiguous: bool = all {
    canAdmitAtomic(state),
    state' = applyAtomicFailure(state, true),
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
    atomicGroupSuccess,
    atomicGroupFailure,
    atomicGroupAmbiguous,
    separateExecuteA,
    separateExecuteB,
    separateFlushA,
    separateFlushB,
    forceFlushEmpty,
    shutdown,
  }

  pure def committedGroupCount(s: ExportState, caller: Caller): int =
    s.committedGroups.filter(group => group.contains(caller)).size()

  val successBelongsToExactlyOneCommittedGroup: bool =
    state.successes.forall(caller => committedGroupCount(state, caller) == 1)

  val noPreCommitSuccess: bool =
    state.successes == Set() or state.committedOrder == orderAB

  val exactlyOneSettlement: bool = and {
    state.successes.intersect(state.failures) == Set(),
    state.admitted == state.successes.union(state.failures),
  }

  val fifoReplayOrder: bool =
    state.committedOrder == List() or state.committedOrder == orderAB

  val terminalFailureRetainsWal: bool =
    not(state.barrierFailed or state.barrierAmbiguous) or and {
      state.retainedWal == orderAB,
      state.failures == groupAB,
      state.committedGroups == Set(),
    }

  val shutdownFencesAdmissionAndForceFlush: bool =
    not(state.closed) or and {
      state.admitted == state.admittedAtClose,
      state.forceFlushes == state.forceFlushesAtClose,
    }

  val committedCallerNeverFails: bool =
    state.committedGroups.forall(group =>
      group.intersect(state.failures) == Set())

  val exporterSafety: bool = and {
    successBelongsToExactlyOneCommittedGroup,
    noPreCommitSuccess,
    exactlyOneSettlement,
    fifoReplayOrder,
    terminalFailureRetainsWal,
    shutdownFencesAdmissionAndForceFlush,
    committedCallerNeverFails,
  }

  val atomicGroupCommittedReached: bool =
    state.committedGroups == Set(groupAB) and state.successes == groupAB

  val atomicGroupFailureRetainedReached: bool =
    state.barrierFailed and state.retainedWal == orderAB and state.failures == groupAB

  val atomicGroupAmbiguousRetainedReached: bool =
    state.barrierAmbiguous and state.retainedWal == orderAB and state.failures == groupAB

  val forceFlushEmptyReached: bool = state.forceFlushes > 0

  val shutdownFencedReached: bool =
    state.closed and shutdownFencesAdmissionAndForceFlush

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

  run atomicGroupSuccessTest =
    init
      .then(atomicGroupSuccess)
      .expect(and {
        atomicGroupCommittedReached,
        exporterSafety,
        successBelongsToExactlyOneCommittedGroup,
        exactlyOneSettlement,
      })

  run atomicGroupFailureTest =
    init
      .then(atomicGroupFailure)
      .expect(and {
        atomicGroupFailureRetainedReached,
        exporterSafety,
        terminalFailureRetainsWal,
      })

  run atomicGroupAmbiguousTest =
    init
      .then(atomicGroupAmbiguous)
      .expect(and {
        atomicGroupAmbiguousRetainedReached,
        exporterSafety,
        terminalFailureRetainsWal,
      })

  run forceFlushShutdownFenceTest =
    init
      .then(forceFlushEmpty)
      .then(shutdown)
      .expect(and {
        forceFlushEmptyReached,
        shutdownFencedReached,
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
