# Nested JSON fragment review: useful question, recipe not accepted

Claude20727 completed exit0; raw source-only design critique is adjacent. It is
advisory, not a reviewed implementation, proof or qualification. Main rejects
several important assertions:

1. JSONWriter dispatches on `object`, not `out`: json.clj787-788 explicitly
   declares `(-write [object out options])`. An arbitrary custom method may call
   `.toString`, `.getBuffer`, inspect the concrete StringWriter, retain it, or
   reenter. Replacing it with a generic UTF8 Writer is NOT automatically
   observationally equivalent. The new ASCII test itself reads the real prefix
   via `.toString out`; existing byte-collector publication/borrow machinery
   exists precisely to preserve those observations. Retain a REAL StringWriter.
2. The current site requires an actual receiver value. Applying it to a private
   fragment instead of a String changes dispatch; applying it to a placeholder
   could execute value-dependent predicates on the wrong input. Captured stock
   function identity does not solve the missing receiver. Current private
   callback-free family caches are precedent, NOT an exposed value-free probe.
   A runtime-owned hot-family probe with epoch/domain/owner validation and a
   callback-free decline may be needed. Unknown/missing/cold cases must retain
   normal actual-value materialization; do not replay observable classification.
3. Outer vector/map/custom root writers and receiver predicates can inspect
   row contents BEFORE String-field dispatch. An internal marker in an ordinary
   row could leak to those callbacks. Any capability needs a separate owner-only
   row boundary or safe materialization BEFORE arbitrary ancestor observation;
   checking only the String method is insufficient. Original eager inner JSON
   callback/error order must remain before event-column/projector work.
4. Raw custom output can contain Unicode or isolated surrogates. Converting it
   through UTF8 bytes and back before outer JSON escaping may not preserve the
   original String's scalar/surrogate semantics. A validator alone cannot restore
   lost information. Exact fallback must retain the original real writer result
   without rerunning callbacks; test those cases explicitly.
5. The review's JVM UTF16 memory estimate is not applicable to this Chez target.
   Main's pinned Chez10.4.1 probe returns compute-size(make-string n): n0→0,
   n1→16,n1000→4016bytes. Empty/static objects and alignment affect this report;
   these are three object-size observations, not a complete
   collector allocation ledger or forecast of an attainable speedup. Double
   escaping is STILL necessary when storing JSON text as a JSON string; a byte
   route can avoid intermediate copying/materialization, not that required work.
   The proposed step "decode again before merge" does not establish avoiding
   that allocation merely by introducing a wrapper type.

No product API/type/compiler helper is implemented or selected from this review.
Next bounded prototype, if prioritized, must keep actual writer visibility,
separate logical-row ownership from generic JSON data, and explicitly identify
the missing runtime probe. First demonstrate exact transcript/output controls
for custom ancestors, changing methods/roots, receiver predicates, reentry,
exceptions and raw surrogate output. Do not build a fake optimized path by
turning those contracts off. Independent review does not replace main ownership.

The measured two-worker overlap is separate and remains valid with the original
selected encoder. Larger1/2/4lane resource-accounting work can proceed without
waiting for a speculative fragment API.
