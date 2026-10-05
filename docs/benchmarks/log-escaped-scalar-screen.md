# Escaped log scalar experiment: do not promote

The actual collector's generic log path is not a missing runtime capability.
The Jolt row and batch encoders compile, the closed schema matches and the
fixture is eligible. The strict batch encoder declines the resource schema URL
because `/` requires escaping under data.json's defaults. The existing route
then rebuilds generic physical row maps.

Candidate `cd60fea` delegates escaped string tokens to data.json inside the
batch encoder, counts their actual UTF-8 bytes and retains the same per-row
overflow and confirmed publication boundaries. It introduces no independent
escaping implementation or persistence changes. Fifteen focused tests / 178
assertions passed, including URL/control/Unicode parity, no full-row map
construction on that route, and overflow before observing the later lazy row.

This candidate is **not recommended for integration**: it saves allocation but
is slower. Retain the scalar-helper baseline `1af91f3` for product integration.

## Measurements

Selected rebuilt Jolt `2223c24a`, Chez 10.4.1, AOT disabled, chDB `7dcaec0`,
data.json `993b906`, OTel `19fc49d`, libchdb 26.7.3. Local POSIX Durable,
10 x 5,000-item five-table collector, unchanged per-physical confirmed insert:

| Exporter | Rows/s | Scheme heap allocation |
| --- | ---: | ---: |
| Baseline `1af91f3` | 17,676.32 | 9,365,567,952 B |
| Escaped token candidate `cd60fea` | 16,732.69 | 9,167,114,848 B |

Sequential pipeline runs alone do not establish causality. A symmetric component
screen isolates encoding of the same prebuilt 512-record URL-bearing fixture,
ten payloads per arm. Each arm produces exactly the same JSON bytes:

| Encoding path | Two arm times | Allocation per arm |
| --- | ---: | ---: |
| Generic physical rows, native key-cache writer | 92.47 / 91.90 ms | 91.15 MB |
| Direct batch, configured scalar writer | 166.19 / 159.85 ms | 72.05 MB |
| Direct batch, fresh native payload writer binding | 137.64 / 170.15 ms | 74.07 MB |

Even native scalar delegation does not beat the generic native writer. The
direct path pays per-column Clojure accessor/lookups, predicates, appends and
volatile byte bookkeeping. These are source-visible candidate costs, not a
separately isolated attribution of each operation. Reducing allocation alone
does not imply throughput improvement. Do not broaden the direct path as a
performance fix on this evidence or discard the existing generic/native gains.

Receipts in the workspace evidence directory:
`exporter-log-escaped-durable-20261005.edn` and
`exporter-log-escaped-component-20261005.edn`; drivers use the same basenames
with `.clj` where applicable. The independent count reader companion is
`exporter-log-escaped-durable-20261005.edn.recovery.edn`.

Next work should optimize the natural generic/native pipeline, not duplicate
more JSON escaping in the exporter. The generic payload phase and the combined
confirmed execute/WAL/publication phase remain the largest measured targets.
No full aggregate, Claude review, robust tail, S3 or Rust-relative qualification
is claimed for this experimental branch. Do not open a product PR from it.
