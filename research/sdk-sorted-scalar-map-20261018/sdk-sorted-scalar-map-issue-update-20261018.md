Implemented the next source-owned capability on top of the sorted JSON gain:

- SDK [697f58b...7878a2e](https://github.com/casselc/otel/compare/697f58bd5b4925f72339694bedbace97bdc869bb...7878a2eb4340519d6843d13490a5a7c99ebade8a)
  adds optional bounded stock sorted scalar -> plain string map normalization.
- Exporter [20999f9...a1bedfc](https://github.com/chucklehead-dev/jolt-otel-clickhouse/compare/20999f9c37d01ce1629f9ae2033bd12483030fc7...a1bedfcc50d84dd59597fe12dec144231b282920)
  consumes it with separately guarded missing/unsupported/changed-policy fallback.
- [SDK CI](https://github.com/casselc/otel/actions/runs/39141384747) and
  [exporter CI](https://github.com/chucklehead-dev/jolt-otel-clickhouse/actions/runs/39141486504)
  pass at those exact heads. SDK helper alone is qualified on stock Jolt0.8.23;
  no full native JSON/Durable stock-compiler claim follows. Independent source
  reviews completed; no PR submitted or merge yet.

Actual same-queue/connection local Durable ABBA, 5000 rows/batch, 16 per arm:
full insert p50 615->565ms (~8%); projection/encoding 424->373ms (~12%);
full managed allocation 55.4->38.7KB/row. Independent old-stack reader verifies
all175000 physical rows, typed values/status, multiplicity and exact event
timestamp ticks. The store was removed after receipt preservation/validation.
This is NOT p99/SLO, hosted-S3 throughput, Rust-relative or real-app qualification.

Further source simplification (removing per-entry observer thunks while keeping
all publication boundaries) passed38/621 but yielded no useful gain: same-byte
2k-record component p50127.95->128.07ms, allocation30.306->30.304KB/row. Not adopted.

New exact logical counters separate two often-conflated costs: a 17-entry sorted
map requires103 sequence observations/105 publication-flush calls, with20
nonempty buffer flushes. Nested JSON emits219 bytes incrementally, with NO
batch-prefix rewrite. The same map directly in a byte batch publishes11171
cumulative prefix characters for220 output bytes;128 entries publish715551 for
1830 bytes. That direct-batch amplification is real but is NOT established as
the cause of this fixture's remaining gap (its sorted attributes are nested).
Counters preserve all callbacks and output; logical character counts are not
physical-copy/CPU attribution. Next targets: cheaper ordinary traversal/nested
publication, and separately content-coherent batch publication without skipping
custom-operation visibility or edits. No Durable state transition changed.
