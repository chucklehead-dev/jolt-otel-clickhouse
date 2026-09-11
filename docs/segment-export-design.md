# Columnar segment export to a central ClickHouse

A design for fanning embedded telemetry stores into a central ClickHouse or
ClickStack cluster, without replaying SQL and without a network ClickHouse
client at the edge.

Status: proposal. Nothing here is implemented. The throughput and sizing claims
are measured and recorded in
[`benchmarks/segment-export.md`](benchmarks/segment-export.md); this document
is the argument built on top of them.

## The gap this closes

[`clickstack-compatibility.md`](clickstack-compatibility.md) records the
remaining drop-in gate:

> Validate actual ClickStack UI behavior through a network-facing ClickHouse
> endpoint or an explicit gateway/collector adapter. HyperDX cannot attach to a
> local embedded chDB directory, so pointing it at the directory is not a valid
> integration test.

and the constraint on how to close it:

> For a server ClickHouse target, keep the same exporter protocol but add a
> separate network driver/collector path; do not make the chDB FFI driver
> pretend to be a remote ClickHouse client.

An object-store hop satisfies both. The edge keeps the existing exporter and
FFI driver unchanged; the centre reads files. No ClickHouse network client is
introduced anywhere, and a central outage costs lag rather than data.

## What already exists

Durable V1 in `jolt-chdb` is already a per-writer store in object storage:

- `head.json` — manifest `{db, base, wal[], seq}` behind a fail-closed V1 codec
  that preserves fields unknown to this reader
- `wal/{generation}-{sequence}-{sha256}` — statement segments, capped at 128 MB
- `checkpoints/{generation}-{sequence}-{sha256}` — full backups
- a lease (`generation`, `owner`, `instance`, `expires_at`) fencing a **single
  writer per store**
- CAS publication with an explicit `:ambiguous` status that callers reconcile

So "each service owns a durable WAL in object storage" is not the missing
piece. The missing piece is that nothing in that bucket is ingestible by
ClickHouse: `wal/` holds SQL text, and `checkpoints/` holds a chDB backup.

## Three object classes, three consumers

The central decision is that **the WAL is a recovery journal, not a
transport.** Conflating those is what makes the rest hard.

| class | consumer | format | purpose |
| --- | --- | --- | --- |
| `wal/` | another chDB reader of this store | statement JSONL | crash recovery between checkpoints |
| `checkpoints/` | another chDB reader of this store | chDB backup | recovery base |
| **`segments/`** | **the central cluster** | **Parquet** | **fan-in transport** |

`segments/` is written by the same writer at seal time, incremental on the
`seq` that `head.json` already carries. It is a columnar read of data already
in MergeTree form, not a re-serialization, and it measured **~1.4% of ingest
CPU** at the recommended segment size.

### Why Parquet rather than the WAL

Not for centre-side CPU: measured, Parquet ingests only ~1.5x faster than the
JSONEachRow payload the WAL already carries. The reasons that hold are:

1. **Bandwidth.** 38.6–53.8 B/row against the WAL's 1,299.5 — a 21–32x
   reduction. A 1,000-node fleet at 2,000 spans/s is 640 Mbit/s of segments or
   20.8 Gbit/s of WAL. Only one of those is a system.
2. **Schema skew.** Parquet is self-describing and ClickHouse matches by column
   name with defaults for absent columns. A `CREATE TABLE` inside a replayed
   WAL that disagrees with the centre is a hard conflict with no good
   resolution — and with N independently-versioned services, skew is the
   normal case.
3. **Blast radius.** Replaying a WAL means the centre parses and executes SQL
   authored by every edge writer. Reading Parquet does not.

### Why not Vortex, yet

