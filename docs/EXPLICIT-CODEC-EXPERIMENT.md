# Explicit span codec experiment

The optional codec moves nested event/link JSON and outer span encoding into a
small, separate library. It is a research option; the normal backend is unchanged.
It does not yet support every OTel value shape or replace all fork dependencies.

Activate `:experimental-span-codec` alongside `:native-json-byte-batch`, then
add this option to an otherwise configured exporter:

```clojure
{:experimental-span-codec? true
 :signals #{:spans}
 :durable? true
 :json-backend :native-guarded-byte-batch
 :insert-format :json-compact-each-row
 :owned-statement-output? true}
```

Supply the actual Durable connection and any installer-confirmed typed
descriptors as usual. Invalid combinations or unavailable support fail before
opening storage. The option is deliberately limited to this tested span path;
it is not a way to change metric/log encoding.

The codec uses its own supported-value contract instead of third-party
JSONWriter extensions. Unsupported values fail the export; no attributes are
redacted or silently omitted. Compact plan checks run before and after
encoding. The existing driver owns ASCII snapshots, native insertion, WAL,
lease/CAS checks and persistence confirmation. No new acknowledgement contract
or Durable protocol was introduced.

## Current evidence

The actual SDK/exporter/local-Durable comparison used 5,000-row rich typed
batches, one serial exporter worker, a 20,000-item bounded queue and libchdb
26.9.0. Four independent processes ran with the flag off/on/on/off. Each was
followed by an older-stack reader that checked all 175,000 physical rows,
including typed statuses and nested event fields. All 700,000 rows matched.
Temporary stores were removed only after validation.

- Control windows: 9.69k and 11.48k confirmed rows/s.
- Codec windows: 11.83k and 12.68k confirmed rows/s.
- Estimated allocation: 24.4 versus 20.7 KB/row, about 15% lower.

The 18.5% drift between controls prevents treating the pooled rate difference
as a qualified speedup. Callback p50/p90/p95/p99 are preserved in the research
results, but this short run is not a production-tail or end-to-end span-latency
qualification. This does **not** meet the 25k/20k throughput targets. No S3,
failure-injection or standalone qualification is claimed here.

The remaining work is stock-runtime qualification of the full consumer and
reducing intermediate projection/attribute maps and row vectors, alongside
the existing native/WAL/copy costs. The library is described in
[the incubator README](../incubator/codec/README.md).
