# Columnar segment export baseline

This baseline answers one question: if a Durable V1 store published columnar
segments to object storage alongside its statement WAL, what would that cost,
and what would it buy?

It is evidence, not a CI threshold. Two of the numbers here corrected earlier
estimates by an order of magnitude in the wrong direction, which is the reason
the measurements and their limitations are recorded rather than summarised.

## Provenance

Captured 2026-09-11 on a single 4-core host (15 GB RAM, Linux 6.18.44) running
both halves of the benchmark, so edge and centre figures are comparable to each
other but not to the earlier `jolt-chdb-backend.md` baseline, which used
different hardware.

| Component | Version |
| --- | --- |
| Jolt | 0.8.6 |
| libchdb | chdb-io release candidate used by `jolt-chdb` `:setup-native` |
| ClickHouse (centre) | 26.9.1.1181, official static build |
| Edge harness | `bench/otel/exporter/segment_export_benchmark.clj` |
| Centre harness | `bench/segment-export/clickhouse-ingest.sh` |

The generated span shape is identical in both harnesses: 17 columns matching
migration v1 `otel_traces`, twelve spans per trace, eight services, twenty
routes, eight resource attributes, ten span attributes, 1,138 bytes per row as
JSONEachRow.

**Compression here is optimistic.** A generator's attribute values repeat far
more than production telemetry's. Segment sizes below are a floor; budget
1.5–2x for real traffic.

## The Durable WAL path is bounded by bytes, not rows

The same engine, the same protocol, two payload widths:

| payload | B/row | rows/s | MB/s |
| --- | ---: | ---: | ---: |
| narrow, log-shaped | 272 | 22,741 | **5.89** |
| wide, real span | 1,138 | 5,436 | **5.90** |

A 4.2x change in row width produces a 4.2x change in row rate and no change in
byte throughput. Any row-per-second target for this path is therefore only
meaningful next to a row width, and the earlier working target of
"20,000–25,000 rows/s" was reachable only on narrow synthetic rows.

## What each artifact costs on the wire

Measured over 50,000 wide rows through a real Durable writer, reading the bytes
actually published to the object store:

| artifact | B/row | ratio to payload | ingestible by ClickHouse |
| --- | ---: | ---: | --- |
| statement WAL | **1,299.5** | 1.14x | only by parsing and executing its SQL |
| checkpoint `.tar.gz` | 44.8 | 0.04x | no — chDB backup format |
| Parquet zstd segment | **38.6–53.8** | 0.04x | yes, natively |

The WAL is 1.14x its own JSONEachRow payload because each statement is escaped
into a `{"sql": ...}` envelope. The checkpoint is already as compact as
Parquet; it is not a candidate transport only because nothing outside chDB can
read it.

## Export format, whole table, 1,000,000 rows

| format | ms | rows/s | B/row |
| --- | ---: | ---: | ---: |
| Parquet (default) | 3,295 | 303,457 | 52.7 |
| **Parquet zstd** | 1,801 | **555,266** | **53.8** |
| Parquet lz4 | 1,808 | 553,152 | 97.0 |
| Native | 2,475 | 403,960 | 799.9 |
| ArrowStream | 2,132 | 469,113 | 286.3 |
| JSONEachRow | 6,343 | 157,656 | 1,150.5 |

For reference the same rows occupy 96.9 B/row as MergeTree parts on disk and
1,134.9 B/row uncompressed.

## Segment size decides whether export is affordable

Bounded slices of a 2,000,000-row table, Parquet zstd. No `ORDER BY`:
`otel_traces` orders by `(ServiceName, SpanName, Timestamp)`, so a timestamp
range is not a primary-key prefix and sorting would measure the sort.

| rows in segment | rows/s | B/row | cost against 5,436 rows/s ingest |
| ---: | ---: | ---: | ---: |
| 1,440 | 22,402 | 57.1 | 24% |
| 14,400 | 139,860 | 50.5 | 3.9% |
| 86,400 | 264,917 | 40.6 | 2.1% |
| 86,400, partition-aligned | **394,735** | **38.6** | 1.4% |
| 2,000,000 | 420,670 | 44.0 | 1.3% |

Small segments lose twice: throughput collapses and compression worsens,
because Parquet dictionary and run-length encodings need volume. The knee is
near 50,000 rows. Below roughly 10,000 rows per segment the export stops being
a rounding error against ingest.

Partition alignment is worth 1.5x. `otel_traces` currently declares no
`PARTITION BY`, so exporting a time range scans the table; adding
`PARTITION BY toStartOfHour(Timestamp)` turns each segment into a whole-part
read. That is a physical schema change with ClickStack-compatibility
consequences — see `clickstack-compatibility.md` — not a free optimisation.

