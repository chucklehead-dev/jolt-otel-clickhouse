# Canonical preparation integration

Integration base: exporter `b414975492996c6bdd71843aa5ff3878cb400384`.
Preserved adapter source: `d0bf2f3`; approved model source:
`78e1f0117c247ca33d5e135149f4fbdb6ca4add9`. Neither source nor model logic
was changed by this integration. The original benchmark graph and receipts
remain under `research/preparation-overlap-20261021`; their rates are not
measurements of this final dependency graph.

Final published dependency pins:

- OTel `38278ab4cf3b7bad7ed817170ca7e380122068b3` (canonical main).
- Driver `312812f0dfeff5498a6d1aa29b0476a97d8b65f3` (canonical main, PR302).
- Codec `c75579b90a9089bcbdcb195ebd18d15d16ee0082` (unchanged).

Local gates on official stock Jolt 0.8.23, pinned Chez 10.4.1:

- Final owner/adapter namespaces: 10 tests, 116 assertions, no failures/errors.
- Final dependency resolution, including stale/wrong-coordinate controls:
  63 checks passed. Child CLI was explicitly the same stock executable.
- Current-root shape-plan gate: 4 tests/15 assertions; scalar views: 3/19.
- Unchanged literate model: both extracted files typecheck; all 29 deterministic
  scenarios/controls pass (17 core, 9 owned lifecycle, 3 borrowed lifecycle).
  Prior sampled receipts remain at the original model revision. No exhaustive
  run or new model guarantee is claimed.
- Final-graph native normal export and separate fresh readers pass for owned
  and borrowed connections: four rows each, independent ordinary JSONEachRow
  table parity, exact nanoseconds and correct publisher cleanup.
- Final-graph causal cancellation and separate fresh readers also pass for
  both owners: A enters WAL publication before B is prepared; cancellation
  prevents B's submission; only A recovers. SDK reports attempted=2,
  exported=1, failed=1, dropped=0. False shutdown is expected cancellation,
  not a delivery-success receipt.

Native gates selected chDB 26.9.0 explicitly. Runtime SHA256:
`7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94`;
native library SHA256:
`a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557`.
Small writer/reader receipts are in `native-results`. These are separate
processes using this same final graph, not an independent older reader stack.
No live AWS throughput, application integration, complete lifecycle coverage
or final-graph throughput qualification follows from these functional gates.

The stock CI workflow now explicitly runs the ten owner/adapter tests and
both causal native cancellation/readback controls after verifying its native
library. Its YAML, embedded shell syntax and library-before-use ordering were
checked locally. Hosted execution and final review remain separate gates.

Independent Claude read-only source review completed without a blocker.
Its slot-lifetime prose nit was corrected: release does not itself retire a
slot. Its line references are offset and are not authoritative; Codex checked
the named transitions against the actual source and ran the gates above.
The review did not execute tests or prove callback/native settlement contracts.
Hosted integration CI remains pending at this checkpoint.
