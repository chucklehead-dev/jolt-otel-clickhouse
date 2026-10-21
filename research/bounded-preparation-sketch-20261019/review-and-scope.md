# Revision B design intake and review

This packet supports type/scope approval, NOT implementation or model
qualification. No init/actions/invariants, production concurrency changes,
exhaustive checks or new performance runs are present.

## Existing-code findings that changed the design

1. SDK `drain!` takes A, blocks in `export-spans!`, updates A's counters, then
   takes B. An exporter-private helper cannot prepare the next SDK batch that
   has not been handed to it. An early truthy return would miscount admission
   as delivery. Intra-batch chunking is not the same workload/solution.
2. Durable already has a publisher worker and composes private
   `enqueue-open-ticket!` with `await-result-settled`. Reuse that atomic
   owned-execute-and-flush request with a distinct internal confirmed-ticket
   facade, rather than introducing a second SDK-owned thread and generalizing
   every SDK owner's single-worker lifecycle helper.
3. `do-execute-owned-and-flush!` still executes/classifies/authorizes the owned
   request then calls `do-flush!` inside ONE serialized worker operation.
   `execute-request!` dispatches that operation unchanged. No separate
   execute/flush requests or buffered-local acknowledgement are proposed.
4. The actual SDK benchmark uses a borrowed exporter connection. SDK/exporter
   retirement therefore does not prove the Durable writer is stopped. Revision
   B explicitly includes exporter-owned and consumer-owned connection modes,
   separate close outcomes and separate SDK/final settlement observations.
5. Queued admitted writer work may begin after SDK cancellation. The fence
   must forbid new submissions, not pretend it retracts prior admission.
   Await admitted outcomes through interruption; never abandon or replay them.

## Review adjudication

The first Claude review found missing explicit occupancy semantics and the
single-worker SDK helper mismatch. Its suggestion that a hidden exporter
pipeline alone could suffice was valid for confirmed-only synchronous return,
but did not supply B to that exporter while the sole SDK worker waits for A.
Codex checked the concrete drain loop and clarified the cross-batch requirement.

That review's no-codec memory concern is outside the selected owned-byte codec
lane, now explicitly rejected by the pipeline option rather than assigned a
misleading universal memory charge. Existing synchronous fallbacks remain.
The retained-byte budget must include owned SQL AND precomputed WAL; it is not
a bound on raw queued telemetry, temporaries, allocator overhead or RSS.

The completed Revision B delta review reported no blocking scope/type issue.
Its cosmetic slot-list ambiguity was corrected to explicitly include the
writer-admitted/execution-pending stage. It could not verify receipt semantics
from its restricted read ranges; Codex separately inspected writer lines406-421
and654-665 and the already-recovered confirmed SDK evidence. Review is advisory,
not a proof of the future ticket API or model.

The final binding clarification assigns exporter late validation/admission to
the submission seam; the existing Durable worker retains its own backend
context. It does not assume SDK dynamic bindings travel across the queue.

## Gate and remaining approval

The declarations-only Quint fence typechecked on the installed Quint CLI.
Prose clarifications after that check left the extracted type declarations
byte-identical (compared directly); no unchanged check was rerun. The recorded
type SHA256 identifies the tested declarations. There was no simulation and
this must not be described as a runnable checked protocol yet.

Explicit approval of **Revision B** in
`formal/quint/bounded-preparation-type-sketch.md` is still required before
model logic, per the Quint modeling skill. The asynchronous request points to
that file; the implementation/ownership findings above clarify the same
one-owner, two-role, two-slot bounded proposal before any approval is acted on.
