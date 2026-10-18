## Finding

Actual SDK-ended spans contain sorted resource/scope/event attributes. The
guarded byte JSON writer directly handles builtin HAMTs, but TreeMaps invoke
the ordinary map JSONWriter. This is a significant encoding bottleneck on
event-rich inputs, independent of Durable WAL admission/confirmation.

## Same-byte representation discriminator

2,000 real SDK spans, 17 span/resource/event attributes, one event/span;
creation and alternate representation preparation excluded from timing.
Only event attribute maps were changed from TreeMap to explicit array-map
preserving all values and exact entry order. Complete records compare equal;
full emitted UTF-8 is identical (about 3.39 MB per batch). 12 rotating pairs:

| phase | sorted p50 ms | ordered-array p50 ms | sorted / array managed B/row |
|---|---:|---:|---:|
| public nested JSON |113.308|95.976|46,263 / 38,006|
| guarded nested JSON |100.846|36.637|45,111 / 26,246|
| full collector row + encoding |227.731|103.732|68,858 / 23,382|

This is a discriminator, **not a proposal to change SDK record types**.

## Direct sorted-tree traversal diagnostic

Separate 16 rotating pairs, unchanged original SDK records. Source-only
byte-writer overlay adds sorted-tree traversal to the existing stock-map
dispatch branch. Existing per-value method resolution and scalar emitters are
retained. Both arms emit identical full UTF-8 before and after measurement:

| full row + encoding | p50 ms | managed B/row |
|---|---:|---:|
| baseline |213.829|68,860|
| direct sorted tree |139.963|39,625|

Roughly 35% lower p50 time and 42% lower allocation on this workload. No
throughput, persistence, application, Rust-relative or p99/SLO qualification.
All reported p50/p90/p95/p99 samples are retained, but 12/16 pairs are too few
for tail claims. These different runs must not be combined into paired data.

## Reproduction custody

Exporter5a7617a, SDK697f58b, JSON8d557cc, Jolt1ae7e0d artifact
06a2ccb858c072bfcd74ce94d96eb95f008d75e0fb1f46117728602572189811,
mandatory Chez10.4.1. Both bounded jobs exited0. No DB/native engine/network
or persistent store was involved. Scripts, raw counter samples, summaries and
logs are under research/nested-sorted-json-20261018 in the local evidence
checkpoint branch (publication pending).

## Adoption gates and remaining work

- The diagnostic accepts actual SDK-created builtin sorted maps only; it does
  NOT establish safety for arbitrary/forged/custom-op tagged tables. Need an
  ownership/ABI guard or a runtime-owned ordered traversal capability.
- Preserve live JSONWriter selection, callback ordering, actual writer
  visibility, custom methods, reentrancy and exceptional cleanup. Test these
  before adopting a production change.
- Full row production also calls pdata-raw, rebuilding event maps via into
  (empty map). This may explain part of the residual difference, but is not
  separately measured yet. Do not bypass it by assuming raw SDK ownership.
- Model gap: prior mostly hash-map/empty-event fixtures did not represent
  actual SDK event attribute shapes. Add producer-shaped workload coverage;
  this does not justify changing Durable state-machine logic or rerunning
  unchanged exhaustive checks.
- Qualify any production implementation cumulatively through local Durable
  with recovery, then hosted S3. Local upload is capped at 10 Mbps.