[ClickHouse PR #112950](https://github.com/ClickHouse/ClickHouse/pull/112950)
adds Vortex read/write. It is not a candidate today for three reasons, in order
of decisiveness:

1. **Vortex cannot write `Map`.** The migration v1–v4 schemas use `Map` in 22
   places plus `Array(Map(LowCardinality(String), String))` for
   `Events.Attributes` and `Links.Attributes`. Attributes are not optional in
   OTel; a telemetry segment cannot be serialized at all.
2. **The PR is unmerged**, so it is in no ClickHouse release and therefore not
   in chDB. Neither end could use it.
3. **It would not help this workload.** The PR reports geomean ~1.2x *slower*
   than Parquet on ClickBench after its parallel-read work. Vortex targets
   selective reads — pushdown, lazy materialization, count from metadata —
   and segment ingest is a bulk scan of every column.

It becomes interesting if `segments/` stops being a transport and becomes a
queryable cold tier read in place. Flattening `Map` to parallel key/value
arrays would unblock it, but that breaks the ClickStack compatibility four
migrations have earned, and is not recommended.

**Therefore: record the format, do not bake it in.** Each `segments/` manifest
entry carries a `"format"` field. `head.json`'s codec already preserves unknown
fields, which is the extension point. Flipping later is a writer change plus a
reader branch, with both formats servable during migration.

## Sealing policy

Rows per segment, not wall-clock, is the variable that matters. Measured, the
knee is near 50,000 rows; below ~10,000 both throughput and compression
degrade sharply.

```
seal when  rows >= 50,000  OR  age >= max_age
```

A node at 2,000 spans/s seals every 25 s and lands well past the knee. A node
at 100 spans/s must be allowed to wait rather than emit 6,000-row segments
every minute — `max_age` trades latency for efficiency and belongs in policy,
not in code.

Also set `output_format_parquet_row_group_size` explicitly. chDB's default
emitted 3 row groups for 1,000,000 rows, and ClickHouse parallelises across row
groups.

## Required change: a first-class export operation

`SELECT ... INTO OUTFILE` **cannot** ride the existing Durable writer. The
driver classifies it `:control`, and that verdict comes from libchdb's native
query analyzer (`jdbc.chdb.native`), not from Clojure policy, so it cannot be
reclassified from outside. `writer/do-query!` dispatches only `:read-only` and
`:mutating`; everything else reaches `policy/authorize-query!` and is rejected.

Nor can the result be buffered instead: `jdbc.chdb/max-encoded-result-bytes`
caps one `query-bytes` call at 64 MiB and 100,000 rows.

So the writer needs an `export-segment!` operation alongside `flush!` and
`checkpoint!`, which composes the `INTO OUTFILE` statement itself from a
validated table name, predicate and destination, and admits it without routing
library-authored SQL through the caller-facing query class gate. The statement
is never caller-supplied.

## Partitioning

`otel_traces` declares no `PARTITION BY` and orders by
`(ServiceName, SpanName, Timestamp)`, so time is the *last* key and "everything
since the watermark" is a full scan. Adding `PARTITION BY toStartOfHour(Timestamp)`
made export 1.5x faster and makes each segment a whole-part read.

This is a physical schema change. `clickstack-compatibility.md` states the
trace table "retains its v1 partition/order/index choices", so this is a
migration decision with compatibility consequences, and is **not** a
prerequisite: the unpartitioned scan still reached 264,917 rows/s, comfortably
inside the 1.4% budget.

## Fan-in: notifications, deduplication, watermarks

### Topology

`S3 → SNS → SQS → consumer → INSERT ... SELECT FROM s3(...)`.

SNS rather than S3-to-SQS directly: it costs nothing and lets a second consumer
attach without touching the first. Standard queues, not FIFO — MergeTree does
not care about insert order, and FIFO would throttle for a guarantee the
watermark already provides.

**One segment, one message, one INSERT, one deduplication token.** Measured,
per-segment INSERTs at 8-way concurrency beat a single batched glob INSERT by
1.37x with zero delayed or rejected inserts, so there is no throughput reason
to break that mapping — get throughput from consumer concurrency instead.

### Deduplication

At-least-once delivery plus retries means segments will be offered twice. Set
`insert_deduplication_token` to the object key. Because keys are already
`{generation}-{sequence}-{sha256}` — deterministic and content-addressed — a
re-upload after a crash produces the identical key, and a re-insert is a no-op.
Durable V1 solved this for its own purposes and the property transfers intact.
Measured, replaying all 60 segments through this path wrote nothing and left
the row count identical, including when eight duplicate deliveries of one
segment raced each other.

Four things about it are sharper than they look, all measured in
[`benchmarks/segment-export.md`](benchmarks/segment-export.md):

**The token is mandatory, not a belt-and-braces addition.** ClickHouse's
default content-hash deduplication does not cover `INSERT ... SELECT`, which is
exactly the shape segment ingest uses — an identical segment inserted twice
with no token produced 100,000 rows from 50,000. The same table deduplicates an
identical `INSERT ... VALUES` correctly, so this is a property of the insert
form, not of the table. There is no implicit backstop.

**The token must be a function of segment content and nothing else.** A
colliding token discards a real segment with no error, and a spuriously
differing token admits a duplicate; both were reproduced. The
`{generation}-{sequence}-{sha256}` key has the right property. A key carrying a
UUID or an upload timestamp would have exactly the wrong one.

**Size the window against the retry horizon.** `replicated_deduplication_window`
defaults to 10,000 blocks and `replicated_deduplication_window_seconds` to
3,600; hashes drop when either is passed, so the block count binds first at
fleet scale. At ~40 inserts/s — 1,000 nodes sealing 50,000-row segments — 10,000
blocks is about 250 seconds of history, so a redelivery more than roughly four
minutes late lands as a duplicate. Raise the count deliberately, and keep the
ledger table for anything beyond it.

**Replication is optional for this.** A single-node centre can set
`non_replicated_deduplication_window` and get identical token semantics with no
Keeper. Where replication is wanted for its own sake, it costs about 5% per
insert sequentially and nothing measurable at the 8-way concurrency this design
already uses; the token adds a further ~6%.

Never key segments by UUID or upload time. Determinism is what makes this work.

### Watermarks

Two, and they are not the same thing.

**Progress watermark**, per `(store, generation)`: the highest contiguous
sequence ingested. It gives gap detection (7 and 9 arrive, 8 never does),
query completeness, and — most importantly — a reconciliation path that does
not trust SQS. S3 notifications are best-effort and SQS retention caps at 14
days, so a periodic sweep that lists `segments/` *only* for stores whose
watermark has gone stale is what makes the system self-healing. It is cheap
because it is bounded to stragglers. Skipping it loses segments silently.

Note `generation` is the lease generation and bumps on writer failover, so
sequence legitimately resets: the watermark is the lexicographic pair, never a
scalar.

**Event-time watermark**, global: `min` over live stores of their highest
ingested event time, driving MV correctness and TTL. The classic failure is one
stalled node freezing the pipeline. Durable V1 supplies the fix already — the
lease `expires_at` distinguishes a dead store from a slow one, and an expired
lease holds nothing back.

### Retry

Classify rather than blanket-retry. Transient (S3 5xx, `TOO_MANY_PARTS`,
overload) retries with backoff. Poison (malformed Parquet, unresolvable schema
skew) goes straight to a DLQ and alerts; it must never consume receive-count
attempts or block the queue. Set the visibility timeout above worst-case insert
time, or heartbeat `ChangeMessageVisibility` during long inserts — otherwise a
slow insert is redelivered and deduplication is doing flow control's job.

Whether a poison segment blocks the progress watermark or is skipped with a
recorded gap must be an explicit policy choice, not an emergent one.

## Where the aggregation goes

If the edge is only a buffer, the OpenTelemetry Collector's file exporter is
the simpler answer and this design is not worth its complexity. Having a query
engine at the edge earns its place only by doing work there:

- **Pre-aggregating metrics** to fixed buckets before upload. 100–1000x volume
  reduction, and the only reduction that is lossless for its purpose. This is
  the strongest single argument for the architecture.
- **Tail sampling in SQL** — retain every span of any trace containing an error
  or exceeding a latency threshold. Sound for same-node traces; cross-node tail
  sampling still needs a central or trace-id-sharded tier.
- **Priority shedding** when the bucket is unreachable and disk is filling:
  drop by predicate rather than by age. A file buffer cannot do this, and it is
  the difference between losing debug spans and losing an incident.
- **Local query** for an agent observing its own telemetry without a round
  trip.

Raw tables at the centre stay dumb. Rollups, tiering and TTL are central
materialized views.

## Deployment shape

Durable V1 is single-writer per store, so "one store per service instance"
means N leases, N manifests and N sets of objects for the centre to discover.
In Kubernetes it also has a fatal flaw: an `emptyDir` WAL dies with the pod,
which is precisely the crash it exists to survive.

The recommended shape is therefore a **hybrid**: the application writes into a
node-local store on a `hostPath`, and a daemonset owns the lease, the sealing,
the upload and crash recovery. Application-crash durability is preserved — data
is on the node's disk before the export acknowledges, which is what
`formal/quint/durable-export-ack.md` already models — while node-level batching
naturally fills segments past the knee. One lease per node is exactly the shape
Durable V1 wants. Signals that must survive node loss (audit, billing) get
their own store on a PVC.

## Open questions

1. **A second replica.** The replicated measurements use one replica against a
   single-node Keeper, so they cover the Keeper round trip on the insert path
   but not replication traffic, part fetches, or replica lag.
2. **`PARTITION BY toStartOfHour(Timestamp)` on `otel_traces`** — worth 1.5x,
   costs a physical migration against a compatibility boundary.
3. **Real object storage.** All measurements use local files. No S3 latency, no
   multipart upload, no `s3Cluster` fan-out.
4. **WAL retention once segments carry the data.** If segments are the
   transport, the WAL could stay node-local and never be uploaded, cutting
   egress further. That depends on whether any reader recovers this store
   remotely.
5. **Small-file management** at fleet scale: hive-style prefixes, processed-object
   expiry, and whether the low-latency and bulk lanes need to be separate.
