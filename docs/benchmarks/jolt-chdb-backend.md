# Embedded chDB backend baseline

This benchmark compares the actual embedded storage backend, not an HTTP client
or mock. The deterministic workload exports 20 batches of 50 spans, logs,
gauges, sums, and histograms, then runs the same grouped trace query 20 times.
Correctness is a hard gate: each run must reconcile exactly 1,000 rows in each
of the five ClickStack-compatible tables.

The first exploratory baseline was captured on 2026-09-06 with Jolt 0.8.3,
Chez Scheme 10.4.1, chDB through `jolt-chdb` commit
`a06b2af8df67e6b917b3242818ab933239e5b525`, Linux/WSL2 6.6.87.2, and an
Intel i7-1185G7 (4 cores/8 threads). These are single-run reference values, not
CI thresholds.

| Backend | Items/s | Span p50/p95 | Log p50/p95 | Metrics p50/p95 | Query p50/p95 | Scheme heap allocated | GC count |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| memory | 1,011 | 52.08/70.25 ms | 37.30/46.46 ms | 149.96/164.68 ms | 4.80/9.21 ms | 3,539,478,208 B | 211 |
| filesystem | 1,020 | 51.61/63.58 ms | 36.24/46.95 ms | 147.88/166.95 ms | 4.33/5.21 ms | 3,538,790,512 B | 211 |

Aggregate throughput differed by less than 1% in this single comparison. The
allocation total is exact for the Chez-managed Scheme heap, but excludes native
chDB/C++ allocations; calling-thread CPU also excludes chDB worker threads.
Consequently these counters cannot attribute the difference to persistence or
encoding. Repeated runs with process RSS and native profiling are required for
that comparison.

## Runtime availability

| Runtime | Real chDB backend | Comparable baseline |
| --- | --- | --- |
| Jolt | yes, native `jolt-chdb` driver | captured above |
| Babashka | not yet; needs a real `babashka.ffi` chDB backend | unavailable |
| JVM Clojure | not yet; needs a real chDB native backend | unavailable |

Mock or client-only numbers must not fill the unavailable cells. Once those
backends exist, run this same workload and reconciliation contract with each
runtime's native timing, allocation, and GC counters.

## Metric routing checkpoint (2026-10-05)

SDK metric ingestion now routes each constructed row into a per-kind vector,
without associating and then removing a temporary `:_type` on its persistent
wire map. Construction and typed projection retain input order; physical
submission remains gauge, sum, histogram. Ordinary ingestion still validates
and encodes all physical batches before any native insert. Durable publication
and partial-failure semantics are unchanged.

Focused qualification: 4 tests / 63 assertions, including exact legacy JSON
bytes, interleaved resource/scope/type routing, empty inputs and projector
order. The earlier candidate's aggregate native/socket checks also reported
all checks passed with terminal exit 0; the final small reduction-body cleanup
was checked by the focused suite and actual collector, not a fresh aggregate.

Component ABBA (24,576 constructed/routed rows per arm, composed Jolt
`976dd9d`, Chez 10.4.1): legacy 340.82/327.33ms and ~399.83MB allocated;
routed 326.93/291.31ms and ~373.13MB. Allocation decreases approximately 6.7%.
This is component evidence, not end-to-end tail qualification.

Actual local POSIX Durable collector, 10 x 5,000 items across five physical
tables, chDB encoder `45d090a`, data.json `993b906`: 15,470.55 rows/s and
10,461,622,224 allocated Scheme bytes for 250,000 rows. The prior sequential
same-shape screen was 15,424.13 rows/s and 10,629,602,928 bytes. Allocation is
~1.58% lower; throughput is essentially unchanged in these sequential screens,
not a causal speedup claim. Ten samples do not qualify p99. The throughput
target remains unmet; no S3/Rust or full-row recovery claim.
A separate fresh-process reader counted 50,000 service rows in each of the
five tables; writer and reader both reached terminal exit 0.

Exporter implementation SHA-256:
`7cb1f8e2c049ad5f662a18eb4c6cf1536581bd66498a9748450d9c390b7eb04e`.
Local receipt/driver basenames under `evidence/`:
`exporter-metric-routing-screen-20261005.{clj,edn}` and
`exporter-metric-routing-durable-20261005.edn` with its `.recovery.edn`.

## Guarded scalar attribute formatting checkpoint (2026-10-05)

OTel `19fc49d` adds `try-scalar-string`: unchanged bounded strings, Booleans
and admitted Int64 integers can be formatted without allocating the normal
canonicalization-result map. OTel owns the budget/range rules. Changed default
limits or canonicalizer roots, over-budget strings, out-of-range integers and
other shapes return nil, selecting the existing exporter normalization and
fallback. Values are not cached, and JSON/WAL/persistence semantics do not
change. This is ordinary maintained Clojure code, not a new Chez encoder.

OTel normalization tests pass 12 tests / 345 assertions. Exporter tests cover
exact scalar/structured/special/error text, changed limits and one invocation
of a live replacement canonicalizer. New tests are included in the persistent
migration child of the exporter aggregate; a fresh aggregate/review remains a
gate, not a claim from focused tests.

Component ABBA, 24,576 constructed/routed metric rows per arm: current
304.86/283.14ms and ~373.13MB allocated; helper 121.69/120.38ms and ~253.59MB.
Exact JSON rows matched before timing. Mean component elapsed time was ~59%
lower and allocation ~32% lower. These are component results, not a pipeline
speedup estimate.

Actual 10 x 5,000-item five-table local POSIX Durable collector with chDB
`45d090a`, data.json `993b906`, composed Jolt `976dd9d`, Chez 10.4.1:
17,640.77 rows/s, 9,366,906,096 allocated Scheme bytes, 250,000 physical rows.
The preceding same-shape routing screen measured 15,470.55 rows/s and
10,461,622,224 bytes: ~14% higher throughput and ~10.5% less allocation in
these sequential screens. No matched causal/p99/S3/Rust claim. A separate
fresh-process reader counted 50,000 service rows in each table, with writer
and reader terminal exit 0. Counts do not constitute full-row recovery proof.
The collector target remains unmet.

Exporter source SHA-256:
`ecd53a3cc637655f308d790e123c414251df75589a9661a536ad61186ab96d64`.
Local component receipt/driver:
`evidence/exporter-scalar-attribute-screen-20261005.{clj,edn}`.
Native receipt: `evidence/exporter-scalar-attribute-durable-20261005.edn`
and `.recovery.edn`.