## Centre-side ingest, real ClickHouse

An embedded chDB is not a valid stand-in for the centre: it is single-threaded
here and its Parquet writer emits much larger row groups than ClickHouse's.
These numbers come from a real server, 60 segments of 50,000 rows.

| pattern | rows/s | peak active parts |
| --- | ---: | ---: |
| 60 sequential INSERTs | 233,522 | — |
| 6 batched INSERTs of 10 segments | 284,124 | 12 |
| 1 glob INSERT over all 60 | 345,308 | 13 |
| **60 INSERTs, 8-way parallel** | **471,943** | 28 |

`DelayedInserts` and `RejectedInserts` were both zero, and background merges
collapsed 60 parts to 10 unaided. **Concurrency beats batching**, and batching
was worse than doing nothing clever.

### Format at the centre

Same server, same rows, `max_threads=4`:

| format | rows/s |
| --- | ---: |
| Parquet, 1,000,000 rows, warm | 763,255 |
| Parquet, 60 files x 50,000 rows | 406,531–446,414 |
| JSONEachRow, 1,000,000 rows | ~500,000 (stable across runs) |

**Parquet is roughly 1.5x JSONEachRow at the centre, not orders of
magnitude.** ClickHouse's JSONEachRow parser is fast. The case for columnar
segments is bandwidth, storage, and not executing SQL authored by N
independently-versioned edge writers — not centre-side CPU.

### chDB under-splits Parquet row groups

A 1,000,000-row file written by chDB contains **3 row groups**. ClickHouse
parallelises across row groups, so such a file ingests as a 3-way parallel job;
its first cold run measured 229,934 rows/s against 763,255 warm. ClickHouse
writing 50,000-row segments produces one row group per file, so 60 files are 60
independent parallel units.

An edge writer should set `output_format_parquet_row_group_size` explicitly
rather than inherit chDB's default. At the ~50,000-row segment size the knee
analysis recommends this does not bite, but it would at larger segments.

## Capacity, derived

At 2,000 spans/s per node and 40 B/row, using the measured figures above.
Fleet bandwidth is arithmetic from the measured per-row sizes, not itself
measured.

| fleet | segments | per day | statement WAL instead |
| --- | ---: | ---: | ---: |
| 100 nodes | 64 Mbit/s | 0.7 TB | 2.1 Gbit/s |
| 1,000 nodes | 640 Mbit/s | 6.9 TB | 20.8 Gbit/s / 225 TB per day |
| 10,000 nodes | 6.4 Gbit/s | 69 TB | — |

Centre capacity for 1,000 nodes (2,000,000 spans/s) is roughly 18 cores of
ClickHouse ingest, and is not materially different between the two formats.
Object-store request rate is not a constraint: 50,000-row segments at 2,000
spans/s seal every 25 s, so 1,000 nodes produce about 40 PUT/s against a 3,500
PUT/s per-prefix limit.

## Corrections to earlier estimates

Recorded because both were wrong in ways that would have shaped the design.

**"Parquet ingest beats SQL replay ~67x at the centre."** Wrong. That compared
chDB ingesting Parquet against the *edge's* full Durable write path, which
includes WAL journaling and the V1 protocol the centre never pays. Measured
apples-to-apples on one ClickHouse server the ratio is ~1.5x. The bandwidth
argument (21–32x) is the one that survives.

**"Batch many segments per INSERT to avoid part pressure."** Wrong at this
scale. Measured, 8-way parallel per-segment INSERTs beat a single batched glob
INSERT by 1.37x with no delayed or rejected inserts. One segment per INSERT
also preserves the 1:1 message-to-insert mapping that makes deduplication and
retry tractable.

## Not measured

- `ReplicatedMergeTree`. Every centre-side number uses a non-replicated table.
  Replication adds a Keeper round trip per insert, and
  `insert_deduplication_token` — the deduplication mechanism the design
  depends on — requires it. This is the most important open measurement.
- Real object storage. Both harnesses use local files; no S3 latency, no
  multipart upload, no `s3Cluster` fan-out.
- Materialized views firing on insert at the centre.
- Production attribute cardinality, as noted above.

## Running it

```sh
jolt -M:segment-benchmark                    # edge: 1,000,000 rows
jolt -M:segment-benchmark 100000 10000 512   # quick pass

curl https://clickhouse.com/ | sh            # centre needs a clickhouse binary
bench/segment-export/clickhouse-ingest.sh 60 50000
```

Neither harness touches the repository; both write under `/tmp`.
