Matched rich-fixture follow-up completed. The current **RowBinary-over-Base64-SQL
prototype is slower than the optimized JSON stream**; the older small-fixture
four-sample result does not predict this workload.

Four fresh processes in JSON/RowBinary/RowBinary/JSON order, fixed runtime
`631984d6`, exporter `4d48871`, SDK `7878a2e`, native JSON `96f3e47`, driver
`3d766fa`, native package 26.9.0. Each has three warmups and 32 measured 5k
batches. Direct serial callbacks, not SDK-queue or OTLP integration.

| Measurement | JSON stream | RowBinary SQL prototype |
| --- | ---: | ---: |
| Callback rows/s | 8,952 | 4,737 |
| Callback p50/p90/p95/p99 ms | 537/685/715/746 | 1,018/1,198/1,232/1,330 |
| Estimated allocated bytes/row | 30,685 | 83,674 |

RowBinary has costs in conversion, framing and native work: projection/encoding
p50 526 ms, separate Base64/SQL framing 152 ms, native+confirmation 332 ms.
JSON projection/encoding **including its framing** is 342 ms, native+confirmation
179 ms. Phase medians are not additive; totals above are actual measured calls.

The raw binary is 6,713,890 bytes, becoming 8,953,250-byte SQL. Replay therefore
uses an explicit 16 MiB query-budget open seam in both writer and reader. The
existing guard correctly rejected combining that custom open seam with owned
streaming; it was not relaxed. JSON retains its default streaming path, while
RowBinary uses its ordinary owned query path in a separate process.

All four independent older-stack stock-reader recoveries passed **175,000 rows
each**, including physical types/statuses, full values, nanosecond event ticks,
trace siblings and multiplicity. Descriptive results only; not service-tail or
S3 qualification. Source-only Claude review and Codex adjudication are retained.

Decision: do not promote this prototype. Keep priority on the winning JSON
path's projection/encoding allocation and native/WAL costs. If revisiting the
binary experiment, first remove per-string UTF-8 temporaries and full Unicode
Base64/SQL intermediates, then remeasure. This is an implementation result,
not proof that RowBinary cannot win or that the throughput target is impossible.
