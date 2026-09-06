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

| Backend | Items/s | Span p50/p95 | Log p50/p95 | Metrics p50/p95 | Query p50/p95 | Ingest allocation proxy | GC count |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| memory | 1,001 | 53.60/59.19 ms | 37.71/45.62 ms | 153.82/163.31 ms | 4.46/5.37 ms | 3,537,460,576 B | 210 |
| filesystem | 977 | 54.36/76.12 ms | 39.08/43.45 ms | 153.71/165.22 ms | 4.55/7.33 ms | 3,537,460,576 B | 210 |

The filesystem run was about 2.5% slower in aggregate. The essentially
identical allocation proxy and collection count indicate that encoding and
export object churn dominate this small workload more than persistence does.
That is a hypothesis for the next profile, not a causal conclusion from two
single runs.

## Runtime availability

| Runtime | Real chDB backend | Comparable baseline |
| --- | --- | --- |
| Jolt | yes, native `jolt-chdb` driver | captured above |
| Babashka | not yet; needs the proposed `babashka.ffi` backend | unavailable |
| JVM Clojure | not yet; needs a real chDB native backend | unavailable |

Mock or client-only numbers must not fill the unavailable cells. Once those
backends exist, run this same workload and reconciliation contract with each
runtime's native timing, allocation, and GC counters.
