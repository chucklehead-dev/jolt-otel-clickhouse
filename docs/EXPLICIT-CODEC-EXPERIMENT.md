# Explicit span codec experiment

The optional codec moves nested event/link JSON and outer span encoding into a
small, separate library. It is a research option; the normal backend is unchanged.
It does not yet support every OTel value shape or replace all fork dependencies.

Activate `:experimental-span-codec`, then
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

The explicit path does not load the optional native data.json writer. Its map
walker is shared lexically with attribute projection rather than installed as
a runtime global. Full stock-runtime use additionally needs the driver's
stock-compatible private-buffer adoption change; the currently pinned driver
does not yet include it. Other ecosystem dependency forks remain selected.

The codec uses its own supported-value contract instead of third-party
JSONWriter extensions. Unsupported values fail the export; no attributes are
redacted or silently omitted. Compact plan checks run before and after
encoding. The existing driver owns ASCII snapshots, native insertion, WAL,
lease/CAS checks and persistence confirmation. No new acknowledgement contract
or Durable protocol was introduced.

## Current evidence

An additional stock Jolt 0.8.23 semantic gate used the library-owned walker,
stock-compatible driver buffer adoption and ordinary metadata JSON reader,
without the optional native JSON alias. The actual SDK/Durable writer completed
175,000 rows; a separate older-stack reader matched every physical row and
multiplicity, including typed values/statuses and nested event fields. The
validator passed. This gate used local-root candidate dependencies, not the
published pins, and does not qualify AOT, S3 or Oscope startup. Its single
11.62k confirmed-rows/s window is not a comparative speedup result.

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

The remaining work is published-pin/CI qualification of the stock consumer and
reducing intermediate projection/attribute maps and row vectors, alongside
the existing native/WAL/copy costs. The library is described in
[the incubator README](../incubator/codec/README.md).
