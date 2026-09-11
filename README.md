# jolt-otel-clickhouse

Direct in-process export from `jolt-otel` to embedded chDB. The initial schema
uses ClickStack's correlation and search column names (`TraceId`, `SpanId`,
`ParentSpanId`, `ServiceName`, `SpanName`, `Duration`, `Body`, and attribute
maps), so the demo and later ClickStack integration share the same query model.

The library can also compile reviewed attribute declarations and build-time
`otel.attribute-schema/v1` hints into a deterministic typed storage manifest.
Its bundle-backed path consumes an operator-selected set of artifact indexes
through OTel's validated `otel.attribute-schema.bundle/v1` contract, then binds
the complete artifact identities to an explicit deployment and to every closed
signal/table/location field identity. The resulting checksummed manifest can be
prepared as a registry record, persisted in the complete catalog through an
object-backend compare-and-set, and reconciled against bounded table-qualified
schema evidence. An explicit deployment installer can execute only those
registry-owned additive columns, re-observe the table, and publish descriptors
only after the active generation is persisted. Ambiguous writes are proved by
canonical reread and interrupted preparing records remain recoverable.
Restarted or read-only consumers can reacquire the process-local descriptor
capability from an operator-selected active record after fresh schema and
catalog-freshness checks, without DDL or a catalog write. That capability can
enable typed span columns on the existing exporter, bounded value
distributions, exact Int64 range aggregates and trace filters, and typed
Boolean/string trace filters with explicit availability coverage while
retaining its generic `SpanAttributes` map. Typed predicates use only
status-valid physical values and never reinterpret fallback text.
See
[`docs/typed-attributes.md`](docs/typed-attributes.md) for the boundary and an
example, including the fail-closed v1-to-v2 manifest migration boundary.

## Install

Pin the exact commit you have reviewed:

```clojure
{:deps
 {io.github.chucklehead-dev/jolt-otel-clickhouse
  {:git/url "https://github.com/chucklehead-dev/jolt-otel-clickhouse.git"
   :git/sha "<full-commit-sha>"}}}
```

The placeholder must be replaced with a full 40-character commit SHA. The
library also pins exact commits of `jolt-chdb`, `jolt-otel`, and `data.json`;
aliases from those Git dependencies do not propagate to applications. It
selects canonical upstream `jolt-crypto` `5effcc89` directly and excludes the
older compatibility-fork revision inherited through its OTel pin. That
revision contains the Jolt 0.8 value-first FFI migration, JDK crypto provider
declarations, and large-input digest/signature fixes. Install the chDB native
library explicitly with:

```sh
jolt -m jdbc.chdb.install
```

Set `JOLT_CHDB_LIB` instead when using an already installed compatible
`libchdb`.

```clojure
(def exporter (otel.exporter.chdb/exporter {:db-spec "chdb:telemetry.chdb"}))
(def telemetry (otel.sdk/init! {:service-name "agent"
                                :exporter exporter
                                :logs? true}))
```

An exporter-owned connection can keep telemetry isolated in a logical database
on the application's one active physical chDB path:

```clojure
(otel.exporter.chdb/exporter
 {:db-spec {:vendor "chdb"
            :name "/var/lib/my-app/chdb"
            :database "otel"}})
```

The chDB driver validates, creates, and selects `:database` when it opens the
connection. Schema migrations and exports continue to use the fixed unqualified
names `otel_schema_migrations`, `otel_traces`, `otel_logs`, and
`otel_metrics_*`; they therefore live in the selected database without an
unsafe table-prefix option. Multiple logical databases may use those same
names on the one physical path without colliding.

When supplying an application-owned `:connection`, select its logical database
in that connection's dbspec. The exporter uses its current database naturally
and never issues `USE`; shutdown also leaves the shared connection open.

### Durable acknowledgement

Pass a Durable writer connection with `:durable? true` when exporter success
must mean that the batch can be recovered from object storage:

```clojure
(require '[jdbc.chdb.durable.backend :as durable-backend]
         '[jdbc.core :as jdbc]
         '[otel.exporter.chdb :as chdb-export])

(def store (durable-backend/memory-backend)) ; use local POSIX or S3 in production
(def conn
  (jdbc/connection
   {:vendor "chdb-durable" :backend store
    :owner "telemetry" :instance "process-1"
    :database "otel" :lease-ttl-ms 30000}))

(def exporter
  (chdb-export/exporter
   {:connection conn :durable? true
    :signals #{:spans :metrics :logs}}))
```

