# Rejected clean-word WAL experiment

This local branch is an experiment based on selected driver
3d766fa295b0e98c30f275d0a334db92e6dd998c, not a merge candidate.

The four-byte checked copy preserves tested bytes but loses on the rich 8,648,398
byte statement: 24 observations per arm, baseline/candidate p50 64.979/82.835ms,
p90 82.387/109.398ms, p95 86.457/113.889ms, p99 105.587/119.023ms. Allocation
is unchanged at approximately 2,132 estimated bytes/source row. All emitted WAL
outputs have 10,508,410 bytes and SHA256
ce8c4fb6286c96c16a7984bfa85099dd2fbf8b4d5a7dd155278ae39f34a779e5.
This is a kernel screen, not a Durable execution/recovery/throughput result.

Focused tests passed 3 tests / 4,237 assertions on hosted runtime7098 / pinned
Chez10.4.1. They compare all ASCII bytes at all four lanes/alignments against
portable JSONL, compare chunk boundaries with the scalar encoder, count real
copy-path entries, and detect four disabled escape guards.

Claude read-only review completed, finding the stale formal source pin and no
byte-predicate defect by inspection. Main confirmed the checksum check fails
for owned_ascii_wal.ss; the prior bounded fact mapping describes only scalar
emission and does not qualify this new transition. Do not update the digest
alone or claim a passing model. No SMT/Quint model was changed or run. The
candidate is not selected, so formal extension and wider/native gates are not
warranted. No PR, consumer repin or default change is authorized by these results.

Small source, scalar results, test and review receipts are retained in the
exporter research branch under research/repeated-conversion-and-wal-20261019.
No synthetic Durable stores were created by these component screens.
