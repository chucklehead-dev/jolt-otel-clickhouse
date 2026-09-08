# Durable exporter acknowledgement

This is the authoritative executable model for the persistence boundary in
`otel.exporter.chdb`. The `lmt` tangler extracts the Quint blocks into ignored
files under `target/formal/quint`; generated `.qnt` files are never edited.

## Scope and assumptions

One logical OTel export is the actor. It coordinates with chDB and Durable
through shared state, not protocol messages, so plain Quint is clearer than
Choreo. The model deliberately splits insertion, the Durable barrier, and the
caller-visible return because failures can occur between those boundaries.
Serialization, SQL shape, retries, leases, and object-store CAS reconciliation
remain in the lower-level Durable model.
`flush-exporter!` is also outside this one-batch model: unlike a non-empty
export, an explicit force-flush may correctly find no pending WAL and return
success after an `:empty` Durable result.

The modeled state is one cohesive record:

- `phase` records the current export boundary;
- `wrote` distinguishes an empty successful export from a telemetry batch;
- `inserted` and `barrierAttempted` expose ordering;
- `durable` means the barrier committed or reconciled the batch;
- `returnedSuccess` and `returnedFailure` are caller-visible outcomes.

The primary invariant is: a non-empty successful return implies the batch is
durable. Supporting invariants require barriers to follow insertion, prevent an
empty batch from publishing, and keep success and failure disjoint.

| Model boundary | Implementation boundary |
| --- | --- |
| `insertSuccess` / `insertFailure` | `insert-json-rows!` in `src/otel/exporter/chdb.clj` |
| `barrierSuccess` / `barrierFailure` | `persistence-barrier!` and Durable `flush!` |
| `returnSuccess` | the `true` return from each signal exporter |
| event ordering | `durable-barrier-history-property` in `test/otel/exporter/chdb_property_test.clj` |

The corrected module forbids return before `Committed`. The mutant admits a
return directly from `Inserted`; its deterministic test and bounded Apalache
check are the red control. Update this model before changing any of the named
implementation boundaries.

## Commands

Run extraction, typechecking, deterministic tests, sampled reachability, the
corrected bounded check, and the required mutant counterexample with:

```sh
scripts/check-durable-export-quint.sh
```

The bound is three transitions: insert, barrier, and return. This is exhaustive
for the one-operation model, not a claim about the lower-level distributed
Durable protocol.

## Executable model

