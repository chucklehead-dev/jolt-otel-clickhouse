Implementation checkpoint, not an end-to-end throughput claim:

- SDK topic [diff](https://github.com/casselc/otel/compare/ecce82c427be527b8778cf8dd26a86ff9e22d9e6...697f58bd5b4925f72339694bedbace97bdc869bb)
  adds optional `try-scalar-string-map` with SDK-definition-owned policy anchors,
  bounded builtin-map admission, live helper/limit drift refusal, known ABI/
  fixnum-width fences and nil fallback. Unsupported inputs/hosts must retain
  ordinary conversion; no values, budgets, writer semantics or capture API change.
- [Exact-head CI](https://github.com/casselc/otel/actions/runs/39128818354)
  **SUCCESS** at697f58b, both jobs. The new stockJolt0.8.23 job actually ran
  native5tests/66assertions and ordinary AnyValue17/388; it cannot silently skip
  native selection. Existing full SDK floor job also passes. Local full SDK
  suite at0cd4e1a passes470/4086; isolated BB/JVM optional-helper decline passes
  7assertions each, not full SDK/codec portability. Separate gaps:
  [casselc/otel#63](https://github.com/casselc/otel/issues/63).
- Two completed independent source reviews; main addressed the useful fixnum
  width/malformed-loader points and checked remaining notes against actual
  source/tests. No concrete functional defect remains identified by those
  bounded reviews. Initial empty timed-out review is not credited.
- Consumer [initial diff](https://github.com/chucklehead-dev/jolt-otel-clickhouse/compare/57415464e2685703dc8a8d49a017ed5f12317ef0...e23d535b321e327293fa0a5d6808a0cf6be199ec)
  preserves original converter guards and fallback. Focused4/20 plus existing
  native attributes21/607 and projection24/321 pass. SDK pin/consumer CI
  integration is next; no PR or merge has occurred.

Actual local SDK/Durable/native26.9 ABBA uses one SDK worker and the same
connection,16samples/arm at5k rows/batch. Independent unchanged exporter/old SDK/
stock-reader recovery verifies **all175,000 physical/typed rows**, zero dropped
or failed SDK spans. Encoding caller-thread CPU **96.74->91.63ms**, but full
insert-body mean **186.70->186.37ms** is effectively flat as native stage varies.
Mixed-arm aggregate25,990.95rows/s is not candidate throughput or p99/Rust/S3
qualification. The pure component's base shaping improves23.03->16.17ms p50;
full projection/encoding68.43->67.03ms p50 (mean paired ratio0.923). Keep the
CPU benefit, but do not label it a throughput-target win.

Sources/scalars preserved locally; the266MiB native store was removed only
after terminal writer/reader success, full readback verification and copied
receipts. No compiler rebuild or default/canonical aspects branch change.
