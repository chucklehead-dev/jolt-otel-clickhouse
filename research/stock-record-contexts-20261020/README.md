# Standard SDK record contexts in the independent codec

Tracks #149; blocks Oscope PR173 until the app selects the corrected component
and rich linked-span regression gates pass. Base exporter01c991b. Published
independent component98e93e2a6726c435d9b55ada9a088c2dbf5b1c99.

## Failure and modeling boundary

Actual OTLP decoding constructs otel.trace.SpanContext records inside links.
The prior codec0af1008 only accepted ordinary/sorted maps; LinksJSON encoding
failed with `ffc-json: unsupported value`, correctly producing HTTP503 before
publication. Attributes/events encoded; links failed; a plain-map context
substitution passed. The substitution is diagnosis, **not** the implementation.
Earlier linked-span fixtures used map-shaped contexts and missed the standard
SDK type. This is a value-representation/consumer-contract test gap, not proof
that control-plane models establish arbitrary telemetry interoperability.

## Fix

Centralize ordinary defrecord traversal beside persistent/sorted maps. Walk
declared keys through runtime field accessors, then the captured ordinary
extension map, preserving seq order without map/entry/sequence copies. Accessors
handle empty, inline and spilled records. Reject deftypes, custom seq methods
and unsupported extension representations. Record fields/dispatch stay immutable
during each call. Metadata/native type bookkeeping are not JSON fields.

The JSON backend delegates to this traversal. Records are not fragment-cache
roots; no raw JSON bypass, global helper installation, compiler fork, lifecycle,
WAL, ACK or concurrency change. The trusted internal native-kernels vector now
also supplies a record predicate; instrumentation factories were updated with
it, not silently mixed with the earlier four-kernel bridge.

The exporter probes the public SDK constructor's actual record once before
storage, so old components reject rather than failing on their first linked
span. This probe is not evidence of ingestion. App observers must reset at the
startup handoff before asserting runtime codec use.

## Executed gates

Official stock Jolt0.8.23, Chez10.4.1, native26.9.0 for the actual socket smoke.

- Dependency-free source: **32 tests /314 assertions**, terminal0.
- Actual Linux standalone library binary from a neutral directory: **32/314**,
  terminal0. This is not app/exporter standalone packaging.
- Exact published component exporter gate: **71/553**, terminal0. Actual OTLP
  links use SDK records (flags129, ordered trace state, false attributes), with
  and without nested-fragment scopes and with record extension fields. Every
  row field matches configured JSON, including exact LinksJSON TEXT/order.
- Controls: empty/inline/spilled records, extension/metadata/field order,
  nested/reentrant scopes, callback throw, custom seq/deftype rejection,
  unsupported values, depth/output limits, no stale output aliases.
- A record-incapable encoder rejects before JDBC. CI exercises actual older
  published components as rejected capability controls.
- The unchanged richer Oscope socket smoke now writes and independently
  recovers four rows, checking all22core ClickStack columns and multiplicity,
  with only the independent library changed. It used a local component path;
  app publication/pinning/CI and independent review are still separate gates.

Initial aggregate errors were fixture integration mistakes: debug factories
still accepted the old four-kernel bridge, and the new capability probe/test
initially treated a single NDJSON row as an outer array of rows. Corrected
source and gates above passed; these earlier errors are not product passes.

No new throughput/allocation, S3, all-value-shape, app packaging or population-
tail claim. The socket screen has not yet qualified a timing workload. The
~25k p50/~20k p99 objective remains unmet. No manual model/exhaustive run added.
