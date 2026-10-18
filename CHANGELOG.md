# Changelog

## Unreleased

- Add an optional per-exporter `:max-insert-bytes` payload limit, from 1 byte
  through 64 MiB. Keep the 8 MiB default, newline-inclusive UTF-8 accounting,
  independent concurrent serialization, and confirmed Durable acknowledgements.
  Reject invalid limits before acquiring a connection or changing schema.

- Compile per-location declared-key selections for portable typed projectors.
  Keep normalization order and duplicate detection while avoiding temporary
  maps and value vectors for unrelated attributes. Captured telemetry and
  typed value/status semantics are unchanged.

- Advance the optional native byte-JSON backend to emit short clean ASCII
  without fragment-cache lookup, retaining cached Unicode and longer strings.
  Check the actual resolved source and reject the previous pin. Ordinary JSON,
  SDK, driver, schema and persistence defaults remain unchanged; short paired
  improvements do not qualify sustained throughput or tail latency.

- Check builtin string-map shape and SDK string bounds in one allocation-free
  walk. Keep generic normalization for changed helpers, policies and unsupported
  layouts; do not change captured values or byte budgets.

- Repin the optional native byte-JSON backend to include local ASCII cursor
  emission and safe reuse of never-exposed row writers. Preserve custom writer
  behavior and ordinary JSON, SDK, driver, schema and persistence defaults.
  Verify the actual resolved dependency; short allocation improvements do not
  qualify sustained throughput or tail latency.

- Update only the optional native byte-JSON dependency for lower per-row
  bridge allocation. Keep ordinary JSON, SDK, driver, encoding and persistence
  defaults unchanged. Check the alias's actual resolved source separately;
  this dependency checkpoint is not a throughput-target qualification.

- Render standard OTel span-kind and status keywords with string literals,
  avoiding temporary case-conversion strings per row. Preserve the general
  rendering path for extensions, named/compact wire values and live enum-helper
  replacements. No encoding, schema or durability defaults change.

- Use the optional native JSON string-output adapter for span events/links in
  explicit byte-batch mode. Stock empty vectors can avoid temporary writers;
  live JSON replacements and custom methods remain visible. Ordinary modes
  and older byte dependencies keep their public writer path.
  Pin only the optional byte dependency and require its real adapter in native CI.

- Avoid intermediate typed value/status pairs in explicit native positional
  projection. Share classification rules with the ordinary pair path and retain
  live projectors, helper/codebook behavior and private output ownership.

- Avoid redundant key-converter calls for primitive string keys in the explicit
  native attribute-map path. Check the live converter before each entry so
  mid-row replacements, value callbacks and normalized-key fallback remain live.
  Bind stable converter Var identities once rather than forwarding them across
  the native boundary for each map; converter values are never cached.

- Avoid empty event/link vector folds when constructing span rows. Preserve
  named/compact layouts, JSON encoding calls and generic/lazy input traversal;
  nonempty events and links retain the existing conversion path.

- Update the chDB driver pin to the reviewed current Durable implementation,
  including optional owned compact streaming. Keep default native installation,
  encoding choices and persistence semantics unchanged; requalify source identity.

- Reduce temporary attribute-map construction for the explicit native-byte
  backend. Preserve current string-map reuse, live key/value conversion order,
  normalized-key precedence and captured dictionaries. Unsupported layouts
  retain the original portable conversion; unavailable kernels fail at startup.

- For explicitly selected native-byte compact typed spans, collect only
  declared values without temporary map entries and build positional output
  once. Keep every normalized duplicate and live converter call in source
  order, with invocation-owned storage and fallback for unsupported maps.
  The default portable projector, captured dictionaries and WAL are unchanged.

- Avoid sorted-map comparisons for the built-in typed status codebook during
  row projection. Keep its public sorted presentation and live replacement
  behavior, including codebooks installed before the projector loads.

- Add explicit experimental Durable `:datetime64-wire :raw-ticks` for chDB
  26.9.0. Keep numeric nanoseconds and include the fixed raw-tick setting in
  every generated INSERT/WAL statement, without changing session settings.
  Default ISO selection and ordinary transports remain unchanged.

- Qualify an exporter-local UTC ISO timestamp wire for native chDB 26.9.0;
  retain exact integer nanoseconds for 26.7.3 and reject unknown packages before
  schema writes. Spans, nested event arrays and log observed-time fallback use
  the same bounded nanosecond domain. Metric second-based timestamps are unchanged.

