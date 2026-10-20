# Bounded string-literal reuse experiment

**Decision: not adopted.** Production sources are unchanged from merged 7b6ac8e.
The tested implementation is preserved as `candidate.patch`; its three tests /
35 assertions are preserved in `candidate_test.clj`, outside default test
discovery. `git apply --check research/string-fragments-20261020/candidate.patch`
passes from this worktree root. Apply that patch before reproducing this
experiment; these scripts are not benchmarks of the unchanged library.

Hypothesis: distinct maps with repeated immutable String identities still pay
repeated JSON escaping costs after scalar schema plans remove HAMT rebuilds.
Copy a complete previously encoded literal instead. This is an optional library
encoder path, not a Jolt/data.json patch or a changed exporter default.

`:string-fragment-cache? true` requires `:flat-map-cache? true`. It affects only
the outer record encoder; scalar and nested encoders are unchanged. A per-call
weak identity table admits at most 64 strings, retaining at most 128KiB of
encoded bytes, at most 4KiB per complete literal. Inputs shorter than 16
characters bypass lookup. Table overhead is additional; these are not RSS caps.
Encoded values may contain telemetry; they remain private, bounded, synchronous
and are not diagnostic output. No caller-supplied JSON or mutable alias is
accepted. Input strings must remain immutable throughout encoding.

Misses use the existing encoder once; only a complete literal within the same
output chunk can be captured. Hits recheck the current byte budget. Original
value dispatch checks the current depth before either path. The map-fragment
depth guard must not be used for scalar literals. This experiment does not
change policy admission, numeric conversion, snapshot ownership or WAL ordering.

Focused gate: `jolt.codec.string-fragment-test`, three tests / 35 assertions.
Existing flat-map mechanism gate: six tests / 71 assertions.
Run on official stock Jolt 0.8.23 through the workspace Chez 10.4.1 wrapper.

`run-durable.sh NEW_ABSOLUTE_OUTPUT_DIR` runs matched off/on/on/off arms with
shape plans enabled in every arm, equal but distinct rich attribute roots,
5,000 rows/callback, three warmups and 32 measured callbacks. The option is
injected into the library constructor only for this experiment, not installed
as a public exporter feature. Each arm uses local candidate sources (not a
published library revision); independent recovery uses the older reader stack.
It is a deterministic SDK-shaped fixture, not live application capture or S3
qualification. Do not claim tail qualification from only 32 callbacks.

Current run: `/home/chuck/ai-src/evidence/string-fragments-abba-20261020`.
All four arms independently recovered 175,000 rows each (700,000 total), with
zero failed/dropped spans and empty queues. Exact owned stores were retired
after validation; only small reports remain. Measured window rates were
11,796.8 / 12,669.7 / 9,538.9 / 7,266.8 rows/s (off/on/on/off).

**Do not promote the pooled +21.0% arithmetic to a causal improvement.** Control
throughput fell 38.4% across the run, while allocation remained about 18.5KB/row.
Process CPU ticks also increased substantially. The source of this drift is
not diagnosed; neither GC alone nor external load is established as its cause.
This is correctness evidence but insufficient stable performance evidence for
enabling an exporter feature or changing a default. The first favorable pair
must not be selected as the result. `summarize.clj` reports all four arms,
including p50/p90/p95/p99 and inverse-callback throughput.

`component-screen.clj` additionally isolates the rich untyped projection/encode
phase with short alternating off/on/on/off and on/off/off/on rounds in one
process. It compares complete output bytes after every call, outside the timed
interval. This excludes typed projection, JDBC, queueing and publication; even
a stable component win is not a Durable throughput claim.

The component screen passed byte equivalence on every invocation. Its sixteen
samples/arm gave p50/p90/p95/p99 milliseconds:

| Arm | p50 | p90 | p95 | p99 |
|---|---:|---:|---:|---:|
| off | 289.55 | 347.70 | 376.39 | 376.39 |
| on | 290.72 | 348.13 | 349.77 | 349.77 |

There is no demonstrated median benefit. Tail changes from sixteen samples
are not sufficient evidence to justify new retention, API surface or code.
The restored maintained library passed its original 37 tests / 435 assertions.
Next target: eliminate intermediate normalized map/row materialization through
an explicit SDK-owned projection/encoder bridge, rather than more string caches.
That needs an actual allocation/cost attribution and a compatible API; it must
not silently change the existing converter's fresh plain-map return contract.

The initial component invocation rejected before timing because the imported
fixture defaults to 512 rows. The corrected invocation explicitly sets its
fixture size to 5,000; no incomplete invocation contributes timing samples.
For future runs the runner also hashes its own script and arm source. The
completed run's hash receipt covers production kernels and supporting fixtures,
but omitted those two harness files; do not claim otherwise.
