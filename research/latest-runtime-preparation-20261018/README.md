# Latest-release row-preparation screen

Actual exporter4d48871 on latest-release source18dce94a(v0.8.23base),
mandatory Chez10.4.1; native optional JSON is deliberately not activated.
Configured ordinary JSON allows this experiment without pretending stock Jolt
already provides our native writer's method-site and buffer capabilities.

Frozen driver SHA256b45a829ba0deadae3e025453dc6effd7dd9de0bea84e07bf5bc5a875e3f83fd4.
Fresh-processopen/direct/direct/open,16measured5000-row preparations per
process,3warmups; runner24799terminal0. Independent same-source row
references and cross-arm whole-result hash match:
0fd4f377d5a4cee29a4d41433e1006628d4a7f9c6cf1c4db1e1437d98ee62d5e.
Native mode, typed projector, actual SQL/WAL and fresh recovery are not in this
screen. Do not compare these times with the native-writer Durable benchmark.

| Configured-JSON row prep | p50ms | p90ms | p95ms | p99ms | estimatedB/row |
| --- | ---: | ---: | ---: | ---: | ---: |
| ordinary loading |572.526|710.532|716.636|775.356|73957.5|
| runtime direct-link opt-in |501.073|753.722|798.446|842.017|74228.7|

~12.5%lower observed p50, no allocation reduction and worse observed tail.
32observations per arm do not qualify production p99. Not a product default,
dependency/runtime pin change, throughput gain or reason on its own to migrate
the aspect compiler. Keep the qualified cumulative native stack unchanged.

Post-timing attribute value-policy and public JSON writer replacement controls
are observed in all four processes. Separate untimed mechanism probe67881
terminal0 counts3677direct-link emissions,162in exporter namespace; a known
small callee's caller keeps its compiled body after replacement, proving the
feature is active. Exporter key-policy replacement remains observed in that
probe. These three controls are not a proof that every callback contract is
preserved under direct-linking; focused existing mutation suites remain gates.
The counting wrapper is confined to the untimed probe, never a timed arm.

Next bounded migration work, if pursued: establish the complete helper/domain
prerequisites from actual consumers, port only to an isolated plain runtime
topic, run full native JSON/exporter effects gates, then compare real binaries
on the same native workload. Do not rebase canonicalintegration/aspects or
relax policy/dispatch/visibility guards merely to activate this feature.

The adjacent minimal-port audit was misleading; read its adjudication before
using it. All artifacts here are small source/scalar logs; no build/store bulk.