- Reuse ordinary immutable attribute maps whose string keys and bounded string
  values need no conversion. Preserve live normalization policies, converter
  overrides, metadata stripping and generic fallback; JSON encoding and Durable
  persistence remain unchanged. Reject mixed map shapes before SDK value
  admission to avoid repeating normalization work on fallback.
  Qualify the scalar helper at its SDK definition boundary, including helpers
  replaced before exporter startup; never trial-invoke unknown helpers.
  Decline hash collision buckets and map-mode changes whose rebuild changes
  kind or entry order. An optional Chez shape walk avoids temporary entries;
  unsupported hosts retain generic normalization and SDK bounds remain live.

- Add experimental owned statement output for explicit native-byte compact
  Durable export. Qualify its snapshot ABI before opening storage; preserve
  schema fences, byte limits and confirmed publication. Unsupported output
  decodes already encoded bytes without repeating row producers. Defaults and
  generic fallback routes retain text; WAL and timestamp formats are unchanged.

- Build approved typed compact Durable span rows directly as vectors with the
  explicit native byte backend. Compile the field/location plan once; retain
  target and column fencing, normalized-key collision/status semantics,
  conversion order and the public map projector's behavior.

- Materialize bounded Durable insert SQL once with the explicitly selected
  native byte-batch collector, including generic typed and closed compact
  rows. Keep row-local custom writer views, payload-only byte limits, schema
  fences and confirmed writer boundaries. Update the driver to its reviewed
  prefixed encoder API; configured JSON and ordinary insertion are unchanged.

- Qualify actual SDK gauge, sum and histogram capture with an explicit
  per-reader shutdown budget, using the updated SDK's public option instead
  of changing global policy. Preserve primary capture failures and bounded
  cancellation diagnostics; verify every physical row in a fresh reader.

- Build untyped compact Durable span, log and metric rows directly as ordered
  vectors, without intermediate physical maps or temporary metric routing
  fields. Keep schema/layout guards, conversion order, typed fallback and
  confirmed writer boundaries; recheck plans after serialization callbacks.

- Add explicit compact JSON row input bound to the confirmed live schema,
  connection and ordered columns. Reject mismatched types, missing/extra
  fields and changed plans before insertion; retain fixed errors and the
  single-schema-owner contract. Named input remains the default, with the
  same persistence acknowledgements and native timestamp behavior.

- Expose opt-in native guarded, bounded string-cache and serial byte-batch
  JSON backends for the exporter's general JSONEachRow fallback. Validate
  availability before opening storage; give each payload its own encoder and
  preserve row effects, 8 MiB limits and cleanup. Configured defaults,
  specialized codecs, timestamp wire and persistence acknowledgements are
  unchanged. The optional collector dependency remains explicit.

- Qualify the current chDB scoped S3 writer through the normal exporter and
  one SDK owner for spans, logs and metrics. Document borrowed-connection
  shutdown and add stock Jolt 0.8.22 CI with actual handle reuse/cleanup and
  independent full-row recovery. SDK, JSON, crypto and native-package choices
  remain unchanged; no throughput or real-AWS qualification is claimed.

- Use the SDK's guarded scalar-text helper for attribute strings, Booleans,
  and Int64 values without allocating a normalization-result map. Keep ordinary
  canonicalization for other values and changed canonicalizer/default limits;
  preserve exact attribute text and JSON wire values. Qualify the new SDK pin
  while retaining chDB, JSON, crypto, and native-package choices.

- Update chDB to the qualified native SQL placeholder detector. Confirm the
  exact resolved revision and reject the prior pin; preserve SDK, JSON, crypto
  and native-package choices. Query results, parameter handling and Durable
  acknowledgements are unchanged; no exporter throughput gain is claimed.

- Qualify the current chDB Durable writer with shared selection reads and
  preserved seal-error provenance. Check its actual resolved source revision,
  including a prior-revision negative control; retain the existing OTel, JSON,
  crypto and native-package choices. This is not a new throughput claim.

- Normalize keyword attribute names without allocating a colon-prefixed
  intermediate string. Preserve namespaces, collision precedence, converter
  order and all non-keyword behavior. No encoding or persistence contract change.

