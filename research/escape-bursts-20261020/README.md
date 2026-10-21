# Guarded escape/digit bursts: validated candidate, not adopted yet

Candidate native JSON kernel replaces repeated byte-at-a-time writes with:

- one capacity check/two private writes for ASCII escape pairs;
- one capacity check/six private writes for a Unicode escape;
- one capacity check/digit scratch block copy for bounded integers.

Tail capacity is already clamped to the remaining byte budget. At insufficient
space the original byte-at-a-time code remains, preserving chunk transitions
and limit failures. No buffer address or alias escapes, no global helpers or
retained cache added, and no public API or policy change is proposed. Explicit
unchecked writes are reached only after the capacity guard and known byte
construction. Full row/model state transitions are unchanged.

Functional source gate: 43 tests / 764 assertions, including four new tests /
296 assertions. Original merged kernel is embedded as an independent test
oracle. Controls show quote/Unicode/20-digit integer guard reductions while
preserving complete bytes. All control characters, integer limits/fallback,
22 offsets around the 64KiB boundary for each burst kind, exact/truncated byte
budgets, producer stop order and independent outputs are covered. Candidate
whole-app/standalone and actual persistence qualification remain pending.

Two short interleaved rich untyped component screens, eight samples per arm:

| Arm | First p50 ms | Second p50 ms |
|---|---:|---:|
| Original | 131.08 | 142.35 |
| Escape pairs | 127.28 | 142.34 |
| Pairs + Unicode | 125.02 | 145.13 |
| All, including digits | 127.15 | 140.94 |

All completed bytes match; allocation is effectively unchanged. These are
modest/inconsistent differences, not a qualified throughput or tail win.
No exporter repin/default change or PR has been made. Candidate source is
preserved for later release-build comparisons rather than claimed as a win.
The second screen includes a broken-backend constructor control to test that
the private experimental backend substitution is actually observed.

The separate factorized cost-ceiling screen uses the ORIGINAL kernel and
precomputes fixture state outside timing. Eight samples/arm, identical bytes:

| Work precomputed | p50 ms | Estimated bytes/row |
|---|---:|---:|
| None | 148.21 | 9582.6 |
| Normalized attrs | 139.94 | 6645.6 |
| Nested text | 127.29 | 8261.4 |
| Attrs + nested text | 122.81 | 5328.3 |
| Complete projected rows | 79.07 | 3439.6 |

These are deliberately unrealistic retained fixtures, NOT production caches.
Do not subtract independent medians as exact exclusive stage timings. They
indicate substantial row-preparation cost as well as outer byte writing.
Typed projection, queueing, native insertion, WAL and publication are excluded.

Next priority is source-vs-release qualification of a complete public-API
SDK/exporter/Durable app. Jolt0.8.23 documents direct linking/whole-program
inference for release builds. Existing component scripts call private Vars;
do not reuse those probes in a release comparison where they could inhibit
optimization. No release performance benefit is assumed without measurement.
