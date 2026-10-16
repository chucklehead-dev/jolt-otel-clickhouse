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

- Compact startup confirms live column names/types for every enabled signal.
  `:metrics` requires gauge, sum and histogram tables, including any confirmed
  promoted columns. Missing/wrong/ambiguous schema fails with a fixed error;
  there is no silent switch to named input. Select JSONEachRow explicitly if
  the compact schema cannot be confirmed.
- A private, opaque plan binds each confirmation to its connection and explicit
  column order. Physical DESCRIBE order and additional unselected columns do not
  change that order. Reusing the plan on another connection or changing the
  ordered insert vector is rejected before insertion. Plan printing does not
  expose the connection. No steady-state DESCRIBE query is added.
- Confirmation is a startup snapshot under the single-schema-owner contract,
  not a DDL lock or cross-process freshness proof. Do not change the schema
  while that exporter is active; settle/retire it and construct a new exporter
  after schema changes. Concurrent schema administration remains unqualified.
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

## Checks for this integration

Run each native command in its own process, with the declared 26.7.3 package:

```sh
jolt -M:compact-wire-test
jolt -M:compact-wire-native-test
```

The focused tests cover column/type confirmation, exact order and connection
binding, fixed error data, missing/extra/null row rejection, same-type field
swaps, incremental overflow and all-before-driver metric validation. The native
fixture exports spans, logs and every supported metric kind, checks Unicode,
question marks and nanosecond timestamps, rejects changed live types and column
order, and confirms there is no steady-state DESCRIBE.

The scoped SDK fixture also accepts compact input and compares full physical
rows against an independent fresh reader of the loopback S3-compatible store:

```sh
JOLT_CHDB_SOURCE_ROOT=/path/to/declared-clean-driver \
  bash scripts/qualify-scoped-s3-exporter.sh /path/to/jolt json-compact-each-row
```

That is not a performance or real-AWS qualification. Existing OTLP socket
fixtures have not yet been adapted in this port; do not infer format-selection
arguments there. Direct-vector construction and owned output remain follow-up
work. The internal plan seal rejects manufactured values; it is not a defense
against private Var access, reflection or unsafe FFI.
