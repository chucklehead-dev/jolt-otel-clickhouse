# Compact row input (opt-in candidate)

`(chdb/exporter {:insert-format :json-compact-each-row ...})` selects standard
ClickHouse `JSONCompactEachRow` input: one array per row, with the exporter
providing the matching ordered column list. OTel input remains normal maps and
records; queries, stored columns, attribute descriptors and persistence remain
unchanged. No second JSON encoder or binary WAL format is introduced.

This can reduce repeated physical column names and their parsing/escaping cost.
The default is still `:json-each-row`. JSON backend selection is independent:
`:configured` uses maintained data.json, while `:native-guarded` retains its
qualified source-runtime requirement. No standalone native/AOT claim is added.

## Safety and observable behavior

- A physical row must contain exactly its declared columns. Missing/extra fields
  fail before driver entry with a fixed error and no retained field/value data.
- Projection is schema-ordered and lazy between rows. Serial encoding checks
  each complete row's UTF-8 size before requesting the next row. As before,
  arbitrary input sequences may themselves realize chunks, and custom writers
  remain trusted operations which may allocate before size is known.
- Ordinary metric export still prepares/validates every physical batch before
  the first driver call. It does not promise native rollback.
- Each Durable physical insert still uses one `execute-and-flush!` request.
  Success still requires committed/reconciled publication; no group-commit,
  admission-only acknowledgement or parallel encoding is enabled.
- Specialized JSONEachRow span/log writers are not used in compact mode.
  JSONWriter extensions see schema-ordered arrays instead of row objects;
  nested attribute maps still use their usual writer. Applications relying on
  custom row-map writer/omission behavior should retain JSONEachRow.

This option is currently a library candidate, not yet an Oscope settings option
or merged default. Required review and broader integration checks remain.

The [acknowledgement model](../formal/quint/durable-export-ack.md) deliberately
abstracts serialization and SQL shape. Its unchanged queue/confirmation
invariants do not prove positional field binding. That obligation belongs to
the projection, unknown-field negative control and native field-readback gates
below; green model checks alone would not detect same-type field swaps.

## Running typed socket checks

The existing native OTLP/HTTP acceptance fixtures can select either format:

```sh
jolt -M:typed-log-socket-test json-compact-each-row
jolt -M:typed-gauge-socket-test json-compact-each-row
jolt -M:typed-histogram-socket-test json-compact-each-row
```

Omit the argument to check the unchanged JSONEachRow default. Each command
belongs in a fresh process with the qualified native library/runtime; run native
commands serially. These exercise real loopback OTLP ingestion, not just the
receiver handler. The log fixture captures and delegates the selected native
transport before checking its fixed-column negative control. The gauge/sum and
log capability-free controls also use the selected format, so historical rows
are tested after typed columns have been installed.

The two-process Durable typed fixture likewise accepts an optional third
`json-compact-each-row` argument after `writer|reader` and its shared store root.
Its existing writer/readiness/reader handshake still applies. This is a local
acceptance fixture, not a replacement for the canonical integrity/release gate.

## Current evidence

Focused tests: 14 tests / 46 assertions cover fixture selection, projection, column ordering and
quoting, missing/extra/null-row rejection, pre-overflow lazy-row behavior,
constructor validation before acquisition, unchanged default wire, and one
confirmed Durable request, typed null/status slots and ordinary all-before-driver
validation. The earlier 12-test extra-field-dropping mutant produced 38 passes / one expected
failure. A fresh-process ordinary native smoke passes one test / 11 assertions,
including question marks, quotes, Unicode and exact timestamp ticks. Default
metric/scalar/span regressions pass 20 tests / 181 assertions.
A same-type String column-swap mutant produces 45 passes / one expected
failure; native coercion alone cannot reject that swap.

An earlier closed-fixture experiment changed logs/metrics only (spans remained
JSONEachRow). It measured 23,185.51 stored rows/s and 7,383,241,984 allocated
Scheme bytes, versus the recent serial 18,133.91 / 8,342,326,896 screen. Same
cumulative Jolt artifact, native chDB 26.7.3, local POSIX storage, ten 5k batches
per table, per-physical-insert confirmation. One sequential screen, not causal
repeated tail/S3/Rust qualification.

The actual guarded implementation, now compact for all five tables and with no
private-function replacement, measured **25,090.19 rows/s** and
**7,622,302,912 allocated bytes** on the same workload and selected compiler/
dependency stack. This is a single local mean-rate screen, not proof of the
25k p50 / 20k p99 target. An independent product-store reader confirms counts
and selected field aggregates across all five tables, including span duration,
kind/status, attributes and exact timestamp ticks. This is not full-value
equivalence; repeated performance remains a gate.

On that same selected stack, compact input passes the existing real socket
acceptance checks for typed logs, gauge/sum (15 checks), and explicit histograms
(8 checks). They retain exact Int64/Boolean/String values, generic fallback maps,
schema-bound filters/discovery and availability coverage. These are bounded
ordinary-native tests, not S3, application startup or concurrent schema-owner
qualification.

A separate writer and snapshot-reader process also pass the existing local
Durable typed fixture in compact mode: 25 writer checks and 24 reader checks.
It checks typed span/log Int64 bounds, Boolean/status/fallback values, typed
gauge/sum resource/scope/point values, exact timestamp/event nanoseconds, and
invalid-batch rejection without changed counts or head/etag. The reader sees a
base plus persisted WAL while the live writer is parked, then the writer's
signal shutdowns complete. This is not a crash-kill, S3 or concurrent-DDL test.

The prior, log-only candidate in exporter issue #82 required a live DESCRIBE
schema/type fence and had a separate insert-shape model. This newer candidate
has explicit source/descriptor-derived columns and closed row-shape checks,
but has not ported that live fence or qualified the older model against all
five tables. Do not infer those guarantees from the unchanged ACK model or
from these native tests. Review/resolve this boundary before app adoption.

Independent fresh readers of the experimental store confirm 250k rows and
selected log/metric field aggregates: resource/scope data, map cardinalities,
metric names/units/timestamps/flags, value sums, histogram count/sum/min/max,
bucket totals/bounds and temporality, plus log severity/flags/body length/event
name. This is stronger than counts, but not full-value equivalence.

Other diagnostics helped narrow the plan:

- Removing live protocol resolution in a closed encoding-only fixture reduced
  encoding time ~20%. It is NOT a correct production optimization.
- Replacing chunk accumulation with memory ports was ~10% slower and allocated
  more; no representation migration was promoted.
- Four-worker encoding reached 21,072.47 rows/s, but its diagnostic whole-payload
  budget check does not satisfy the production incremental-overflow contract.
  It was not promoted.
- Public SDK-call timing measured 19,145.65 rows/s versus 18,083.72 including
  fixture generation. Fixture construction accounted for only ~5.5%, so it does
  not explain most of the gap. Both exclude real socket/SDK-queue ingestion.
