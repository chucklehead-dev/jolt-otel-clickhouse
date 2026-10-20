# Mixed-signal explicit span codec: core integration slice

Base: canonical exporter `6a98b94213648fcf52036d72019281fc37d0165c`.
Tracks #146. This removes the library-side obstacle to using the stock span
codec in a mixed-signal owner. **Oscope's closed configuration/profile does
not expose these flags yet**; that remains follow-up, not a completed app mode.

## Behavior

With explicit codec opt-in, allow only declared signal sets containing spans
and optional logs/metrics. Unknown/absent span signals still reject before
storage. Spans retain the owned-byte explicit codec and existing caches.
Logs/metrics explicitly select configured JSON and String SQL, without
installing/loading the separate legacy native JSON writer. Their scope clears
the span-only converter/nested-writer bindings and restores them on return.
For owners without the explicit codec, inherited bindings/backend selection
stay unchanged. No default, schema, timestamp-setting, ACK, worker or queue
change. All three signals still use the existing atomic confirmed writer API.

This is representation selection, not new preparation/publication overlap.
The opt-in span scope/caches remain per synchronous payload; they do not become
a shared cross-signal pool. Existing shutdown/admission and late-plan code is
unchanged. No new model logic or manual exhaustive run was added.

## Validation

Official stock Jolt0.8.23 / Chez10.4.1; published codec0af1008, published
SDK/driver/JSON pins, no checkout-local codec alias.

- Combined exact published-component gate: **62 tests /497 assertions**.
  Includes real allowed/rejected signal preflight, no old writer load,
  owned-span vs String nonspan routing, raw-tick SQL settings, Unicode log
  roundtrip, byte-limit/no-write controls, exact error preservation,
  admission/local-execution rejection, and balanced in-flight counts.
- Reentrant and concurrent log calls do not borrow an active span scope;
  parent bindings restore. The concurrent test parks span preparation, confirms
  a log, then releases the span; no early span success. This is a caller-binding
  unit control, **not** concurrent native publication or a new pipeline proof.
- Two fresh local stores, `:raw-ticks` and `:auto` (ISO), each completed real
  direct-exporter calls for8 typed spans,1 Unicode log and gauge/sum/histogram
  points. Actual routing:1 owned span request +4 String requests. Legacy native
  writer acquisition is a throwing sentinel. A separate fresh reader process
  checks every selected physical value and multiplicity: **12 rows per mode**.
  Writer and reader both terminal0; copied/cmp receipts and source hashes
  checked before retiring each exact owned store. No native data/binary kept.
- Result labels are normalized from JDBC lowercase names; DateTime fields
  compare in epoch units, independent of local display timezone. Integral
  Float64 JSON spelling (2 vs2.0) compares in the exact small-integer domain.
  Controls reject precision loss above2^53, Boolean/string confusion and
  negative-zero erasure. Nested JSON TEXT remains an exact String comparison,
  not a decoded/reformatted substitute.
- `actionlint` passes the stock CI workflow. CI now exercises the new pure
  tests and both actual native writer/fresh-reader modes on stock Jolt0.8.23 /
  chDB26.9.0. Hosted results remain a separate publication gate.

These are small **direct exporter** functional cases, not SDK queue/HTTP,
real Oscope or Langfuse, standalone app, S3, Rust or throughput/tail
qualification. No new benchmark was run; ~25k p50/~20k p99 remains unmet.
The last paired span result remains the earlier12.2k->14.8k local observation.

## Diagnostic custody

Early probes exposed test errors, not silently accepted passes: a malformed
event fixture, incorrect case-sensitive result labels, a forbidden snapshot
database override, and comparing DateTime/Float64 display spellings literally.
After correcting the oracle, both previously written12-row stores independently
recovered completely; the setup-only failed fixture proved zero application
rows. Their small failure/recovery receipts are retained in workspace evidence;
all three exact stores were retired after diagnosis, terminal status and
copied/cmp receipts. They are not counted as fresh successful writer gates.
The final fresh raw/auto gates ran after all oracle/guard changes.

The runner has fixed workspace paths, binary/native checksums and an optional
NEW output directory. It is a research reproduction aid, not a standalone
benchmark distribution. Failures retain their owned store; successful stores
retire only after the independent reader and source/copy checks.
