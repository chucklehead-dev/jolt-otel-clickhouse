# Actual integration review and remaining gates

Claude source-only integration review completed terminal0. It found two issues:

1. An alias-present but unsupported runtime was misleadingly reported as a
   missing alias. Fixed the message to name both requirements, without exposing
   arbitrary original exception data/cause. Added a synthetic-cause redaction
   control; the qualified focused suite passes 18 tests/98 assertions.
2. The runtime alias also included tests. Split runtime and test aliases.

The targeted follow-up review completed terminal0 and confirmed both fixes;
it found no actionable issue in the separate lightweight stock-Jolt CI job.
`actionlint` passes and the hosted run actually passes 4 tests/37 assertions.
Both raw reviews are retained. Codex owns these results; source review is not
execution, protocol conformance, or a performance proof.

The pre-review source is committed as 26067f5 and exactly preserved with the
four-arm comparison. Corrected source 7cc1a7b passes an additional 175k-row
independent recovery gate, after the 700k comparison rows. All five synthetic
stores were retired only after writer/reader/validator success and copied
receipts. No data objects or credentials were sent for review.

Still required before default/stable/full-product qualification:

- The full consumer on stock Jolt, not just the independent codec module.
  Current integration still selects the qualified native JSON/SDK/projection
  stack. The optional constructor still initializes that stack; native declared
  projection still uses its runtime ordered-map helper. Do not present this as
  eliminating every compiler/ecosystem fork from the app today.
- Performance targets: actual on windows 11.83k/12.68k confirmed rows/s, not 25k.
  Pooled apparent improvement is smaller than control drift. Allocation is
  consistently lower, but timing needs a stronger causal result.
- Reduce remaining projection/stringified attribute maps and positional row
  vectors plus native/WAL/hash/snapshot/copy costs. Do not attribute all remaining
  time to JSON encoding or assume this change alone can achieve the target.
- Broader unsupported values, actual Oscope pin/config adoption, S3 and fault
  cases, standalone/AOT, cross-platform qualification, and full consumer CI.
  Existing default backend remains unchanged while these gates are open.

No Quint model changed or unchanged exhaustive check was rerun. The new
pre-admission encoding path reuses existing driver ownership/publication rules;
all-success replay evidence does not prove every concurrency/fault invariant.

The stock-runtime source probe confirms two concrete remaining seams:
`pmap-fold-seq-order` and `na-owned-bv->bytearray` are absent on official 0.8.23,
while `make-jolt-array`, `na-bytearray->bv` and `seq-more` are present. The
native declared projection uses the first missing helper; driver owned snapshot
and WAL resources use the second. These are next candidates for library-owned
traversal/fresh-buffer construction, not reasons to rebuild the compiler now.
This is source/capability evidence, not a completed replacement or stock app run.
