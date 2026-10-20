# Matched stock-runtime Durable phase diagnosis

Corrected run: `/home/chuck/ai-src/evidence/matched-durable-phases-v2-20261020`.
Official stock Jolt 0.8.23, chDB 26.9.0, published codec/SDK pins, inferred
scalar shape plans enabled; deterministic rich SDK-shaped records with equal
but distinct attribute roots. One writer, 5,000 rows/callback, three warmups and
32 measured callbacks. This is diagnostic instrumentation, not an uninstrumented
version comparison, live application qualification, or S3 benchmark.

Measured phase p50 / p90 / p95 / p99 milliseconds:

| Phase | p50 | p90 | p95 | p99 |
|---|---:|---:|---:|---:|
| Project and encode | 179.67 | 252.01 | 351.41 | 377.42 |
| Snapshot validation/copy | 15.64 | 23.84 | 25.76 | 30.92 |
| WAL precompute | 29.67 | 40.62 | 47.86 | 58.22 |
| Execute and confirm | 175.42 | 241.84 | 256.76 | 319.66 |
| Compact total | 396.76 | 562.72 | 594.01 | 712.18 |
| Placeholder scan (nested) | 20.71 | 28.85 | 32.84 | 37.39 |
| Native classify (nested) | 2.34 | 4.94 | 5.85 | 10.39 |
| Stream finish (nested) | 66.13 | 83.12 | 101.18 | 132.38 |

Each phase occurred once per callback, including placeholder scanning. Parent
and nested durations, and independent quantiles, must not be added. p99 is the
maximum of only 32 samples, not a qualified population tail. Native worker CPU
is not established by calling-thread CPU counters. Projection/encoding accounts
for 46.3% of total measured parent wall time, making it the next optimization
target; duplicate-placeholder-scan memoization has no opportunity here.

An independent older-stack reader recovered all 175,000 rows and validated
physical fields, types, statuses and multiplicity. The exact owned synthetic
store was retired only after writer/reader completion, validator success,
copied/cmp reports and unchanged-source checks. Small reports remain in the
evidence directory.

The initial run recorded malformed flat events because the observer's map
brace closed too early. Its failing unit control and source are preserved in
855d5ab. This correction passes two tests / eleven assertions. The initial
writer's 175,000 rows were also independently recovered before its own store
was retired; its partial diagnostic data is not used as a complete profile.
