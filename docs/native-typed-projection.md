# Bounded typed projection

The experimental native-byte compact span path can collect just the values
needed by an approved attribute manifest. It still visits and normalizes every
input key in the same order. Complete captured attribute dictionaries are not
filtered or discarded: this optimization only changes temporary typed-field
projection work.

This path requires Durable export, typed span descriptors,
`:insert-format :json-compact-each-row` and
`:json-backend :native-guarded-byte-batch`. The ordinary two-argument portable
projector is unchanged. It does not change JSON encoding, SQL/WAL contents,
timestamp choices or persistence acknowledgments.

On the qualified Jolt runtime, built-in maps with at most 128 entries use
invocation-owned storage. Repeated normalized keys remain repeated values, so
typed collision/status rules stay intact. More than eight output groups promote
to a normal builder; larger input maps and unsupported map layouts use the
portable collection fallback. These limits are optimization bounds, not capture
limits. Calls never share mutable scratch or overwrite input nodes.

The focused gate is `jolt -M:native-projection-test`. It checks callback order,
live replacement and reentry, collision buckets, fallback, input ownership,
single-seal positional output and deliberately wrong traversal/field-order
controls. The gate runs in CI with the authenticated qualified Jolt artifact.

The original classifier can deliver its value and status directly to the native
builder, avoiding a temporary pair for each typed field. Both forms are generated
from one classification rule. Replacing the projector still uses its ordinary
two-argument API, including list/short/nil results. The result continuation never
captures the output buffer, so retaining it cannot mutate a completed row.

## Component benchmark

With the qualified native-projection runtime, compare pair construction and
direct results through the same indexed projector:

```sh
jolt -Srepro -A:native-projection-test \
  -m otel.exporter.chdb-native-projection-benchmark \
  target/projection.edn 1000 24
```

Arguments are the output file, rows per sample and number of A/B/B/A cycles.
The default runs 48 samples per mode, with three typed fields among 16 captured
attributes. It checks exact portable vectors and JSON before and after timing,
reports p50/p90/p95/p99 trial latency and Jolt heap bytes allocated per row, and saves bounded
scalar samples. Var handles are captured once outside the timed loop.

This opens no database or network connection and creates only the small report.
Results describe a projection component, not ingestion throughput or a latency
SLA. Full-pipeline results depend on typed-field count, encoding, native execution
and persistence; retain separate Durable recovery and throughput checks.