Startup rejects an ordinary chDB connection or a read-only Durable connection
before schema mutation, then checkpoints the migrated schema. Each non-empty
span or log batch flushes once after insertion. A metric collection flushes
once after all of its table inserts. An empty batch does not publish a new
manifest. If insertion or the persistence barrier fails, export returns
`false` and `last-error` retains the cause. Span force-flush reaches the same
barrier.

This is an at-least-once boundary: a failed or ambiguous attempt can have made
local progress, so an SDK retry may produce duplicates. Durable's flush only
returns successfully after its manifest transition is committed or reconciled.
Callers with another persistence implementation can supply one callable
`:persistence-barrier`; it must return a truthy confirmation or throw, and is
mutually exclusive with `:durable?`.

The literate model and its checked success trace live in
[`formal/quint/durable-export-ack.md`](formal/quint/durable-export-ack.md).
The Hegel history property replays all signal kinds and the checked-in Quint ITF
success trace against the exporter boundaries.

The exporter implements span, log, and metric exporter protocols. Metrics use
ClickStack's `otel_metrics_gauge`, `otel_metrics_sum`, and
`otel_metrics_histogram` table names. It sends each SDK-bounded batch through
chDB's ordinary query API with `FORMAT JSONEachRow` and an 8 MiB safety limit.
libchdb 26.7's streaming-insert API corrupts ClickHouse `ThreadStatus` nesting
for this multi-signal exporter. Export returns `false` instead of throwing into
observed application code and exposes `last-error` for diagnostics. Pass an existing
`:connection` when the application UI also queries the database; that keeps
connection ownership with the application.

When the exporter owns its connection and logs are enabled, declare all active
signals: `{:signals #{:spans :metrics :logs}}`. Shutdown is tracked per signal,
so the SDK's metric shutdown cannot disable a later log/span batch drain. An
export attempt for a signal omitted from this set returns `false` and records a
descriptive `last-error` instead of failing later against a closed connection.
The final signal atomically claims the one connection close before calling the
connection. Concurrent and repeated shutdowns therefore cannot close it twice.
A shutdown racing an in-progress close reports that the close was accepted. If
the owning close call throws, that call and every later shutdown return `false`;
the exporter records the terminal failure in `last-error` and does not retry a
potentially partial native teardown.

Migration v2 adds ClickStack's seven physical `Events.*` and `Links.*` Nested
subcolumns and the `otel_traces_trace_id_ts` lookup table/materialized view.
Span export fills the parallel arrays and continues filling `EventsJSON` and
`LinksJSON` for the lightweight embedded viewer. Migration v3 normalizes all
16 embedded log insert columns to the pinned collector's types and codecs. Log
export uses that explicit column list, including its supported `EventName`
feature, and follows the collector's timestamp fallback, pdata string, and
`UInt8` conversion semantics. Migration v4 adds the pinned gauge, sum, and
explicit-histogram insert columns and types. Resource/scope schema URLs are
preserved; fields absent from the current canonical metric model use their
truthful empty defaults: zero flags/dropped-attribute count and five aligned
empty exemplar arrays. These are bounded trace/log/metric insert parity, not a
claim that the physical tables are drop-in ClickStack. In particular,
non-empty exemplars, non-zero point flags, dropped scope attributes,
exponential histograms, and summaries are not modeled, and the embedded tables
do not mirror every partition, skip index, TTL, comment, or MergeTree setting.

## Schema migrations

Exporter startup runs `otel.exporter.chdb.schema/migrate!`. Migration v1 owns
the five existing OTel tables, so databases created by earlier releases are
adopted without rebuilding or deleting them. Applied migrations are recorded in
`otel_schema_migrations` with a consecutive version, stable name, SHA-256 of
the ordered SQL statements, and UTC application timestamp. Reopening a database
is idempotent; a changed name/checksum, duplicate version, gap, or database from
a newer migration plan fails startup with diagnostic `ex-data` instead of
silently mutating history.

