# Publication subphases on the stock pipeline

Read-only diagnosis using the existing driver `:writer-phase!` hook plus the
earlier coarse wrappers. Stock Jolt0.8.23, published codec15b15f3, SDK14f58e5,
driver3314b79 and chDB26.9. One serial rich local-Durable writer, 5,000 rows per
callback, three warmups and 32 measured callbacks. Not live application or S3
qualification and not a before/after performance comparison.

Observer tests: four tests / 19 assertions. Closed labels and scalar fields
only, no SQL/WAL content, references or errors retained. Diagnostic failures
cannot replace operation results; they fail the final report validation.
Observer events do NOT sample allocation. Their report values are nil, not a
claim of zero allocation. All 595 events complete; each of the 17 phases occurs
once in every measured batch. No production source was changed.

| Phase | p50 ms | p90 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|
| Compact total | 550.04 | 679.04 | 699.74 | 704.13 |
| Project/encode | 259.99 | 343.15 | 346.02 | 360.52 |
| Snapshot validate/copy | 22.69 | 32.45 | 35.04 | 47.68 |
| WAL precompute | 40.70 | 57.54 | 61.49 | 68.75 |
| Execute/confirm (parent) | 214.59 | 276.46 | 285.95 | 289.52 |
| Native stream finish (nested) | 81.28 | 113.97 | 127.12 | 129.75 |
| Placeholder scan (nested) | 26.01 | 37.44 | 42.57 | 55.18 |
| WAL immutable put (nested) | 28.04 | 38.14 | 41.42 | 50.45 |
| WAL immutable verify (nested) | 12.73 | 15.88 | 18.67 | 26.65 |
| WAL append (nested) | 7.73 | 12.57 | 14.27 | 17.54 |
| Head CAS (nested) | 4.86 | 6.05 | 7.19 | 14.95 |
| WAL join (nested) | 1.63 | 2.83 | 3.35 | 3.37 |
| WAL prepare/reuse (nested) | 0.27 | 0.33 | 0.34 | 0.45 |

Do not add parent/nested phases or independent medians. `summarize.clj` instead
subtracts source-confirmed non-overlapping leaf durations within each batch;
execute/confirm's unassigned remainder averages35.68ms (median36.71ms). That
residual includes observer overhead and is NOT an attributed operation. Source
inspection locates WAL file SHA computation before immutable-put timing, but
its actual cost and other control/ownership work are not separately measured.

Project/encode accounts for 47.6% of whole-parent total wall time in THIS run.
The absolute times differ substantially from earlier runs, so do not turn a
cross-run comparison into a regression or an optimization claim. p99 is the
maximum of 32 samples, not population-tail qualification. Calling-thread CPU
does not account for all native/worker CPU.

Independent older-stack recovery verified all 175,000 rows, physical fields,
types/status and multiplicity. Copied reports, comparisons and unchanged-source
checks passed before retiring the exact owned store. Small raw reports remain
at `/home/chuck/ai-src/evidence/publication-subphases-20261020`.

Next targets are still encoding/traversal and measured publication/hash/copy
costs. WAL prepare/reuse and WAL joining are not dominant here; do not optimize
them on the assumption that they account for the whole publication remainder.
