## Summary

Avoid three transient `mapv` builders when a span has one event in an immutable
vector. Build fresh timestamp/name/attribute vectors directly, in the existing
conversion order. Empty, lazy and multi-event inputs keep their current paths;
event JSON, typed values, output limits and Durable acknowledgements do not change.

The row-component profile identified event-column construction at about 3.3 KB
of estimated allocation per rich row. This small portable Clojure specialization
removes setup work without adding a native encoder or new runtime dependency.
Changelog and focused CI entry included.

## Red / green and correctness

- The independent original three-pass reference matches all named/compact
  outputs across timestamp modes, Unicode/Boolean/nested attributes and boundary
  timestamps. Tests retain live converter order, failure and lazy-input controls.
- Old source: **33 assertions pass, one mechanism assertion fails** (three
  `mapv` calls instead of zero). Candidate: **3 tests / 34 assertions**, green.
- Related ordinary/compact/time/native event suites: **43 tests / 317 assertions**,
  green. Source-only Claude review found no functional defects; Codex checked
  conversion ordering and immutable-vector scope against the source and tests.
- Four fresh real SDK/local-Durable processes in ABBA order, 5,000-row batches,
  plus four independent older-stack readers: all **700,000 rows** recovered with
  full field/value/status controls, no dropped spans or export failures.

## Measured impact — allocation improvement, not a throughput claim

| Local SDK/Durable measurement | Baseline | Candidate |
| --- | ---: | ---: |
| Confirmed-window rows/s | 10,428 | 10,426 |
| Estimated allocated bytes/row | 25,233 | 24,392 |
| Callback p50 ms | 446.3 | 448.5 |
| p90 ms | 588.8 | 582.9 |
| p95 ms | 621.1 | 611.9 |
| p99 ms | 666.1 | 665.6 |

Throughput is flat; allocation decreases about 3.3%. Two process repetitions
per arm do not qualify sustained service tails, S3 or Rust-relative performance.
The separate component encoding screen saves about 848 bytes/row but has worse
observed upper quantiles; it is not substituted for this pipeline comparison.
All earlier cumulative JSON/SDK/runtime improvements remain enabled.

Related: #138; [core allocation extraction research](https://github.com/chucklehead-dev/jolt-aspect-packs/issues/176#issuecomment-6270824403).
No upstream submission or runtime fork change is included.