Migration v1 remains the immutable five-table baseline. Migration v2 adopts
existing trace tables in place with idempotent `ADD COLUMN IF NOT EXISTS`
statements, then creates the trace-ID time lookup table and view. Its source
provenance and exact collector insert column order are recorded in
`docs/fixtures/clickstack-traces-aad2838d.edn`.

Migration v3 adopts existing log tables in place through retry-safe `MODIFY
COLUMN IF EXISTS` statements. Its exact source hashes, base/feature insert
columns, and collector types are recorded in
`docs/fixtures/clickstack-logs-aad2838d.edn`. The pre-existing `EventName`
column is retained for the embedded viewer and matches the pinned collector's
schema-detected optional feature.

Migration v4 adopts the collector insert schemas for the three metric kinds
the current SDK produces. It adds canonical resource/scope metadata, flag, and
exemplar columns and normalizes compatible types/codecs. The initial schema
uses the pinned collector's `DateTime` types for `StartTimeUnix` and `TimeUnix`;
there is no pre-release legacy timestamp layout to preserve. The exact source
hashes, insert order, and model defaults are recorded in
`docs/fixtures/clickstack-metrics-aad2838d.edn`.

Migration history is local to the connection's selected logical database. Each
logical database is therefore independently initialized and validated.

chDB has no transactions. Each migration must therefore contain only
idempotent statements. The runner records a version after every statement has
succeeded; a statement or registry-write failure reports its phase, version,
name, checksum, and statement context, leaves the migration unrecorded, and can
be retried on the next open. The bootstrap `CREATE TABLE IF NOT EXISTS
otel_schema_migrations` is the only operation outside the versioned registry.
Keep application startup for one database serialized; cross-process migration
locking is not part of this initial foundation.

## Bounded attribute explorer

`otel.exporter.chdb.explorer/top-values` provides a small, read-only query API
for reusable viewers. A request must name one signal, one or more allowlisted
fields, a half-open epoch-nanosecond time window, and a per-field result limit:

```clojure
(explorer/top-values
 conn {:signal :spans
       :fields [:service-name :http-request-method]
       :start-unix-nano start
       :end-unix-nano end
       :limit 20})
;; => [{:signal :spans, :field :service-name, :value "api", :count 12} ...]
```

The hard limits are a 24-hour window, 8 fields, 100 buckets per field, and 256
UTF-8 characters per returned value (128 by default). Long values are grouped
by their displayed prefix. `supported-fields` returns the complete closed
allowlist. Spans support service, span name/kind/status/scope, HTTP method and
status, and deployment environment. Logs support service, severity, event,
scope, and deployment environment. Metrics support service, metric name/unit,
scope, and deployment environment across the union of gauge, sum, and
histogram tables.

All identifiers and semantic attribute keys are library-owned SQL fragments;
caller-controlled signal or field strings are rejected and scalar values are
bound parameters. This first layer intentionally does not expose arbitrary map
keys, free-form SQL, pagination, approximate cardinality, metric-kind facets,
or separate per-kind metric results. Empty strings are omitted. The per-field
queries are individually capped, so a successful call returns at most
`fields * limit` rows and may observe concurrent inserts between fields. The
pinned metric tables store `TimeUnix` at whole-second precision, so metric
window membership is necessarily evaluated at that stored precision; trace and
log windows retain their `DateTime64(9)` nanosecond precision.

### Bounded metric series

`explorer/metric-series` turns an exact metric name and a closed aggregation
recipe into rows suitable for a chart. The recipe, rather than the returned
sample rows, can be saved in a dashboard or Plotje document:

```clojure
(explorer/metric-series
 conn {:metric-kind :gauge
       :metric-name "http.server.active_requests"
       :bucket :5m
       :group-by [:service-name]
       :aggregates [:avg :p95 :p99]
       :start-unix-nano start
       :end-unix-nano end
       :limit 100})
;; => [{:bucket-start-unix-nano 1700000100000000000
;;      :service-name "checkout", :avg 4.2, :p95 8.0, :p99 9.0} ...]
```

Call `supported-metric-series` to obtain the complete vocabulary. Gauge and
sum point values support `:count`, `:sum`, `:min`, `:max`, `:avg`, `:p50`,
`:p95`, and `:p99`. Delta explicit-histogram points support observation
`:count`, `:sum`, and `:avg`; cumulative histogram snapshots are excluded.
Recipes may use no bucket or fixed `:1m`, `:5m`, `:15m`, or `:1h` buckets and
may group by service, metric unit, scope, or deployment environment. The same
24-hour, 100-row, and 256-character hard caps apply.

