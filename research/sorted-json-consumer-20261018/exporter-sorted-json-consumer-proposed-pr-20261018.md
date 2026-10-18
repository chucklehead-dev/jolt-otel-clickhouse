# Qualify native JSON encoding for SDK sorted event attributes

Stacked on `perf/sdk-scalar-map-consumer-20261018` (5a7617a); diff is only this
additional integration slice, not a resubmission of its parent.

## Motivation

Real SDK-ended resource/event attributes are sorted maps. The earlier JSON
backend handled them correctly but fell back to generic map serialization,
which was expensive for spans with nonempty events. Select the reviewed
sorted-map byte-sequence implementation in the opt-in native alias.

Ordinary JSON stays at0f51b991; SDK/driver/schema pins, default backend and
Durable acknowledgement semantics are unchanged. The updated dependency oracle
requires a18ae06 and retains both prior/older native-source rejection cases.

## Validation

- Actual SDK tracer/simple processor/ended-span test covers named and compact
  full-byte equivalence, sorted resource/event inputs, Unicode, Boolean/numeric
  values and exact newline-inclusive byte budgets with prefix excluded:2/12.
- Wider native attribute/projection suite47tests/940assertions.
- Resolved dependency gate56checks including current source and negative roots.
- Existing qualified-native CI runs the new consumer namespace explicitly.
- Source review found no functional regression; retained the earlier old-SHA
  negative control as an additive followup.
- Local SDK-queue/Durable ABBA with deterministic event-rich records: encoding
  p50713->495ms/5k, full insert938->728ms/5k. Separate older-stack reader
  checked all175000physical rows, exact event-time ticks and typed statuses.
  This is not sustained/p99/Rust/S3 qualification.

The added consumer tests are equivalence tests, not expected semantic failures
on the compatible prior backend. The library's separate branch mutant proves
native selection; this repository's resolution oracle proves the chosen pin.
