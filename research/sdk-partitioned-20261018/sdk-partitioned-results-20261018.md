# Partitioned SDK capacity: completed bounded comparison

No product/default/API/compiler/model change. A1/B/A2 use one/two/one distinct
real exporter instances, one serial SDK worker per instance, one outer-owned
Durable connection,5kbatch×32measured exports and three direct warmups. Total
queued cap10k in both layouts; extra in-flight batches/memory can differ.

| Arm | SDK/exporter lanes | Confirmed aggregate rows/s | Callback p50/p90/p95/p99 ms | Managed B/row |
|---|---:|---:|---|---:|
| A1 |1|26,810|180.702 /222.396 /254.702 /294.241|8,026.49|
| B |2|41,454|221.896 /297.809 /310.089 /314.340|8,018.30|
| A2 |1|24,995|187.624 /240.208 /267.084 /267.893|8,027.69|

Two lanes show55–66%higher aggregate capacity than either control in this
window, not a single-caller speedup.31cross-lane callback interval overlaps
positively demonstrate concurrent work; every per-lane interval is serial.
Single controls drift~7%, substantially less than this observed difference,
but this remains an exploratory sequential comparison, not sustained/Tail/Rust
parity or application ingress qualification. Callback latency rises; B's
inversep50/p99 are~22.5k/~15.9krows/s, NOT25k/20kper-request qualification.
Thirty-two samples makep99themaximum, not a reliable tail estimate.

All480kmeasured spans accepted/exported,zero failures/drops,queues empty and
32actual export callbacks perarm. Each callback returns literaltrue through
the actual exporter's confirmed/reconciled Durable path.35native stream calls
perarm include3warmups. Full fresh-process readers verify all525kphysicalrows,
original timestamps, typed values/status and expected per-record multiplicity.
Independent BB validators pass, including per-lane totals/serialization.
No measurement-time forceflush/shutdown, preencoded SQL reuse, weakened ACK,
sync omission, checkpoint-threshold change or custom codec contract.

One bounded load generator waits queuecapacity; waits are INCLUDED in wall
time:5.144s/3.049s/5.567s. Application `on-end` is unchanged/nonblocking; this
does not prove an unlimited producer cannot overflow. Inputs partition by
whole batch round-robin; this is NOT a real trace-affinity router. Cross-lane
ordering is not guaranteed. More workers can alter in-flight/GC/cache behavior.
Only exporter0 receives three direct warmups in each arm; no SDK worker or
second exporter warmup is silently excluded, and all first callbacks remain.

## Source and validation

Frozen clean exporter67e28b59/JSON8d557cc18/SDK37c684a2/driver3d766fa,
qualified1ae and cachednative26.9, same as the earlier SDK screens. Launcher
checks exactsourceheads/cleanliness and nativeSHA. Driver graph is actual
resolved3d, not older fixture3fd sources; that fixture supplies test helpers.
Not stock-compiler qualification or a matched current Rust comparison.

- `sdk-partitioned-durable-screen-20261018.clj`, SHA256
  4521e485d5c9d048c958709b5e2a8063dcf629b61efcad61d016e44371fadea6.
- `run-sdk-partitioned-screen-20261018.sh`, SHA256
  293b3625e424e9e6cd81f391d9071edc9bbde47033fab2ee521393215c72c35a.
- A1session76081,B24539,A23682 allTERMINAL0, including writer, reader,
  independent verifier and cleanup. Retain only scalar receipts under
  `sdk-partitioned-5k-{A1,B,A2}-20261018.*` plus source/review/summary.
- `summarize-sdk-partitioned-20261018.clj` independently recomputes quantiles,
  rates, global allocation and overlapcounts from allthree receipts.
- Three verified synthetic stores270+269+270MiB removed after terminal
  writers/readers, scalarcopy equality and proof. No user data removed.
- Claude50878TERMINAL0, no concrete bugs in the four-file isolated source
  packet. Main checked actual SDK shutdown/settlement, queue capacity and
  report accounting. Review wording that warmups only prime the shared
  connection is too broad: onlyexporter0waswarmed; above scope is authoritative.
  Review also says shell explicitly asserts tail-qualifiedfalse; the shell
  emits it, while the independent summary now actually asserts the writerflag.
  No source changes to measured driver or launcher after packet review.

## Consequence

This is an existing-API integration opportunity, not an adopted mode: parallel
encoding across distinct exporters can feed the one serialized Durable writer
more effectively. Do not concurrently call one SDK exporter instance or turn
admission tickets into successful export ACKs. A product adapter would need
explicit partition/ordering semantics, aggregate queue+in-flight bounds,
partial-construction and stable-shutdown ownership, schema/admin coordination,
and per-lane diagnostics. Keep existing single-lane defaults. Compare real
trace-affinity and arrival patterns before offering it as an Oscope setting.

Single-lane projection/encoding work still matters, especially tail latency;
partitioning is not a replacement for the ongoing allocation/upstream effort.
