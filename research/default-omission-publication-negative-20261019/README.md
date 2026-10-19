# Deferred omission-prefix publication: not selected

Candidate local data.json commit `654c1f9dc23cca624add78ca71464d35fb7aed26`
on `perf/default-omission-publication-20261019`, parent/tested library
`2172061060a102ccab779f5adc5d77f8b83a81f6`. The complete five-file patch
is preserved here. No PR, consumer repin or runtime change was made.

It removes the unknown-child flush before a now-pure default identity check.
Observable classification/custom-writer publication remains in emit!'s method
site/invoke boundary, after the current key/colon. The isolated mechanism
control changes from three actual text publications to two for a nested sorted
map. Old source fails exactly that assertion (6 pass / 1 fail / 0 error);
candidate passes 2 tests / 7 assertions. Wider contracts: 67 tests / 1,013
assertions, native hooks 38 / 621, default omission 4 / 16, all green.

Matched full 5,000-rich-row encoding ABBA, four fresh source processes, same
compiled7098/exporter4d/SDK7878, only JSON217 versus654 changes:

| Measurement | JSON217 baseline | JSON654 candidate |
| --- | ---: | ---: |
| Lazy encoding p50 ms | 223.109 | 223.769 |
| p90 ms | 281.591 | 323.563 |
| p95 ms | 291.956 | 406.250 |
| p99 ms | 328.749 | 467.232 |
| Estimated bytes/row | 17,228.224 | 17,228.627 |

All full-output sizes/digests and ordinary/changed-input controls match. The
workload shows no meaningful median or allocation gain and worse observed
tails, so this candidate is NOT selected as a performance improvement.
These component observations are not Durable or sustained-tail qualification.

The exact source-only Claude review attempt (session57207) ended with timeout
exit124 and an empty report. That is no review evidence. Because the measured
candidate is rejected for adoption, no review retry or CI upload was launched.
No bulk data/store/build was produced; only bounded sources/logs/scalars remain.

The refreshed overlapping row-component probe uses the SELECTED JSON217, not
this candidate. Event JSON is now 39.5 ms / 6,668 estimated B/row versus the
prior JSON96 screen's 84.1 ms / 13,357 B/row. Other visible costs remain:
event columns 25.3 ms / 3,308 B/row, resource attributes 20.9 ms / 2,124 B/row,
typed projection 10.2 ms / 540 B/row. Full-row conversion is 163.5 ms / 13,580
B/row. These overlapping medians/allocations are NOT additive. The next bounded
experiment targets singleton event-column builders while retaining the selected
JSON217 and all earlier cumulative wins.
