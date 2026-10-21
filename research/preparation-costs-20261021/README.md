# Preparation attribution, not throughput qualification

Research branch based on canonical exporter
`e5190e8573328ddc03912da227e5f48d00bab6d8`; original throughput graph/binary
remain unchanged. No performance optimization has been adopted here.

## Result

Official Jolt 0.8.23 / Chez 10.4.1 / chDB 26.9.0; default release build,
two warmups plus 16 measured 5,000-row batches of the existing rich typed
fixture. The real adapter reserves, prepares, aborts **before submission**,
releases and retires each batch. No SDK queue, overlapping publisher workload,
network or native insert timing is measured. Final query confirms zero spans.

| Phase | p50 ms | p90 ms | p95 / p99 ms | CPU total ms | Estimated B/row |
|---|---:|---:|---:|---:|---:|
| Projection + JSON | 151.89 | 190.09 | 210.23 | 2468.62 | 8515.63 |
| Private SQL snapshot | 11.47 | 22.46 | 26.20 | 221.67 | 1730.08 |
| Cached WAL | 31.33 | 79.30 | 84.10 | 670.36 | 2123.57 |
| Whole prepare callback | 199.76 | 264.79 | 273.30 | 3367.00 | 12380.58 |

Projection/JSON accounts for ~73% of total measured preparer CPU and ~69%
of the allocation estimate. WAL remains a meaningful contributor, including
slow samples; GC alone does not explain every variation. This is one diagnostic
run, not an architectural limit or a causal attribution of prior ABBA tails.
P95 and p99 are the maximum of only 16 samples, **not population-tail estimates**.
Do not divide 5k by preparation time and call it confirmed throughput.

CPU is current OS-thread CPU; memory/GC readings are process-wide and each
counter is a separate, non-atomic snapshot. Allocation adds reclaimed GC bytes
to live-heap change; it is not retained memory or a per-thread allocation proof.
The total phase encloses its three child phases and their observer overhead:
parent/child timing, CPU, allocation and GC totals must not be summed together.

## Instrumentation and controls

The three coarse markers surround the actual codec invocation (including its
projection callbacks), immutable ASCII snapshot, and cached-WAL preparation.
**The temporary observer hook is NOT present in HEAD's product source.** It is
retained only as `release-marker.patch`, to apply manually in a disposable
research checkout; it must not accompany a product optimization. It uses
a dynamically bound observer and does not change driver admission/publication.
Samples contain fixed phase names, counts and numeric deltas only, never values,
SQL, rows, credentials or arbitrary function/exception returns.

Unit controls pass 2 tests/11 assertions: delegation calls once and preserves
return/error identity; missing, duplicate or wrong phases fail coverage. The
actual release run observed every phase exactly once per measured batch (16
of each), completed retirement, and confirmed no telemetry was published.
Raw scalar observations are preserved before qualification for failure diagnosis.

An initial release attempt used public Var redefinitions. It failed missing
phase observation (`:nonempty-samples`) and is **not valid timing evidence**.
Stock release builds [direct-link/infer functions; `--dev` preserves
redefinability](https://github.com/jolt-lang/jolt/blob/v0.8.23/README.md#compiling-to-a-binary).
This is not evidence of a compiler defect. The successful source-wrapper run
is retained separately, not substituted for release measurements. In-place
markers avoid using Var replacement to observe direct-linked calls.

Release binary SHA256:
`ebfbf83900a386fb7e16bf2b319e73aac755c0d7be95197ffec9f119f2a4297c`.
Compact raw samples and summary are under `results/`; source/build hashes and
logs are retained in the workspace evidence root. No persisted telemetry dump.

The measured marker source is commit `00f041d` (which still has the hook).
After review, HEAD restores product source exactly to the canonical base;
missing markers now reject before native/database acquisition. A subsequent research-only
cleanup correction puts abort/release/retirement in `finally` when preparation
throws or returns false; this is outside the timed callback. New controls cover
success, false and thrown preparation. It is not retroactively represented as
the measured binary, and the observer hook remains excluded from product work.

## Reproduce

From `app/`, select the explicit stock executable in `JOLT_BIN` and 26.9.0
library in `JOLT_CHDB_LIB`. All local Jolt commands use the pinned wrapper:

First, **in a disposable checkout only**, run from its repository root:

```sh
git apply research/preparation-costs-20261021/release-marker.patch
```

No app startup or test command applies this patch automatically. Without it,
the observer/coverage unit tests can run but the native diagnostic fails closed.
Then change to `research/preparation-costs-20261021/app`:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 env JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0 \
  "$JOLT_BIN" -Srepro -M:test
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 env JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0 \
  "$JOLT_BIN" build -m perf.preparation-costs -o /absolute/owned-run/app
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 env JOLT_CHDB_LIB="$JOLT_CHDB_LIB" \
  /absolute/owned-run/app /absolute/fresh-data-root release-diagnostic 5000 16
```

## Next candidate

[OTel #73](https://github.com/casselc/otel/issues/73) tracks ordinary HAMT
scalar-text views. The existing SDK projector tries its copying plain-map
converter before shape lookup, and learned view plans support sorted maps.
The rich fixture uses ordinary maps for span attributes. Extend only through
SDK-owned policy anchors and the explicit codec factory seam, preserving
admission, ordering/collisions, bounded key retention and fresh value slots.
This subpath's individual contribution and any gain remain to be measured.
Allocation reduction alone is not adoption evidence: require preparation timing
and matched full SDK/Durable confirmed-throughput plus independent recovery.
