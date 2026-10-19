# Rich codec / row-adapter review disposition

Claude read-only source review completed terminal0 on 2026-10-19. Raw output is
preserved in `rich-claude-review.txt`. Codex independently verified source and
runtime receipts; the review did not execute tests or qualify performance.

No blocking defect was reported. Do not treat the review's blanket conclusions
as broader proof than the actual gates:

- Several line/file citations say `oracle.clj` where the controls really live
  in `probe.clj`. The collision-only map root is an **hnode with one hcoll
  child**, not a bare hcoll root. Codex checked those actual source locations.
- The fixture is deterministic SDK-shaped benchmark data, not captured
  real-application telemetry. Its typed fields use actual confirmed projector
  machinery with a test installation; this is not actual database schema DDL.
- The `[]` specialization for empty nested vectors is intentional: it avoids
  constructing a fresh scalar encoding context for the common empty-links
  case. It is not removed as redundant; this explicitly selected codec does
  not promise third-party JSONWriter dispatch.
- Zero-copy/no-allocation and complete standalone/full-stock-consumer support
  are not established. Outer encoding has private chunks and a flattened owned
  output, while projection still builds strings/maps/vectors.
- The default writer seam initially captured the default function too early.
  Codex corrected it to pass nil and preserve late lookup at each call boundary
  **before** this packet. A deliberate captured-default mutant now produces
  exactly two assertion failures and no errors in the focused test; the fixed
  suite passes 15 tests / 84 assertions. Both red and green receipts are kept.

Runtime gates: stock release probe 128 checks; separate BB/native-Cheshire
parser 180 cases with UTF-8 and ASCII-escaped variants; original data.json
reference equality/readback; output ownership/limits/reentry/project-order
controls; full rich output digests and changed-input controls in both prepared
and projection-inclusive comparisons. Source hashes remain unchanged across
each final run. No native insertion, SDK queue, WAL/recovery, S3, or native
standalone claim follows from these component results.