The semantics intentionally follow the stored OTLP points. Scalar percentiles
use ClickHouse's approximate t-digest over gauge or sum point values. A `:sum`
of a cumulative sum instrument still sums its stored snapshots; it is not a
counter increase or rate. Histogram percentiles are not available from this
scalar/delta API because they require temporality-aware bucket reconstruction.
The separate `cumulative-histogram-series` operation provides that stricter
contract. The metric kind is explicit instead of silently mixing same-named
rows from different physical tables.

`explorer/cumulative-counter-series` is the separate, stricter path for
counter increase and rate. Its request must state all three stored provenance
facts explicitly:

```clojure
(explorer/cumulative-counter-series
 conn {:metric-kind :sum
       :temporality :cumulative
       :monotonic? true
       :metric-name "http.server.requests"
       :bucket :5m
       :group-by [:service-name]
       :aggregates [:increase :rate]
       :start-unix-nano start
       :end-unix-nano end
       :limit 100})
;; => [{:bucket-start-unix-nano ...
;;      :service-name "checkout"
;;      :increase 42.0 :rate 0.14
;;      :metric-kind :sum :temporality :cumulative :monotonic? true
;;      :interval-count 3 :reset-count 1
;;      :observed-duration-nanos 300000000000}]
```

Increase is the sum of exact differences between ordered snapshots from one
complete OTEL stream identity. When `StartTimeUnix` advances, the new value is
an explicit reset interval beginning at that stored start. Rate is increase
divided by the summed duration of those observed intervals, in seconds. A
first snapshot whose start predates the requested window is not used because
its boundary delta is unknown. This is observed-interval rate, not Prometheus
boundary extrapolation.

`:reset-count` counts reset intervals represented in a result: the first
positive-duration interval whose `StartTimeUnix` is inside the window counts,
as does each later positive-duration interval after the stored start advances.
A zero-duration zero-valued reset produces no rate interval and is not counted.

The operation fails closed when the stored seconds cannot prove an ordering or
reset: duplicate timestamps, a decrease without a new start, overlapping reset
epochs, or a positive reset value with zero duration are errors. It also rejects
a selected projection that collapses distinct resource/scope/attribute stream
identities. A bucket accepts only intervals wholly contained by it; crossing
intervals are rejected rather than split proportionally. Results always expose
the stored kind/temporality/monotonic provenance plus interval and reset counts.

In addition to the 24-hour, 100-result, and 256-character caps, the raw snapshot
query is capped at 10,000 result rows and 64 MiB. chDB may scan at most 100,000
rows or 64 MiB, use 128 MiB of query memory, run for 5 seconds, and use one
query thread. The pinned ClickStack tables store both counter timestamps at
whole-second precision, so ordering and rate durations have that same explicit
precision.

As with `top-values`, every caller-controlled scalar is a JDBC parameter and
the tables, dimensions, buckets, aggregate functions, aliases, and ordering
come from library-owned allowlists. Unknown recipe keys and choices fail before
executing SQL.

### Cumulative explicit histograms

`explorer/cumulative-histogram-series` reconstructs bounded interval histograms
from cumulative OTEL explicit-histogram snapshots. The request must state the
stored provenance explicitly:

```clojure
(explorer/cumulative-histogram-series
 conn {:metric-kind :histogram
       :temporality :cumulative
       :metric-name "http.server.duration"
       :bucket :5m
       :group-by [:service-name]
       :aggregates [:count :sum :avg :p50 :p95 :p99]
       :start-unix-nano start
       :end-unix-nano end
       :limit 100})
;; => [{:service-name "checkout"
;;      :count 42 :sum 3150.0 :avg 75.0
;;      :p50 {:quantile 0.5, :estimate 62.5
;;            :lower-bound 50.0, :upper-bound 100.0
;;            :absolute-error-bound 37.5
;;            :interpolation :uniform-within-explicit-bucket ...}
;;      :metric-kind :histogram, :temporality :cumulative
;;      :explicit-bounds [10.0 50.0 100.0]
;;      :interval-count 3, :reset-count 1
;;      :observed-duration-nanos 300000000000}]
```

