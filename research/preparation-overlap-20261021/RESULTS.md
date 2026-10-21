# First order-controlled local SDK screen

Measured source: `3cedfb912a03a575cff65067f8d81faa7cb97b93`.
One default release binary, SHA256
`e022bf2694dddcbf90898aee0e81ed11b231b6406f74df28e2fca5e558d209a7`.
Stock Jolt 0.8.23/Chez10.4.1/chDB26.9.0; exact Git providers and workload
boundaries are in [README](README.md). No S3, live receiver or paced app claim.

Each run: 3 warmups + 32 measured batches of 5,000 rich typed spans. Both
arms use the real SDK worker and identical saturated raw-queue/configuration.
All four writers and independent older-stack recovery phases completed
successfully. **700,000 stored rows** were checked, with all physical column
types, canonical decoded fields, exact timestamp/event ticks and 35 copies per
fixture row matching the ordinary JSONEachRow control. All SDK failures/drops
were zero. Compact [receipts and raw samples](results/) contain no row dumps.

| Order / arm | Confirmed window rows/s | Cadence p50 / p90 / p95 / p99 ms |
|---|---:|---|
| A1 synchronous | 12,496.95 | 356.67 / 511.62 / 623.19 / 729.77 |
| B1 overlap | 23,237.74 | 204.45 / 264.98 / 295.95 / 350.83 |
| B2 overlap | 21,611.33 | 216.78 / 276.61 / 390.52 / 471.49 |
| A2 synchronous | 13,383.62 | 356.09 / 437.59 / 454.31 / 583.15 |

Pooled finite-window throughput: **12,925.09 → 22,395.04 rows/s (+73.3%)**.
Pooled overlap inverse cadence p50/p90/p95/p99:
**23,376 / 18,357 / 16,895 / 10,605 rows/s**. The approximately25k/20k p50/p99
targets remain unmet. With64 measurements per arm, nearest-rank p99 is still
the sample maximum, not a population-tail guarantee or a stable effect estimate.

Do not substitute cadence for latency: pooled callback processing p50/p99
was356.49/729.59ms synchronously and427.15/861.55ms with overlap. Queue-inclusive
latency also includes the deliberately preloaded finite backlog. These are not
interchangeable with the older direct-export benchmark or normal app latency.

## Remaining opportunity

In B1, the worst350.83ms confirmation interval includes the FOLLOWING batch's
341.86ms preparation, with8.66ms residual prior-confirmation wait. Other large
intervals similarly align with preparation. This locates the next boundary to
profile: projection/encoding/WAL preparation, including GC and scheduling;
it does not yet attribute the tail solely to JSON CPU or to GC.

Coarse estimated allocation stayed near14.4–14.8kB/row. Do not claim an
allocation improvement from this overlap comparison: phase boundaries and
live buffered state differ. One real logical WAL record contained8,648,398
SQL bytes and10,508,410 WAL bytes for5,000 rows, or3,831.36 SQL+WAL bytes/row.
That is useful output accounting, not a heap bound or proof the remaining
allocation can all be eliminated. Next: per-preparation allocation/GC evidence,
then native qualification of existing allocation-reduction candidates.

For this uncompressed rich WAL, a10Mbps upload ceiling alone permits at most
about595 rows/s before overhead.25k rows/s would require about420Mbps for WAL
body bytes alone. Use a suitably connected/cloud runner for S3 throughput
qualification; multipart upload cannot remove this link-rate limit.

## Review and follow-through

Claude's source review checked measurement boundaries, geometry, loss
detection and independent recovery. It flagged the observer's unconditional
index removal on a nonliteral retirement result. The actual SDK already
requires literal true and quarantines failed retirement; successful flush/
shutdown and physical recovery therefore remain required gates for these runs.
Codex verified that SDK enforcement and tightened the diagnostic bookkeeping.

The identical observer test failed six assertions before that guard; afterward
the observer plus independent **unwrapped** SDK controls passed2 tests/20
assertions. Bypassing the observer in the SDK controls keeps its guard from
masking a future SDK regression. Delivery confirmation and resource proof stay
separate. That post-screen guard is not a newly measured throughput result;
the source/binary above identify the exact measured version.

No production default, Oscope dependency selection or final overlap merge is
enabled by these results. The broader issue and throughput goals remain open.
