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
