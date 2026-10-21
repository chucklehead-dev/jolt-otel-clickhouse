# Source review, formatter correction

Claude Sonnet read-only process62736 reviewed initial candidate3d2e0bb and
returned a concrete byte-range gap. It did not run tests or native benchmarks.
Review occurred before native timing; app compilation alone ran concurrently.

Accepted source findings: the private cursor/capacity induction and zero-budget
rejection hold, shared scratch is1024 bytes only when the requested capacity
is1024, and cache fragments use checked copies. It correctly identified that
numeric formatter results did not have a locally enforced byte range. The
initial source screen warrants native testing, not adoption/target/tail claims.

Codex correction: keep ALL runtime float/bignum formatter output on a separate
`formatted-number!` loop with the original checked byte store. `ascii!` now
only receives fixed literals or the validated ASCII prefix. No unsafe formatter
mutation is executed. New lexical substitutions returning Unicode lambda955
must raise at the CHECKED byte store for both numeric paths. Valid substitutions
return123, proving actual encoding rather than a compile/setup failure. Final
independent library source gate43tests/645assertions, zero failures/errors.

The review described missing range enforcement as a concrete memory-safety
hole; source alone does not establish a particular corruption/crash mechanism.
The established issue is an unproven unchecked argument and possible changed
error behavior. The correction removes that assumption without claiming a
demonstrated native exploit. No global optimize-level3 was enabled.

No source/format/persistence model proof is inferred from these controls.
Codex owns the native access reasoning, complete tests and qualification.

## Follow-up on the corrected source

Claude read-only55220 returned terminal0 against453fe44: no blockers found;
both numeric formatter outputs now use checked stores, remaining fast-store
callers are bounded internal bytes, and lexical formatter controls exercise
the intended rejection without replacing process-global primitives. No tests
were run by the reviewer. This is independent source review, not machine proof.
The finalized native workload began only after both reviewer and build ended.
