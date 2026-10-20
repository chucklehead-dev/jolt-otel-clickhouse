# Stock-Jolt payload-cache checkpoint

This is an experimental branch, not product adoption or a qualified Durable
throughput result. Defaults and application pins are unchanged.

## Implementation

- SDK `casselc/otel` commit `26186ce79941e84e0cc67eb148dd8dd43b4e1880`
  adds an optional trusted native folder to the existing scalar-map payload
  cache. It compiles the injector once, captures the folder lexically, and
  retains all eight SDK policy anchors, budgets and weak immutable-root reuse.
- Exporter runtime commit `7ba2cdd9e8736f015c75514126b33bb2af197af1` adds
  `:experimental-span-payload-cache? true`, default false. It requires the
  existing `:experimental-span-codec? true` and its source alias. One fresh
  lexical converter belongs to each payload; nested payloads do not inherit it.
- Driver `3314b79e8a234fafdf5231439a0323c878a40040` supplies private prepared
  WAL records. Both measured arms use this same optimization and the same
  nested-text byte codec. Neither arm replaces production conversion Vars.
- Official Jolt 0.8.23, pinned Chez 10.4.1, libchdb 26.9.0. No aspect compiler
  or global Scheme traversal helpers are required by the selected path.

This does not claim an entirely upstream-only ecosystem: SDK/driver changes
and the ordinary data.json dependency remain forks. Source-mode qualification
does not qualify standalone/AOT packaging. Woven converter bodies are not
qualified by checking Var identity. Arbitrary injected folders are trusted,
not sandboxed or proven correct by this API.

## Validation

SDK: 19 tests / 162 assertions both with an independent sequence-based test
folder and with the actual library folder. BB/JVM optional-loader smoke checks
decline safely. Exporter and codec: 20 tests / 119 assertions. Existing event
columns: 3 tests / 34 assertions. All pass. Both SDK and exporter received
independent Claude read-only source reviews; no concrete defects remained.

The encoding component checks full output digests for shared and independently
constructed attributes, plus changed-event negative controls. All 8,647,780-byte
outputs match (SHA256 in the EDN receipt).

| Component, 5,000 rows | Cache off | Cache on |
| --- | ---: | ---: |
| Shared attributes, median | 186.10ms | 128.72ms |
| Shared, estimated allocation/row | 13,039B | 9,707B |
| Unshared attributes, median | 194.62ms | 185.38ms |
| Unshared, estimated allocation/row | 13,039B | 13,267B |
| Unshared, maximum of 16 samples | 247.12ms | 524.48ms |

The slow unshared sample used about 514ms caller CPU, with only about 1.9ms
reported GC. GC or descheduling is therefore not an established explanation.
Sixteen samples per cell cannot qualify population p99. Raw p50/p90/p95/p99,
CPU, GC and estimated-allocation observations are retained.

## Actual SDK / local Durable comparison

Fresh processes, off/on/on/off, 32 measured 5,000-row callbacks per process and
three warmups. One exporter/worker, one serialized Durable writer. Independent
older reader checks all fields and multiplicities for 175,000 physical rows
per store; all **700,000 rows** pass. Each owned synthetic store was removed
only after terminal children, successful validation, copied/cmp receipts and
source-stability checks. No persisted stores are retained in this packet.

| Arm | Confirmed window rows/s | Estimated allocation/row |
| --- | ---: | ---: |
| Off | 11,972 | 20,353B |
| On | 13,920 | 17,050B |
| On | 11,146 | 17,071B |
| Off | 8,515 | 20,385B |

Allocation drops consistently by about 16%. Pooled rates are 9,952 versus
12,379 rows/s, but baseline drift is 40.6%, exceeding the apparent 24.4%
difference. Do not claim a qualified throughput improvement or target/tail
achievement. CPU/row also rose across the run; its cause is not established.
Callback latency quantiles and their explicitly labeled reciprocal batch rates
are in the summary; those are not end-to-end span latency or population SLOs.
No S3, real application, failure-injection, or Rust-relative qualification here.

## Library layering and next performance slice

Keep one incubating library with separate traversal, byte/JSON and later
schema/RowBinary modules before creating multiple repositories. The existing
`traversal.ss` already isolates private representation access; its synchronous
full-fold contract is not yet a portable reduce-kv/Reduced API. Keep the hot
codec/traversal calls in Scheme, without per-element interop adapters.

The next candidate is bounded reuse of already encoded normalized attribute
maps, avoiding repeated field dispatch and escaping. It must preserve exact
bytes/order, depth and size checks, conversion timing, ownership and live-policy
guards, and must include the unshared control. It is **not implemented here**.
Afterward, fused schema-directed projection can avoid intermediate row maps or
vectors. Neither change should alter WAL protocol or persistence ordering.

## Evidence

This small packet retains scalar receipts, scripts, source hashes, diffs and
review text, not native binaries or database stores. Replay uses the exact
worktrees named above, the standard Chez wrapper, and the existing reader and
fixture paths named by the scripts. It is not a standalone benchmark package.
The runner and selector show the exact local-root SDK/driver overrides and
source alias. `sources.sha256` includes runtime/native artifact checksums.