```quint target/formal/quint/durableExportAck.qnt +=
module durableExportAck {
  const ALLOW_PRE_BARRIER_ACK: bool

  type Phase = Idle | Inserted | Committed | Succeeded | Failed

  type ExportState = {
    phase: Phase,
    wrote: bool,
    inserted: bool,
    barrierAttempted: bool,
    durable: bool,
    returnedSuccess: bool,
    returnedFailure: bool,
  }

  var state: ExportState

  pure val initialState: ExportState = {
    phase: Idle,
    wrote: false,
    inserted: false,
    barrierAttempted: false,
    durable: false,
    returnedSuccess: false,
    returnedFailure: false,
  }

  pure def canStart(s: ExportState): bool = s.phase == Idle

  pure def applyEmptySuccess(s: ExportState): ExportState =
    { ...s, phase: Succeeded, returnedSuccess: true }

  pure def applyInsertSuccess(s: ExportState): ExportState =
    { ...s, phase: Inserted, wrote: true, inserted: true }

  pure def applyFailure(s: ExportState): ExportState =
    { ...s, phase: Failed, returnedFailure: true }

  pure def applyInsertFailure(s: ExportState): ExportState =
    applyFailure({ ...s, wrote: true })

  pure def canBarrier(s: ExportState): bool = s.phase == Inserted

  pure def applyBarrierSuccess(s: ExportState): ExportState =
    { ...s, phase: Committed, barrierAttempted: true, durable: true }

  pure def applyBarrierFailure(s: ExportState): ExportState =
    applyFailure({ ...s, barrierAttempted: true })

  pure def canReturnSuccess(s: ExportState): bool =
    s.phase == Committed or (ALLOW_PRE_BARRIER_ACK and s.phase == Inserted)

  pure def applyReturnSuccess(s: ExportState): ExportState =
    { ...s, phase: Succeeded, returnedSuccess: true }

  action init: bool = state' = initialState

  action emptySuccess: bool = all {
    canStart(state),
    state' = applyEmptySuccess(state),
  }

  action insertSuccess: bool = all {
    canStart(state),
    state' = applyInsertSuccess(state),
  }

  action insertFailure: bool = all {
    canStart(state),
    state' = applyInsertFailure(state),
  }

  action barrierSuccess: bool = all {
    canBarrier(state),
    state' = applyBarrierSuccess(state),
  }

  action barrierFailure: bool = all {
    canBarrier(state),
    state' = applyBarrierFailure(state),
  }

  action returnSuccess: bool = all {
    canReturnSuccess(state),
    state' = applyReturnSuccess(state),
  }

  action step: bool = any {
    emptySuccess,
    insertSuccess,
    insertFailure,
    barrierSuccess,
    barrierFailure,
    returnSuccess,
  }

  val acknowledgementIsDurable: bool =
    not(state.returnedSuccess and state.wrote) or state.durable

  val barrierFollowsInsert: bool =
    not(state.barrierAttempted) or state.inserted

  val emptyBatchSkipsPersistence: bool =
    state.wrote or and {
      not(state.inserted),
      not(state.barrierAttempted),
      not(state.durable),
    }

  val resultIsUnambiguous: bool =
    not(state.returnedSuccess and state.returnedFailure)

  val exporterSafety: bool = and {
    acknowledgementIsDurable,
    barrierFollowsInsert,
    emptyBatchSkipsPersistence,
    resultIsUnambiguous,
  }

  val emptySuccessReached: bool =
    state.phase == Succeeded and not(state.wrote)

  val insertFailureReached: bool =
    state.phase == Failed and state.wrote and not(state.inserted)

  val barrierFailureReached: bool =
    state.phase == Failed and state.inserted and state.barrierAttempted

  val durableSuccessReached: bool =
    state.returnedSuccess and state.wrote and state.durable
}

module durableExportAckCorrected {
  import durableExportAck(ALLOW_PRE_BARRIER_ACK = false).*
}

module durableExportAckMutant {
  import durableExportAck(ALLOW_PRE_BARRIER_ACK = true).*
}
```

## Deterministic boundaries

```quint target/formal/quint/durableExportAckTest.qnt +=
module durableExportAckCorrectedTest {
  import durableExportAck(ALLOW_PRE_BARRIER_ACK = false).* from "./durableExportAck"

  run emptySuccessTest =
    init
      .then(emptySuccess)
      .expect(and {
        emptySuccessReached,
        acknowledgementIsDurable,
        emptyBatchSkipsPersistence,
      })

  run insertFailureTest =
    init
      .then(insertFailure)
      .expect(and {
        insertFailureReached,
        not(state.returnedSuccess),
        acknowledgementIsDurable,
      })

  run barrierFailureTest =
    init
      .then(insertSuccess)
      .then(barrierFailure)
      .expect(and {
        barrierFailureReached,
        not(state.returnedSuccess),
        acknowledgementIsDurable,
      })

  run durableSuccessTest =
    init
      .then(insertSuccess)
      .then(barrierSuccess)
      .then(returnSuccess)
      .expect(and {
        durableSuccessReached,
        acknowledgementIsDurable,
        barrierFollowsInsert,
        resultIsUnambiguous,
      })
}

module durableExportAckMutantTest {
  import durableExportAck(ALLOW_PRE_BARRIER_ACK = true).* from "./durableExportAck"

  run preBarrierAcknowledgementMutantTest =
    init
      .then(insertSuccess)
      .then(returnSuccess)
      .expect(not(acknowledgementIsDurable))
}
```
