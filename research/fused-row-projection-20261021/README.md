# Native compact-row preparation: allocation win, not adopted

Research against exporter product baseline a6cbf939, on research parent
581b50259e37b79ee51f7b1a881fad60150ec22f. Official stock Jolt 0.8.23, Chez
10.4.1 through the mandatory workspace wrapper. No compiler/SDK/data.json fork
change, production source, flags, dependency pins, model or acknowledgement change.
The selected consumer dependencies still include ecosystem forks.

The native prototype calls the existing live exporter timestamp, enum, service,
trace-state, conversion, nested and typed policy functions. It constructs an
opaque codec row rather than a compact persistent vector followed by `into`.
For default helpers, empty/one-event and empty-link columns use multiple values
instead of allocating wrapper vectors. Multi-event/nonempty-link/custom-helper
cases call the existing helpers. No input mutation, global Scheme registration,
raw JSON insertion or mutable output loan is introduced. State is per payload.

This is **preparation fusion**, not direct emission into the outer byte sink.
It still creates a native row/view, event column values, typed suffix and
normalization outputs. The encoder still walks those prepared representations.
Do not call it a completed zero-allocation or fully fused pipeline.

The research harness captures original event/link helper roots before controls.
A production integration would need trusted original anchors in the defining
namespace, not a caller's snapshot. Raw runtime helpers remain trusted/version
sensitive. This prototype is intentionally not a production API or opt-in.

## Functional controls

4 tests / 62 assertions, zero failures/errors, stock source execution exit 0.
Coverage includes ordinary shapes, empty/nil/false collections, multiple events,
nonempty links and SDK SpanContext records, missing scope/resource/status,
trace-state lists, Unicode/escaping, zero/max timestamps, changed-event bytes,
callback order, exact error identity and no later callback after failure,
custom column helpers, timestamp/value policies, policy change during conversion,
helper change during nested encoding, and invalid/missing/out-of-domain timestamps.

The first run encountered the codec's EXISTING rejection of list-shaped events.
The test was corrected to require the exact rejection in BOTH arms, separately
from supported-shape equality; the codec was not broadened or gate weakened.
These are bounded tests, not proof of all callback/redefinition/core semantics.
Only baseline/candidate are covered by these functional tests; the row-view
comparison is the reused prior source transformation and has full-byte screen
controls. No actual SDK queue/native storage/recovery is exercised here.

## Three-way rich component screen

The unchanged 5,000-span rich fixture, actual SDK per-payload converter/cache,
scalar views, typed projector, nested and outer codec. Fixed
baseline/row-view/native/native/row-view/baseline order, 16 samples per arm.
No per-row timing observers. ALL 8,647,784 output bytes agree after every
measurement; changed-event controls differ in all three arms.

| Arm | p50 / p90 / p95 / p99 ms | Median estimated managed B/row |
| --- | --- | ---: |
| Existing vector row | 207.43 / 260.30 / 267.53 / 267.53 | 8871.80 |
| Prior row-view only | 213.29 / 260.64 / 264.69 / 264.69 | 8294.66 |
| Native preparation | 212.76 / 268.01 / 310.70 / 310.70 | 7558.31 |

Allocation estimate is ~14.8% below baseline and ~8.9% below row-view only.
Median time is ~2.6% worse than baseline; observed p99/max ~16.1% worse.
There is NO demonstrated throughput or tail win. Samples drift; no filtering or
favorable repeat. Allocation is a non-atomic live-heap plus reclaimed estimate,
not native/RSS/allocation-site profiling. Source/private probes do not establish
release optimizer behavior. No native sweep, default adoption or PR is justified.

## Reproduction

Run from exporter root with the official stock binary, prefixing Jolt with
`/home/chuck/ai-src/tools/jolt-with-chez-10.4.1`:

```sh
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src" "research/scalar-view-exporter-20261020/app/src" "research/fused-row-projection-20261021/src"]}' -m perf.row-probe
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src" "research/scalar-view-exporter-20261020/app/src" "research/fused-row-projection-20261021/src"]}' research/fused-row-projection-20261021/screen.clj research/fused-row-projection-20261021/result.edn
```

No build/native store was created. Small source, scalar samples and review
evidence are sufficient; do not create another standalone native app for this
negative screen. Next optimization must target work not merely representation:
avoiding repeated projection/encoding walks or actual preparation/publication
overlap under its separately approved model/driver contract, not more row wrappers.
