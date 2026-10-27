# Adjacent retained-binary control: investigate graph differences

After the completed constructor-only comparison, one additional **diagnostic**
pair ran sequentially on the quieter host. This is not another HAMT adoption
trial, an ABBA graph comparison or an estimate of a stable causal effect.
Both runs use overlap,5k rows,3warmups+32measurements and the same native26.9.0.

| Graph/binary | Confirmed window rows/s | Cadence p50/p90/p95/p99 ms | Estimated B/row |
| --- | ---: | --- | ---: |
| Retained historical `e022bf26...` | 15,958 | 282.58 /390.05 /603.69 /616.81 | 14,445 |
| New prototype graph `52050ea8...`, HAMT disabled | 11,362 | 438.79 /572.71 /694.95 /718.84 | 14,452 |

Both writers had zero failed/dropped spans. Both independently recovered
175,000 rows against the older-stack ordinary JSON oracle, including every
physical field/type, exact ticks and row multiplicity. Small [receipts and
host snapshots](control-drift/) are preserved; successful generated stores were
retired after recovery. No source mutation, profiler or other local build/test/
agent-review workload ran during either writer window. This cannot prove the
VM was otherwise interference-free.

The old binary's earlier post-interruption screen was only4566rows/s; recovery
from that slow state does not fully restore its historical~22k result. The
adjacent15,958 vs11,362 difference means **do not assume all remaining drift is
ambient host noise**, either. Allocation is nearly identical in this disabled
comparison, so the difference is not explained by a large allocation change.

The graphs differ in SDK projector routing, codec planner/kernel source, and
the research entrypoint (direct pipeline main vs a constructor-selector wrapper).
This pair does not isolate which difference matters. Neither the claim that
the wrapper changes release optimization nor that the new codec/SDK causes
the whole gap is established.

Next bounded causal control: keep the new graph/disabled selector and exact
compiler/library pins fixed, build the unchanged public pipeline as the direct
entrypoint with selector initiallyfalse, and compare it to its existing wrapper
binary. If the gap remains, isolate old/new sorted-only codec and SDK routing
with the same entrypoint. Do not loosen policy guards, change leases/models,
delete original controls, upgrade/rebuild the compiler, or repeat enabled
trials unchanged to seek favorable timings.
