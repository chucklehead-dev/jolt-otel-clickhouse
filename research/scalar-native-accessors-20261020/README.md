# Reuse validated sorted-node access

The scalar shape-plan walker already checks each node is a persistent vector
of length five before accessing literal indexes 1..4. It nevertheless called
`pvec-nth!` four times, repeating generic nil/coercion/index validation.

Stock Jolt0.8.23 commit a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5 has
`sc-nd-key`, `sc-nd-val`, `sc-nd-left`, `sc-nd-right` in
`host/chez/host-table.ss:145-148`. Each calls `pvec-nth-in-range` at the same
literal index. The original `pvec-nth!` in `host/chez/collections.ss:249`
validates its index then calls that SAME in-range helper. Therefore, after the
existing type/size guard, these reads preserve vector/view behavior while
avoiding redundant checks. No direct tail-layout assumptions were added.
The independent traversal kernel already uses these stock node accessors.

Source and Linux standalone library: 39 tests / 468 assertions passed. New
mechanism test instruments generic indexing in an isolated kernel: original
control performs exactly four generic reads per node; candidate zero, for
0/1/17/128 keys, with identical normalized outputs. Learned lookup negatives
cover malformed/short/invalid-child/key/value nodes, and a sliced five-element
vector remains valid. Live policy, count, traversal/cycle, schema and value
guards are unchanged. Changelog and standalone test-count assertion updated.

Eight interleaved samples/arm, exact complete output byte equality:

| Rich untyped projection/encode arm | p50 ms | p90 ms | p95 ms | p99 ms | Estimated bytes/row |
|---|---:|---:|---:|---:|---:|
| Original generic indexing | 152.92 | 207.45 | 207.45 | 207.45 | 9578.0 |
| Stock native accessors | 145.81 | 171.09 | 171.09 | 171.09 | 9578.0 |
| Frozen normalization cost ceiling | 126.80 | 140.88 | 140.88 | 140.88 | 6646.6 |

Candidate component median is 4.65% lower; allocation is unchanged. These short
component samples exclude typed projection, JDBC, WAL, publication and queues.
Frozen is NOT a production cache: all normalized outputs are retained outside
production bounds and admission/conversion is excluded from timing. No tail
qualification or claimed causal end-to-end throughput improvement follows.

`baseline-plan.ss` is the original merged plan. `screen.clj` compiles both
versions lexically in the SAME stock process with the same SDK14f58e5 policy
anchors; it does not patch the compiler or data.json. Full scalar sample report:
`/home/chuck/ai-src/evidence/scalar-native-accessors-20261020.edn`.

Actual local-Durable candidate arm: one serial SDK/exporter/writer, 5,000 rows
per callback, three warmups and 32 measured callbacks. Independent older-stack
reader verified all 175,000 rows, fields/types/status/multiplicity with zero
failed/dropped spans. Its 11,939.7 confirmed rows/s is a correctness run, NOT an
AB performance comparison, S3, live-app or throughput-target qualification.
Reports: `/home/chuck/ai-src/evidence/scalar-native-accessors-durable-20261020`.
Owned synthetic store retired after validation and unchanged-source checks;
owned standalone binary/build directory retired after its 39/468 successful
execution, preserving small logs/receipts.

No API, default, pin, WAL, confirmation or concurrency changes are part of the
kernel slice. The additive representation bridge remains separate future work.
