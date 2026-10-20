# Explicit span codec experiment

This optional path encodes spans directly from Jolt collections into private
byte buffers. It runs on official Jolt **0.8.23**, without our compiler fork or
the optional native data.json writer. The normal exporter backend is unchanged.
Use it to evaluate lower-allocation span export; it is not a general data.json
replacement and does not support custom JSONWriter extensions or every OTel
value shape. Unsupported values fail the export rather than being omitted.

## Enable it

From this checkout, activate `:stock-span-codec`. Applications consuming the
exporter as a dependency must declare the independent
[codec Git subproject](../incubator/codec/README.md#use-as-an-independent-git-dependency)
themselves: dependency aliases do not propagate. Do not combine the published
and checkout-local codec aliases in a provenance test.

For an existing chDB 26.9.0 Durable writer connection:

```clojure
(otel.exporter.chdb/exporter
 {:connection conn :durable? true :signals #{:spans}
  :datetime64-wire :raw-ticks
  :json-backend :native-guarded-byte-batch
  :insert-format :json-compact-each-row
  :owned-statement-output? true
  :experimental-span-codec? true
  :experimental-span-payload-cache? true
  :experimental-span-flat-map-cache? true
  :experimental-span-nested-fragment-cache? true})
```

Supply installer-confirmed typed descriptors as usual if using typed storage.
To export all signals through this same owner, select
`:signals #{:spans :logs :metrics}`. Spans use the explicit codec; logs and
metrics retain the configured JSON writer, callback behavior and String SQL
path. The span backend setting does not install the separate legacy native
writer for those signals. All physical writes still await confirmation.
The three cache flags are optional, default-off optimizations. The last reuses
bounded complete scalar-map fragments and private scratch storage only within
one synchronous payload. Outputs remain independent and the scope closes on
return or failure. It is not a global telemetry cache or raw JSON insertion.

The pinned published codec includes these capabilities. Explicitly requesting
a missing capability or incompatible configuration rejects before acquiring
storage. The selected SDK, driver and ordinary-JSON dependencies still include
our forks: stock **Jolt** support does not mean an upstream-only ecosystem.

## What stays unchanged

Conversion/callback order, byte/depth limits and compact target/column-plan
checks remain enforced. Private preparation still precedes confirmed
publication synchronously; the plan is checked again before publication.
The driver owns ASCII snapshots, native insertion, WAL, lease/CAS checks and
persistence confirmation. Success is not queue admission or local execution.
There is no new concurrency, acknowledgement contract or Durable protocol.
No attribute values or persisted WAL contents are redacted by this option.

## Validation and limits

The published-component gate checks the exact Git resource paths before
running exporter, cache, traversal and codec tests:

```sh
jolt -M:stock-span-codec:stock-span-codec-test
```

Local workspace commands require the Chez 10.4.1 wrapper. The separate Linux
standalone library gate passes; it does not qualify complete exporter/Oscope
standalone builds or other platforms.

The latest paired SDK/local-Durable nested-cache comparison used published
SDK/driver pins, official Jolt 0.8.23, libchdb 26.9.0 and rich 5k-row callbacks.
Observed pooled confirmed throughput increased **12,158 -> 14,769 rows/s**,
with 9.1% control drift; estimated allocation fell about 1KiB/row. Both cache
iterations independently recovered 700k full-field rows each. The complete
[results and scripts](../research/nested-fragments-20261019/README.md) retain
earlier inconclusive results, all p50/p90/p95/p99 samples and cleanup receipts.

This is not population-tail, S3, real-application/Langfuse, Rust-relative or
throughput-target qualification. The ~25k p50 / ~20k p99 objective remains
unmet. Oscope defaults/configuration are unchanged; selecting this published library alone
does not enable the codec or its caches in an application.