Count, sum, and every explicit bucket are differenced between exactly ordered
snapshots of one complete OTEL stream. Stream identity includes the full
resource, scope, instrument description/unit, and point attributes, not only
the selected chart dimensions. When `StartTimeUnix` advances, the new snapshot
starts a reset epoch and contributes from that stored start. A first snapshot
whose start predates the requested window is omitted because its boundary
delta is unknown. No scalar observations are synthesized from `Min`, `Max`, or
`Sum`.

Quantile rank is `q * count`. The selected bucket follows OTEL explicit bucket
semantics: `(-Inf, b0]`, `(b0, b1]`, ..., `(bn, +Inf)`. For a finite bucket the
reported estimate linearly interpolates the rank under an explicitly named
uniform-within-bucket assumption. `:lower-bound` and `:upper-bound` state the
distribution-free containing interval, and `:absolute-error-bound` is the
larger distance from the estimate to either endpoint. This error can be as wide
as the bucket and is not a statistical confidence interval. In an implicit
infinite edge bucket the missing endpoint, estimate, and numeric error are
`nil`, with `:lower-unbounded?` or `:upper-unbounded?` true; the library does not
invent a finite tail. When reconstructed count is zero, `:avg` and every
requested quantile are explicitly `nil`. A window with no reconstructable
intervals returns `[]`.

Bucket selection compares the fixed percentile as an exact integer fraction,
so cumulative UInt64 counts above `2^53` do not pass through double precision.
Each nonempty quantile exposes that exact rank as `:rank-numerator` and
`:rank-denominator`; `:rank` and the within-bucket estimate are floating-point
display values and do not control bucket selection.

The operation fails closed on a changed or malformed boundary schema, changed
physical column types, non-finite values, count/bucket inconsistency, point
flags, duplicate stored seconds, backwards or overlapping epochs, same-epoch
count/bucket decreases, inward-moving cumulative extrema, a projection
that collapses distinct streams, or an interval crossing a requested chart
bucket. Failures report only structural reasons and bounded timestamps/counts;
raw attributes, bucket contents, and telemetry values are omitted from
exception data. Invalid request evidence likewise omits the supplied metric
name, including overlong credential-like strings.

`Sum` is differenced arithmetically and may decrease within an epoch when new
observations are negative; only a non-finite differenced sum is invalid. Count
and bucket vectors remain cumulative structural evidence and cannot decrease
without a new `StartTimeUnix`.

The same 24-hour, 100-result, and 256-character request caps apply. The source
query is additionally capped at 10,000 snapshots and 64 MiB of results; chDB
may scan at most 100,000 rows or 64 MiB, use 128 MiB, run for 5 seconds, and use
one query thread. Both histogram timestamps have the pinned table's whole-second
precision. `supported-cumulative-histogram-series` returns the closed recipe
vocabulary.

## Development and releases

Use Jolt v0.8.3 or newer. Install the pinned native dependencies, then run
`jolt -M:test`. A release is an immutable Git tag pointing at a commit for
which the test workflow passed; consumers should continue to pin that commit
SHA even when also recording the tag.

### Backend benchmark

The opt-in benchmark drives deterministic span, log, gauge, sum, and histogram
batches directly through the ClickStack-compatible exporter into embedded
chDB. It measures backend work, not HTTP client performance. Every run fails on
stored-count disagreement and reports per-signal batch p50/p95/p99/max latency,
total stored items per second, representative dashboard-query latency, and raw
Jolt CPU/real/GC/memory counters for ingestion and querying. The derived
Scheme-heap allocation total is exact for Chez-managed memory, but excludes
native chDB allocation; calling-thread CPU likewise excludes chDB workers.

Run the default 5,000-item in-memory baseline:

```sh
jolt -M:benchmark
```

The optional arguments are `<db-spec> <batches> <items-per-batch>
<query-iterations> [output.edn]`. Use a fresh filesystem dbspec to compare
persistent storage. Performance numbers are evidence rather than CI
thresholds. The test suite runs the same reconciled workload at bounded counts
as a compile and correctness gate.

The dated baseline and runtime availability matrix are in
[`docs/benchmarks/jolt-chdb-backend.md`](docs/benchmarks/jolt-chdb-backend.md).

This project is licensed under the Eclipse Public License 2.0; see `LICENSE`.
