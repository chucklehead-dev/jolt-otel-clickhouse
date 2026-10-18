# Compact producer research: live helpers and primitive scalar maps

## Baseline and scope

Exporter `57415464e2685703dc8a8d49a017ed5f12317ef0`, clean; cumulative
qualified Jolt `1ae7e0d1` artifact through checksummed wrapper
`jolt-measured-1ae-20261015.sh`, mandatory Chez 10.4.1. SDK37c684a2,
JSON8d557cc18, driver3d766fa remain selected by the exporter's deps and explicit
`:native-json-byte-batch` alias. Resolved classpath captured separately.

All screens are pure components: 5k synthetic spans, wide16 mixed attributes,
two indexed typed fields, default JSON policy, raw-tick timestamps. Actual
base-row/projection/native-byte emission used. Schema confirmation bypassed
only for private fixture compiler; no DB, WAL, cloud, sockets or native stores.
Three warmups; 24 pairs per phase/arm with alternating arm and phase ordering.
Full row/vector and UTF-8 parity before/after, output size checked per sample.
Short correlated p90/p95/p99 numbers are diagnostics, not tail qualification.

## Native compact row constructor: not adopted

`compact-live-row-native-20261018.ss` preserves live exporter helper invocation
and order, but substitutes native lookups and vector construction. Controls
cover event/link values, missing fields, negative duration, and observed live
helper calls. Arbitrary core redefinition is not qualified.

Process13504 terminal0, `compact-live-row-screen-qualified-20261018.log`.
Base p50 21.887->22.040ms; fresh projection/encoding69.603->73.386ms.
Managed base1131.35->1003.46B/row; full3791.29->3662.68B/row.
Approximately128B/row less allocation but no useful time gain. Do not spend
Durable qualification or integrate this handwritten base-row constructor.
The first launch used an undeclared alias and failed before measurement; its
separate log is not a benchmark result.

## Primitive scalar conversion: promising bounded component

The first per-value prototype and next map-scoped version were slower and
allocated~3345B/row extra. Source inspection found their numeric branch used
Chez `number->string`, which reaches its general formatter. The cumulative
Jolt runtime already supplies `jolt-fixnum->string` to avoid that path: preserve
that optimization. No marshaling-layer diagnosis is supported: jolt.scheme's
contract crosses these values raw.

Corrected `scalar-map-fast-factory-20261018.ss` uses that existing primitive.
It admits only builtin HAMT maps of9..128 entries, primitive string keys,
bounded string/Boolean/fixnum values; unsupported maps/policies fall back.
Original exporter key/value helper identities are source-owned; SDK policy is
queried once per callback-free admitted map. Original clone kernel is reused
with its value-procedure seam changed only for this diagnostic, not forked in
production. No custom JSONWriter dispatch or row byte limit is bypassed.

Process61056 terminal0. `scalar-map-fast-screen-20261018.edn` and matching log:

| phase | baseline p50/p90/p95/p99 ms | candidate p50/p90/p95/p99 ms |
|---|---|---|
| base shaping |22.232/27.770/28.625/35.059|15.888/27.001/28.713/29.963|
| fresh projection+encoding |71.904/93.955/116.401/119.003|63.619/78.642/90.312/121.865|

Candidate faster in21/24 base pairs and22/24 full pairs; mean pair ratios
0.779 and0.895. Allocation essentially unchanged: base1131.87/1131.94,
full3791.74/3791.12B/row. No Durable throughput, S3, app, sustained or p99 win
is claimed. Positive poisoned-fallback admission control independently passes;
measured candidate fallback-count is zero. Full wire and typed vectors match.

## Remaining integration requirements

This is research, not a production-safe policy capability. Consumer capture
of the policy helper identity does not establish source ownership against a
preinstalled replacement. The SDK must own that boundary. The current SDK
string-bound API documents only string admission, not permission for callers
to substitute all scalar formatting. Private/core mutation and concurrent
redefinition atomicity are not claimed; keep custom helpers on actual fallback.
Independent Claude source review is running as session18196 on a four-source
packet at `/tmp/scalar-map-review-20261018.kxqDc430`, output
`scalar-map-policy-claude-review-20261018.txt`. Poll same handle; no result yet
credited. One reviewer only; no compiler build or other heavy job active.

Next bounded slice: inspect review, add the smallest SDK-owned primitive-policy
capability and exporter admission/fallback tests, then repeat component screen.
Only a compatible measured candidate merits actual SDK/Durable recovery and
paired performance qualification. Keep prior no-gain experiments as negatives,
not pending optimizations. Do not submit any pending PR without the outstanding
explicit approval. No product branch/dependency or canonical compiler changed.
