# Native evidence review adjudication

Claude performed source-only review; it did not execute the harness or gates.
Codex owns the runtime checks. The original review is preserved unedited.

- Accepted:the pooled+6.71% rate advantage depends mainly on B2, whereas the
  forward pair is only+1.79%. The report now explicitly rejects a stable effect
  size/significance conclusion from two runs per arm. Allocation is the stronger
  result; no slow-tail/target claim is made.
- Rejected numerically:the review calls+1.79% "within the observed baseline-drift
  band," but endpoint baseline rate drift is+0.0052%, much smaller. Neither that
  endpoint drift nor two baselines defines a statistical noise band. The near
  equality of baseline total durations is not evidence of duplication:raw batch
  sequences and timestamps differ. Do not add unsupported suspicion or a
  statistical conclusion to the report.
- Accepted:the post-run source check was executed but its stdout was not retained
  as a file. Repeated it after all writers/readers were terminal, recorded both
  hashes and passing check, and compared the manifests byte-for-byte. This is
  selected-source integrity, not complete compile-time attestation.
- Own correction:the review overstates allocation validation. The writer computes
  allocation from counters, but the initial validator trusted that computed field.
  It now independently recomputes the estimate from before/after counters, with
  an additional rejected wrong-allocation mutation. Raw receipts are unchanged.

No additional benchmark run or product-source modification is justified by these
report/control fixes. The same-binary recovery and source tests remain separate
from interpretation of the noisy timing samples.
