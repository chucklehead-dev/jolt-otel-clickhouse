Implemented the runtime-owned traversal alternative; no private sorted-tree
layout added to data.json:

- Latest stable Jolt0.8.23 topic:
  [a0ac71c...646fcfc](https://github.com/casselc/jolt/compare/a0ac71c3f638fe55158bd01b4ecbb0dd3d7545e5...646fcfcbb1e8bcda33f0a4b9e40d32db0ffcd222).
  Native stock-ops predicate plus seq-order key/value fold, no temporary entries
  or comparator calls; custom operations decline before callbacks. No ordinary
  collection path switched. Source gates79/79 +existing sorted58/58.
- Optional JSON consumer:
  [a18ae06...96f3e47](https://github.com/casselc/data.json/compare/a18ae06c787e6e03709bd78f1b92911130a613f1...96f3e47dba5970728866dacb5ac41a25135a2df5).
  Live Map/child dispatch, prefix edits, errors, reentry and unsupported-option
  fallback retained. Both helpers required; wrong arity/noncallable/absent
  helpers decline without trial calls. Independent source/CI reviews completed.

Actual GENERIC implementation, same-byte SDK2k component: p50128.8->118.5ms,
managed allocation30.3->23.1KB/row. Actual local queue/Durable5k ABBA16/arm:

| p50 ms | baseline | candidate |
|---|---:|---:|
| full insert |547.3|524.9|
| projection/encoding |356.7|326.9|
| native+confirmation |177.1|173.6|

Full mean575.5->544.8ms (~5.3%); managed allocation38.7->31.5KB/row (~18.5%).
Independent older stack verifies175000 full physical rows, exact event ticks,
typed status/values and multiplicity. Earlier bounded scalar diagnostic had a
larger median improvement but worse upper observations; it is not this generic
implementation. No p99/SLO, Rust-relative, real-app or hosted-S3 throughput claim.

Native artifact is STILL qualified1ae; these measurements explicitly overlay
new helper source. Simply repinning JSON on that old artifact does not enable
the feature. App/exporter pins and canonical integration/aspects are unchanged.
Next gate: qualified artifact, then exporter/Oscope integration. Both747MB
stores removed after terminal writer/reader +scalar receipt cmp +validation.

Hosted [JSON6305f3d CI](https://github.com/casselc/data.json/actions/runs/39145448570)
passed native/JVM/ordinary Jolt. Actual job logs show60/982 helper-absent tests
and65/1006 source-overlay tests. Final96f3e47 only refreshes source pin646fcfcb
(bounded rejection diagnostic) and explicit outcome markers; exact final CI
pending refresh. Jolt final-head full tests/flake also still pending.

One correctness-regime gap caught and bounded: local `-A:jolt-native-test -M
script.clj` ran the alias main instead of the requested driver. Those38/621
passes were real native-suite passes, not the claimed byte/segmented/script
scope. Fixed explicit-path drivers verify exact namespace counts and entry/
completion/pass markers. Benchmarks and Durable/effects drivers did not use
that alias, so their execution was unaffected. No blanket claim that earlier
hosted `-A ... -e` CI was wrong; its current scopes are explicitly proven.
No protocol/model transition changed, so no unchanged exhaustive run launched.

Branches published for review, no PR submitted or merge, especially no upstream
public mutation. Remaining target gap is substantial; the next larger candidate
is latest-Jolt loaded-code optimization while retaining live policy boundaries,
not more already-rejected closure/lock probes.
