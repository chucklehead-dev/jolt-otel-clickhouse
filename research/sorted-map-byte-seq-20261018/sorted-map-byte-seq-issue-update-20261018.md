Implemented a conservative candidate in casselc/data.json:
[8d557cc...a18ae06](https://github.com/casselc/data.json/compare/8d557cc18eb9526ed29ae5613089c3793d5973fa...a18ae06c787e6e03709bd78f1b92911130a613f1).
No PR/merge or dependency pin changed yet.

Instead of adopting the research private-tree walker, it uses the map's live
sequence operations and publishes/synchronizes the real writer around callback
boundaries. Live custom JSONWriter/root replacement and custom options retain
their existing behavior. Same-output mechanism mutant catches branch bypass.
Expanded focused10/34, wider byte/segmented57/973 and native38/621 local gates
pass; exact a18ae06 native/portable/version-matrix CI is green.

Same-byte actual SDK-produced component: p50217->154ms/2k (~29%), allocation
68.9->47.0KB/row. Actual SDK queue/local Durable ABBA5k,16samples per arm:

| p50 milliseconds | baseline | candidate |
|---|---:|---:|
| encoding |713|495|
| native+confirmation |208|216|
| full insert |938|728|

Separate old-exporter/old-SDK/stock-JSON reader verified all175000physical rows,
typed values/status and multiplicity. Original reader failed because its
previously empty event-time arrays compared insertion integers to formatted
DateTime64 query strings; the corrected check extracts exact stored nanosecond
ticks, not rounded/text timestamps. Full-field equality is retained.

Not a p99/SLO, sustained throughput, Rust-relative or S3 claim. Producer-shaped
event coverage exposed a workload/reader-oracle gap; no Durable state transition
changed. Research scripts/raw counters/proof preserved;1.4GBgenerated store
removed after successful independent recovery. Final review reconciliation is
still pending: initial review's option claims omitted existing admission guard;
extra requested effect tests pass, but complete followup timed out and is not
credited as a review. Next: finish bounded review, qualify adoption, then
attribute remaining pdata-raw map reconstruction cost.
