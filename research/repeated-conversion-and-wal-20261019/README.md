# Larger encoding opportunities and a rejected WAL shortcut

Previous goal turn: read-only custody check, no new product progress. This turn
published checkpoint4dc0ab0, refreshed exporter issue138, confirmed Oscope169's
merge tree equals its tested tree, and completed the following new experiments.
The performance/integration goal remains active and unmet.

Selected runtime7098/exporter2c/SDK7878/nativeJSON217/driver3d, pinned Chez10.4.1.
The same rich5000-row input produces8,648,398 bytes with SHA256
862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39.
These are same-process component screens, not SDK/Durable throughput or recovery.
No native database, external receiver, S3 store or large artifact was created.

## Repeated conversion counterfactual

Native binding remains active during fixture conversion and timed emission.
Diagnostic modes precompute immutable shared resource/event attribute conversion
and/or per-event-vector EventsJSON strings, then reuse them. No production cache
is implemented or qualified: public callbacks, live policy, freshness, identity,
bounded retention and custom JSONWriter behavior require separate design/tests.
Changed-event input misses the identity cache and changes output identically
across arms. Calibration verifies exactly10000 attribute hits and5000 event-JSON
hits when enabled, zero when disabled. Every timed output matches the full hash.

An initial48-sample screen is preserved. A second60-sample balanced screen adds
the unwrapped production path,12 observations per mode:

| Mode | p50 ms | p90 ms | p95/p99 ms | Estimated B/row p50 |
| --- | ---: | ---: | ---: | ---: |
| Unwrapped | 481.500 | 531.756 | 611.709 | 16381.4 |
| Wrapped baseline | 485.082 | 608.267 | 620.370 | 16382.2 |
| Shared attribute result reuse | 347.513 | 502.024 | 566.986 | 12733.8 |
| Prebuilt EventsJSON reuse | 351.623 | 388.715 | 412.431 | 9999.6 |
| Both | 210.883 | 240.567 | 281.488 | 6351.3 |

This points to approximately61% of estimated encoder allocation in the two
elided regions together. It is NOT a prediction of a safe cache's speedup or an
attainable whole-pipeline rate. Precomputation is outside timing, additional
retention is deliberately counterfactual, and these small-sample percentiles
are descriptive only. The nearby unwrapped control excludes the hypothesis
that the baseline's higher time is primarily introduced by the wrappers; do
not compare this host window directly with earlier230ms windows as a regression.
Allocation uses delta(live heap + GC bytes), not a precise process allocation
counter. Raw counter snapshots are non-atomic.

## Four-byte clean-word WAL copy: rejected

Local branch perf/owned-wal-clean-words-20261019 is based on exact selected3d.
It adds checked aligned u32 input reads and checked four-byte copies only when
all lanes are printable ASCII excluding quote/slash/backslash. Existing scalar
escape handling and twelve-byte reservation are unchanged. Predicate tests
include every ASCII byte at every lane/alignment, boundary/tail cases, real copy
entry counting, and four escape-predicate mutations:3tests/4237assertions pass.

The same owned statement is encoded through production prepared-wal with either
kernel, balanced ABBA48 observations/24perarm. All outputs equal10,508,410bytes
and SHA256ce8c4fb6286c96c16a7984bfa85099dd2fbf8b4d5a7dd155278ae39f34a779e5.

| Mode | p50 ms | p90 ms | p95 ms | p99 ms | Estimated B/row p50 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Selected scalar kernel | 64.979 | 82.387 | 86.457 | 105.587 | 2131.6 |
| Four-byte copy candidate | 82.835 | 109.398 | 113.889 | 119.023 | 2131.6 |

Candidate is slower, allocation unchanged. Do not select, repin or open a PR.
Claude read-only review72644 completed terminal0. Its concrete finding is a
stale formal source pin/fact mapping: main independently ran sha256sum --check,
which rejects the edited WAL source while its snapshot/statement peers pass.
The scalar proof receipt DOES NOT qualify the new four-byte transition. No
digest-only refresh, weakened check, exhaustive model run or protocol change.
Source inspection agrees the new checked copy stays within the old reserve;
that is not substituted for the missing formal mapping or wider/native tests.
Because the candidate loses, extending that proof now would not advance the
performance goal. Local experimental commit/notes and source snapshots preserve
reproducibility without presenting it as a product-qualified branch.

## Next bounded work

Prioritize reducing actual work in attribute conversion and nested event JSON,
rather than more escape-loop speculation or blind native-thread tuning. One
specific source lead is repeated writer publication around non-string key
conversion and sorted-map receiver classification. Those boundaries protect
observable callbacks: they must not simply be deleted or cached across live
policy changes. Count and attribute them first, then qualify any source/runtime
optimization with live replacement/exception/reentry controls and full output.

The last normal rich SDK/local-Durable result remains about10.4k rows/s; this
turn did not remeasure that rate or qualify S3/Rust/service tails. Oscope root
already carries JSON217/exporter4a; singleton exporter2c follow-up and the
separate minimal-profile167 remain pending. No user approval is needed for the
next owned-repository investigation; upstream submissions still need review.