- Update chDB to retain the exact head-scanner character vocabulary and reuse
  identical decoded head bytes within a reference commit. Fresh reads,
  persistence acknowledgements and telemetry values are unchanged; no exporter
  throughput gain is claimed.

- Update chDB to the maintained JDBC callback-domain provider and current
  Durable fixes, including plain-key head decoding. Domain-capable runtimes
  avoid scalar JDBC class-helper work; this does not change exporter
  acknowledgement semantics or claim a measured exporter throughput gain.

- Add mock-only negative controls for the exporter transport spy's actual
  table and ordered-column arguments, and for the maintained Hegel runner's
  failed, flaky, and thrown-error verdicts. These controls do not qualify
  native lifetime, fresh-reader parity, or benchmark behavior.

- Align the Durable acknowledgement model's integration notes and Hegel ITF
  replay with the adopted per-call `execute-and-flush!` boundary. The replay
  checks two sequential confirmed exports; exporter admission and shutdown
  drain remain runtime-test obligations, not claims of the writer-queue model.

- Add an explicit, bounded two-process native gate for exporter shutdown:
  two concurrently admitted Durable span calls race final owned shutdown, a
  post-fence call is rejected, and a fresh reader compares every selected
  physical column against independently populated expected rows. This gate is
  opt-in and does not change production export behavior.

- Fence exporter shutdown and call admission in one lifecycle state transition.
  Each signal's shutdown waits for calls admitted to that signal; this
  per-signal drain does not itself close the connection. Only shutdown of the
  final declared signal closes an exporter-owned connection, after all
  admitted span, metric, log, and span-flush calls have released it.
  Concurrent/repeated shutdowns observe the terminal close result.
  Interrupted shutdown waiters retain their interrupt flag without abandoning
  the pending close; shared connections remain application-owned.

- Add a Jolt-only, schema-fenced untyped log batch encoder to the production
  exporter. Admitted records avoid physical row maps while retaining exact
  `data.json` JSONEachRow bytes, the 8 MiB bound, and the same ordinary or
  caller-atomic Durable write boundary. Typed logs, other hosts, and batches
  with any non-admitted record retain the existing generic path. A local
  public-exporter/Durable A/B (3 warmups, 100 measured 512-record batches per
  arm) measured generic p50/p99 158.8/234.1 ms and 3,348 aggregate rows/s,
  versus specialized 89.2/126.5 ms and 5,725 rows/s. Both arms produced
  identical 251,426-byte SQL and 52,736 full physical rows with independent
  fresh-reader digest parity. This distinct log fixture and provisional chDB
  #204 dependency do not meet the 25k p50/20k p99 rows/s target; they do
  not qualify S3 or other telemetry shapes.

- Extend the Durable JSONEachRow span encoder to installer-confirmed typed
  columns. It writes the established column order and public `data.json` values
  without building the outer physical row map; unsupported shapes and unsafe
  column plans retain the row-map fallback. The Durable SQL/WAL path is unchanged.

- Adopt chDB's caller-atomic Durable `execute-and-flush!` writer request for
  every non-empty exporter physical insert. Typed, fallback, and untyped span
  paths no longer split a Durable JDBC execute from its publication barrier;
  ordinary and caller-supplied persistence barriers retain their existing
  behavior. Opt-in phase receipts now report the one honest combined writer
  boundary instead of fabricated subphase timings.

- Reuse bounded, batch-local `data.json` wire strings for repeated untyped
  Durable span/resource attribute maps and known-empty link fields.  The
  optimized path retains exact JSONEachRow bytes, the 8 MiB per-batch bound,
  and the ordinary fallback for unsupported span shapes.

- Extend the literate Durable acknowledgement model to independently admitted
  callers and ordered writer requests. The checked red separated-request mutant
  demonstrates B being committed then settled false; the corrected bounded
  model covers caller-owned atomic settlement, retained-WAL
  failure/ambiguity, positioned force flushes, and FIFO close processing.

- Specialize the default `data.json` writer call path used by untyped Durable
  span encoding, and add an opt-in, provenance-bound phase-profile launcher.
  The launcher records aggregate payload, native execution, and confirmation
  barrier timing without retaining telemetry values; it is diagnostic and
  disabled outside explicit benchmark invocation.

