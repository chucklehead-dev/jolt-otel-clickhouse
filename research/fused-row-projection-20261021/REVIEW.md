# Bounded independent source review and adjudication

Claude Sonnet read-only review of commit 61484633f4d796b8a3361b169d7ea6c686a53b5f,
one process 18646, exit 0. Only named prototype/source and synthetic scalar
results were in scope. No commands, edits, publication, credentials, telemetry
or persisted stores. It did not run tests. Codex ran the functional and timing
gates independently; no timed/native workload overlapped the review.

Accepted source observations:

- Caller-supplied event/link identity snapshots are trusted research tokens,
  not structurally enforced original defining-namespace anchors. They cannot
  become a production authorization boundary as-is.
- The small event-column fast path duplicates owner logic, and therefore
  needs maintained semantic controls or a shared defining-owner implementation.
- Dedicated functional controls cover baseline/candidate, not the row-view
  third arm. The latter has whole-byte screen coverage and prior separate tests.
- The prototype only fuses preparation. It still creates an opaque row holding
  all fields, then the encoder walks the row; it does not emit fields directly
  into the final byte sink.
- Timing/GC variation warrants caution. The samples establish neither a
  throughput nor a tail improvement. Variation alone does not establish that
  GC caused the observed tails or quantify their statistical uncertainty.

Important corrections to advisory conclusions:

1. The review proposed merging nested event JSON and flattened-column passes,
   claiming both independently repeat the attribute converter. The source does
   NOT support that claim: the nested writer encodes ORIGINAL event/link data,
   while flattened columns call the SDK/exporter attribute converter. Those
   have deliberately different value semantics (e.g. numeric/Boolean originals
   vs String-valued attribute columns). There are repeated source walks, but
   not two executions of the same conversion. A shared traversal must produce
   both distinct representations, never reuse normalized attributes as raw JSON.
2. It described private event/link helpers as a public override contract and
   suggested any shared traversal requires a new model/driver contract. These
   helpers are private. Existing late-binding/error behavior and actual public
   JSONWriter/SDK policies must be preserved, but that does not automatically
   create a public helper API or require a persistence-model change for a pure
   encoding optimization. Ordered asynchronous publication is a separate scope.

No production adoption recommended. The independent review agrees with the
preparation-only classification; it does not supply a new qualified fast path.

## Additional stock capability check

An isolated stock Jolt probe reports Chez `optimize-level` 0 and
`compile-profile` false. This is NOT evidence the library runs unoptimized or
is interpreted. Exact Jolt v0.8.23 `host/chez/compile-eval.ss:853+` calls `eval`;
Chez10.4.1 `csug/system.stex:815-892` describes its default compiled evaluator,
and `:2614-2641` says compiler output is the SAME at safe levels 0--2. Level3
removes safety checks and can corrupt memory on invalid arguments. No global
level change or blanket unsafe compilation was made, and no level0->2 speedup
is claimed. Keep future unchecked specialization local and prove its bounds.
