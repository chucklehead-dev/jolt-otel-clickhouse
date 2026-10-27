# Incomplete native pipeline screen: no adoption

One release binary, SHA256
`464315a6b517dea7c84521e0c782002e7a35a96063df9f8e78a89326e781c2c7`,
used the new SDK for both sorted-only (`disabled`) and HAMT-view (`enabled`)
arms. See [setup and boundaries](README.md) and [raw receipts](results/).
The prototype branches remain unmerged and product defaults unchanged.

| Arm | Confirmed window rows/s | Cadence p50/p90/p95/p99 ms | Estimated bytes/row |
| --- | ---: | --- | ---: |
| A1 disabled | 12,204 | 384.68 / 536.51 / 583.58 / 619.83 | 14,449 |
| B1 enabled | 9,658 | 497.26 / 792.59 / 837.14 / 881.57 | 14,046 |
| B2 enabled | 13,457 | 373.83 / 422.45 / 529.79 / 570.97 | 14,040 |
| A2 disabled | incomplete: lease fenced | no final receipt | unavailable |

Each completed arm stored 175,000 rows (3 warmups + 32 measured 5k batches).
Separate older-driver/SDK/JSON processes verified all physical fields/types,
exact timestamp/event ticks and 35 copies of each fixture row against ordinary
JSONEachRow export. All three completed arms had zero SDK failures/drops.
The independently recovered total is **525,000 rows**, plus the 24-row smoke.
Successful synthetic stores were retired only after compact receipts were
copied; failed A2's store remains available for diagnosis.

The fourth arm has 16 incremental confirmation observations but no writer
completion or recovery receipt. During execution the environment clock advanced
from October 21 to October 27. Its persisted lease expired at
`2026-10-21T23:07:53.261Z`; observation on October 27 and `lease-fenced` are
consistent with correct expiry rejection. This is not permission to extend or
bypass lease checks, nor is it a completed four-arm qualification or a proof
that every admitted request settled. The original goal and invariants stand.

After the interruption, the retained previously qualified binary
`e022bf2694dddcbf90898aee0e81ed11b231b6406f74df28e2fca5e558d209a7`
was screened on the same 5k/32/3 overlap geometry: **4,566 rows/s**, versus its
historical pooled overlap rate ~22,395. Its observed p50/p90/p95/p99 cadence was
1051.73/1416.49/1433.06/1470.04 ms, allocation ~14,477 bytes/row, no SDK
failures/drops. The measured worker thread used 33.16 CPU seconds during a
35.04-second confirmed window; process-wide GC wall increased 1.08 seconds.
CPU and GC have different scopes and must not be subtracted as equivalents.
Host load observed **after** that screen was 18.43/37.68/19.42; this is context,
not a synchronized attribution of all performance variation to contention.
Fresh independent older-stack recovery of this later control also passed for
all 175,000 rows; its receipt is retained here. That store was then retired.

## Decision and next action

There is no reliable full-pipeline speedup or target qualification here. The
roughly 400-byte/row allocation reduction in the completed enabled arms is
smaller than the isolated component saving; it does not establish better
confirmed throughput. Preserve these negative/mixed samples rather than select
B2 or repeat only favorable timings.

Before another timed campaign, check activation/copy counts on the actual rich
fixture in a separate mechanism diagnostic and confine the research selector
to construction, with no dynamic binding spanning worker lifetime. Then use a
quiet host and a fresh complete matched campaign, preserving the old binary.
The timing difference between the dynamic control and a constructor-only
control is **not measured or established** by this screen.

The separate rich-fixture exporter-handoff mechanism diagnostic now passes
with both the original constructor binding and the later constructor-only atom
patch: eight span-map inputs call the original copying helper 8 times disabled
versus 1 enabled; enabled selects 7 opaque span views. Both arms select 23
sorted views for the remaining resource/scope/event inputs. All nested encoded
bytes match. This checks source mechanism and fallback equivalence, not worker
activation counters, native pipeline timing or the reason for the mixed rates.

Claude reviewed checkpoint `e22ecee` read-only and reproduced the table/receipt
numbers from source, not by executing gates. Its follow-ups were the binding
scope, missing requested-mode receipts and missing host-observation record.
Those receipts/explicitly unsynchronized observation are now included, and the
later manual selector avoids a worker-lifetime binding. The factory did capture
the selected Boolean before worker startup in the measured code; the review's
scope concern does not invalidate that narrower construction-time fact.
The review does not establish dynamic binding or contention as a slowdown cause.
The newer atom patch/diagnostic were tested by Codex after that review.

No Quint scope, lease duration, admission/publication semantics or production
configuration was changed. No exhaustive checks were rerun.

## Setup failures kept distinct

The initial source smoke used an incorrect library environment variable and
failed the explicit native-version guard before storage acquisition. Correct
selection is `JOLT_CHDB_LIB`, not `CHDB_LIBRARY_PATH`. The reader initially
omitted the codec dependency imported by the shared fixture and failed load;
the corrected reader graph uses published older codec `c75579b`. The retention
helper is not executable and must be invoked with `bash`; the smoke's recovery
had already passed before that shell failure. Logs remain locally. None of
these setup failures was counted as a product regression or successful timing.
