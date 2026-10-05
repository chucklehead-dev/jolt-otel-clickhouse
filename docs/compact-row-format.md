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

## Current evidence

Focused tests: 12 tests / 39 assertions cover projection, column ordering and
quoting, missing/extra/null-row rejection, pre-overflow lazy-row behavior,
constructor validation before acquisition, unchanged default wire, and one
confirmed Durable request, typed null/status slots and ordinary all-before-driver
validation. An extra-field-dropping mutant produces 38 passes / one expected
failure. A fresh-process ordinary native smoke passes one test / 11 assertions,
including question marks, quotes, Unicode and exact timestamp ticks. Default
metric/scalar/span regressions pass 20 tests / 181 assertions.

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
equivalence; typed native/socket coverage and repeated performance remain gates.

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