- Add opt-in aggregate-only phase receipts for the untyped Durable span path.
  They record payload construction, native execution, and confirmed persistence
  barrier elapsed time and span counts without retaining telemetry values or
  changing acknowledgement behavior. The facility is disabled by default.

- Reduce untyped Durable span serialization allocation with a schema-bound
  encoder using maintained `data.json` for every dynamic value. Typed and
  ordinary exports retain their existing paths; unsupported span shapes fall
  back to the existing row encoder. Payload bounds and publication guarantees
  are unchanged.

- Update OTel to the merged SDK lifecycle fixes and default scalar attribute
  normalization shortcut. Attribute values and ClickStack payloads retain their
  existing representation; this update does not change exporter durability semantics.

- Assemble each ordinary and Durable JSONEachRow export batch in one
  batch-local `StringBuilder`, retaining pinned `data.json` per-row bytes, row
  order, and trailing-newline wire contract while avoiding the intermediate
  chunk sequence and final `apply str` pass.

- Extend installer-capability-bound typed promotion and bounded explorer
  readback to explicit histogram resource, scope, and point attributes. The
  explorer exposes only schema-bound Boolean/String/Int64 filters, stored
  count readback, and six-way coverage; it does not add bucket reconstruction,
  aggregation, grouping, DDL redesign, or cross-process capabilities (#73).

- Extend the installer-capability-bound metric explorer to promoted sum
  resource, scope, and point attributes. It provides the same bounded
  discovery, Boolean/String/Int64 filters, stored-point readback, and six-way
  coverage as gauges, while aggregation, grouping,
  saved-query persistence, and schema changes remain out of scope (#8).

- Extend installer-capability-bound typed promotion to sum resource, scope,
  and point attributes on `otel_metrics_sum`. Generic ClickStack maps remain
  unchanged; direct native and real loopback OTLP readback cover all three
  locations. Histogram promotion and sum query discovery remain unsupported
  (#8).

- Add installer-capability-bound typed gauge schema discovery, Boolean/String/
  Int64 filtering, and six-way availability coverage. Queries use only fixed
  gauge-table identifiers and JDBC-bound caller scalars; generic maps remain
  the compatibility fallback. Sum/histogram querying, metric aggregation, and
  saved-query persistence remain unsupported (#8).

- Extend installer-capability-bound metric promotion to gauge resource and
  scope attributes and sum point attributes. The generic ClickStack maps stay
  unchanged; sum resource/scope and every histogram target still reject before
  DDL or export. Direct native DDL/readback covers the owned columns,
  idempotence, and a real table-qualified wrong-type failure. A real OTLP JSON
  loopback compares direct and receiver rows for both enabled metric tables.
  The existing fresh-reader WAL lane now also reads back those values, statuses,
  and generic maps while the Durable writer remains alive. That fixture is
  wired to the hosted Durable lane, but a passing hosted run remains required
  before this change claims Durable release qualification (#8).

- Make the opt-in Durable `reviewed-source` driver preflight use the same
  NUL-delimited, ignored-aware cleanliness receipt as resolved providers. A
  shell control proves a clean reviewed checkout is accepted and an ignored
  local `jdbc/*.clj` file is rejected before native work; root-pin and ordinary
  release qualification paths are unchanged (#67).

- Permit only Jolt's exact resolver cache sentinel while checking the resolved
  chDB and OTel source receipts for the typed Durable native qualifier. Tracked
  changes and every other untracked path still fail closed; the loaded source
  revisions and native qualification behavior are unchanged.

- Harden the typed Durable native dependency receipt to reject ignored paths,
  including ignored source that could shadow a resolved namespace. Focused
  controls cover clean, exact-sentinel, untracked, ignored-source, and tracked
  cases; loaded source revisions and native qualification behavior are unchanged.

- Correct the clean dependency-receipt expectation for the published OTel
  revision selected by the root dependency graph. The test continues to reject
  a different repository or full revision; exporter behavior is unchanged.

- Add installer-capability-bound typed value/status promotion for gauge point
  attributes on `otel_metrics_gauge` only. The exporter retains generic
  `Attributes`; this initial point-only boundary is extended by the newer #8
  entry above. Native in-memory chDB evidence proves exact Int64 and Boolean
  `false` readback (#8).

- Repin the reviewed Durable capability consumer to immutable casselc/jolt
  artifact `durable-runtime-bf8a5dde-linux-x64` from successful run
  `35405706668`, retaining exact source/tree, archive, binary, and cache
  identity checks. This selects a proven ranged-append capability only; it
  makes no throughput, tail-latency, or compiler-change attribution.

- Repin `casselc/data.json` to `e7f97a9b5ecf7fa00787375fff4176a082fe9b98`
  and update the resolved-provider receipt. This is a separate dependency
  change: measurements must retain the existing A-prime/B-prime/A/B/B/A
  causal split rather than attribute integrated results to either JSON change.

- Require a terminal `:main` stage marker as well as the exact closed v1
  migration-record diagnostic before the ordinary benchmark launcher starts
  its read-only registry observer. Synthetic controls cover nonzero require,
  fixture, and non-record main failures; they stop A/B/B/A and preserve the
  writer's primary exit without an observer, retry, or migration claim (#55).

- Require the complete ordered cold-launch receipt, including `:require :enter`,
  before the ordinary benchmark can classify a terminal v1 migration-record
  failure and start its read-only observer. A synthetic forged-return control
  proves a partial receipt cannot authorize observation (#55).

- On an ordinary benchmark writer's terminal v1 registry record-phase failure,
  launch one fresh read-only child which reports only the closed registry
  cardinality observation. Preserve the original writer failure if that
  observation or its receipt fails, and stop the A/B/B/A sequence without an
  in-process retry or migration claim (#55).

- Distinguish statement-phase migration failures from ambiguous registry-record
  result-consumption failures. Add a two-process native witness that injects
  after the v99 registry INSERT and independently reads the exact record; keep
  record failure non-retriable in process and make no unrecorded-result claim
  (#55).

- Run the qualified artifact's unchanged ranged-append capability check from
  its private artifact directory, independent of unrelated caller dependencies.
  Retain the 30-second deadline and all provenance checks; application/native
  integration remains a separate qualification gate.

- Add closed, payload-free setup/migration diagnostics to the ordinary transport
  benchmark, preserving its assertions and failure exits; setup intermittency
  remains unresolved and this does not claim an encoder or compiler fix.

- Add closed baseline/StringWriter profiles for one authenticated Durable
  runtime artifact pair, preserving the existing AEA default. Reject mismatched
  independent pins before provider access; keep archive, manifest and ranged
  append guards unchanged. Offline selection controls do not qualify actual
  runtime performance or repin any consumer.

- Require explicit witnesses that each migration fault injection ran. Add
  shell-only fake child controls for absolute executable selection under PATH
  shadowing and unchanged parent failure accounting. These are test-boundary
  checks, not native lifetime or performance qualification (#43).

- Reject unknown benchmark launcher arguments before provider inspection or
  child startup. Keep metadata-only mode explicitly environment-configured;
  add a fake-compiler control proving a mistaken CLI flag cannot start work.

- Default the opt-in ordinary transport benchmark to the declared root graph;
  keep local driver overlays explicitly exploratory. Record and check each
  writer/reader's selected providers before native work and compare graph
  receipts across A/B/B/A arms. Add a provenance-only probe without native
  writes. Retain individual sample observations and explicit child exits plus
  available-artifact/source receipts on failure without qualifying unfinished
  comparisons. Observation perturbs interbatch behavior even outside timing.
  Refs #44. This does not qualify p99, allocation or Durable targets.

- Separate official-runtime Durable rejection checks from positive replay on
  an explicitly authenticated capability-runtime artifact. Validate producer,
  archive and binary provenance before execution, and maintain offline controls
  for malformed or stale artifact evidence. Shared-artifact qualification is
  pending; an ordinary minimum compiler version is not a Durable capability
  claim (#48).

- Let the Durable native qualifier accept the pinned resolver's exact
  untracked cache marker while still rejecting changed provider source or
  other untracked files. Provider checks fail closed on Git errors.

- Keep the SDK dependency test aligned with the reviewed merged SDK pin and
  honor an explicitly selected absolute test executable. Treat SDK source and
  resources as one canonical checkout, rejecting duplicate or distinct
  providers without claiming whole-graph uniqueness (#45).

- Select the merged ordinary-row chDB driver and the published OTel numeric
  compatibility checkpoint for root-dependency qualification (Refs #27).
  The SDK checkpoint is a review-branch candidate, not merged SDK main;
  final review and root/native qualification remain release gates. Existing
  explicit crypto ownership and dependency exclusions are unchanged.
  Raise the minimum Jolt version to 0.8.6. Durable qualification now defaults
  to revision-checked root driver/SDK pins; reviewed driver overlays require an
  explicit source-evidence mode and exact clean revision.

- Add explicit bounded CI lanes for ordinary native typed rows/metric admission
  and typed Durable WAL readback through a fresh process (Refs #27). Hosted CI
  selects released Jolt 0.8.6 and the exact root driver pin; an API-missing pin
  fails qualification rather than skipping or using an unmerged override.
  Retain only explicit synthetic fixture logs and step status on failure;
  native stores, WAL, databases and environment files are excluded.

- Add an opt-in ordinary transport ABBA benchmark with explicit provenance,
  bounded child processes and complete fresh-reader reconciliation. Refs #27.

- Add an opt-in, local-only Linux native gate for typed Durable span/log
  exports and fresh-reader WAL recovery, preserving canonical tests and
  requiring explicit runtime/library provenance. Refs #27.

- Fail closed before exporter schema writes unless the actual loaded chDB
  package reports the qualified 26.7.3 timestamp wire. Compatible library
  overrides and Durable connections receive the same check; no bypass option
  is provided, and rejected versions are not included in error data.

- Encode DateTime64(9) span/log/event timestamps as exact integer nanoseconds
  for pinned chDB package 26.7.3 / SQL engine 26.7.2.1, rejecting values outside
  0..Int64-max before insertion. This fixes shared ordinary and Durable
  early-epoch JSON wires; observed log time still feeds the existing Timestamp
  fallback, without adding a column. Whole-second metric DateTime fields and
  Durable classification/WAL/barrier algorithms are unchanged. Requalify the
  timestamp wire before upgrading to 26.8, whose integer semantics differ.

- Send ordinary telemetry batches through the driver's row-data API with
  ordered schema-owned columns, all-row key and numeric validation, maintained
  JSON encoding, and an exact 8 MiB UTF-8 bound. Active installer capabilities
  supply additive typed columns. Durable exports retain materialized SQL and
  their existing checkpoint/WAL acknowledgement protocol and require explicit
  `:durable? true`. Startup rejects non-chDB ordinary contexts before schema
  writes; ordinary metric batches validate every type before the first insert,
  without promising rollback of native execution failures. Physical UInt64
  duration/count/bucket domains remain supported. Refs #27.

- Add capability-bound typed log-record filters and six-way availability
  coverage for every scalar type supported by the current promotion manifest.
  Exact schema bindings and the installed connection authorize library-owned,
  bounded SQL; historical generic values remain visible only in coverage and
  never satisfy typed predicates. Refs #41, #8.

- Promote explicitly approved log-record attributes into typed columns on
  `otel_logs` while retaining `LogAttributes`. A native gate compares direct
  export with the same canonical record sent through a real loopback OTLP/HTTP
  socket and proves exact signed-Int64 readback plus capability-free status.

- Treat resource, scope, and span attributes as location-qualified fields under
  one confirmed `otel_traces` schema authority. The same logical key may be
  promoted independently at all three locations; export, direct/OTLP ingestion,
  saved bindings, discovery, filters, aggregates, and coverage retain the
  location. Existing `ResourceAttributes` and `SpanAttributes` maps remain
  unchanged, while scope history is reported unavailable because ClickStack's
  trace table has no generic scope-attribute map.

- Pin the merged OTel closed-exporter configuration and background-export
  failure retention fixes, with a resolved-classpath guard for the reviewed
  dependency revision.

- Validate immutable typed descriptor evidence once when its private,
  connection-bound capability is minted. Typed query and projection hot paths
  retain issuer and exact target checks without repeatedly walking the captured
  registry catalog and manifest.

- Expose capability-bound typed span coverage independently of filtered trace
  retrieval. The coverage-only operation requires the exact logical schema
  binding, runs one bounded library-owned query, and returns conserved counts
  for valid, present-empty, absent, invalid, historical fallback-present, and
  historical unavailable rows without retrieving or materializing traces.

- Pin the merged OTel log-body and independent-pipeline contracts. Direct SDK
  scalar and structured bodies are canonical AnyValues before export; maps and
  arrays retain JSON text in ClickStack's existing `Body String` column, byte
  strings use base64, explicit empty values use the empty string, and malformed
  bodies retain OTel's readable fallback. Attribute maps use the same pdata
  string projection for canonical empty, byte, map, and array values.

- Add exact signed-Int64 equality and lower/upper-bound span filters to the
  capability-bound typed trace query. Numeric-looking historical or invalid
  fallback text remains excluded, while the existing count/min/max/average
  aggregate surface supplies the bounded numeric summaries used by viewers.

- Add capability-bound Boolean and string filters for typed span attributes.
  Boolean equality and exact/prefix/contains string predicates use only bound
  values and status-valid physical columns. Results include bounded trace
  summaries plus separate counts for valid, present-empty, absent, invalid,
  historical fallback-present, and historical unavailable rows; lossy legacy
  map text never enters a typed predicate.
- Add read-only acquisition of persisted active typed-span descriptors for
  restart and fresh-process consumers. Acquisition freshly verifies the exact
  physical table, then fences publication on unchanged catalog ETag, revision,
  and record generation without accepting a DDL effect or writing the catalog.
- Add a bounded capability- and connection-confirmed Int64 span aggregate
  query. Exact half-open range predicates and closed count/min/max/average
  recipes read only status-valid physical values; numeric-looking historical or
  invalid fallback text never participates. Query scans and results are bounded,
  and no sorting or skip index is generated.
- Pin OTel artifact discovery and add a bundle-backed v3 typed-manifest compiler.
  An explicit operator deployment binding consumes a validated canonical bundle;
  its digest and complete artifact identities are checksum-bound compact
  provenance. Existing v2 catalogs remain readable, bundle drift at one version
  conflicts, and only the separately invoked installer can authorize additive
  DDL.
- Pin the merged canonical OTel receiver record contract and qualify that direct
  export and real OTLP JSON receiver ingestion produce identical typed physical
  rows with the same descriptor capability and chDB connection.
- Qualify typed physical-schema evidence by canonical signal and table. Closed,
  bounded observations reject missing, duplicate, unknown, cross-signal, and
  wrong-table evidence before DDL or descriptor publication, while physical
  installation remains limited to span attributes on `otel_traces`.
- Version typed attribute manifests and reviewed fragments to v2 with an
  explicit closed signal, physical table, and location identity. Checksums,
  field IDs, registry planning, projection, and queries share that identity;
  legacy ambiguous v1 manifests fail closed and require operator-reviewed
  migration. Only span attributes on `otel_traces` are physically enabled.
- Add a capability- and connection-bound explorer query for approved typed span
  keys. Physical value/status columns are library-owned, caller values remain
  parameters, historical or invalid rows use the generic attribute map, and
  present-empty rows remain queryable. A native chDB round trip qualifies real
  installation, export, map fallback, and grouped selection for every status.
- Allow the span exporter to consume the installer-issued active descriptor
  capability and populate typed value/status columns. Generic SpanAttributes
  remain present, and export without a capability retains legacy behavior. A
  private issuer and exact process-local target identity prevent ordinary
  capability forgery or cross-connection reuse.
- Add a span-only typed-column installer with injectable DDL execution and
  physical-schema observation. It persists preparing before additive DDL and
  returns descriptors only after fresh observation and active-state CAS.
  Repairing an active or failed record first persists its new preparing
  generation, so a crash cannot leave DDL authorized only by stale state.
- Add a bounded, canonical EDN CAS store for typed-attribute registry catalogs
  over the jolt-chDB Durable object-backend contract. Opaque ETags prevent lost
  updates, record generations cannot skip, and ambiguous creates/replaces are
  accepted only when a reread proves the intended catalog was persisted.
- Add persistence-ready typed-attribute registry records and a deterministic
  span-column reconciliation planner. Records move through preparing, active,
  failed, and retired states using caller-enforced generation CAS values; this
  slice uses public Malli schemas for its closed data envelopes, emits data
  operations, but does not execute DDL.
- Resolve `jolt-crypto` once from canonical upstream revision `5effcc89` and
  exclude OTel's older compatibility-fork declaration. Dependency selection no
  longer depends on graph traversal order.
- Add a pure, deterministic compiler for operator-reviewed typed attribute
  manifests. It produces stable ClickHouse field descriptors without changing a
  database or authorizing schema changes from telemetry.
