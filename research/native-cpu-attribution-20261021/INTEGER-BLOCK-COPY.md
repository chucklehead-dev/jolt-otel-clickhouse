# Integer output block copy: not adopted

The candidate replaces per-digit `byte!` output with chunked `bytevector-copy!`
from the existing private decimal scratch. Formatting, values and byte budgets
are unchanged. It is an experiment, NOT a promoted performance improvement.

Baseline: `581b50259e37b79ee51f7b1a881fad60150ec22f`.
Candidate JSON source Git blob: `37d91e6b70f79f730df9ab4639fda10a9ed89b12`.
Candidate test Git blob: `3980d4d69f16b2c8eb38deb5a4ac66aad30d581f`.

Stock Jolt 0.8.23 / required Chez 10.4.1 wrapper. Independent library source
gate: **43 tests / 652 assertions, zero failures/errors**, exit 0. Added cases
force integers across the 64KiB batch output boundary and test exact output
budgets/one-byte-short rejection, Int64 extrema, UInt64 maximum and bignum
fallback. This was a source gate, not another native standalone build.

Uninstrumented full rich source pipeline, 5,000 spans/batch, fixed ABBA order,
16 samples/arm. The old codec is compiled in a separate namespace from the exact
baseline source. Public codec function roots are temporarily bound ONLY while
constructing its exporter context; the original roots are restored before any
encoding/timing. SDK policy roots are not replaced. Both context factories
capture their own codec and opaque view boxes. This is a private source
component comparison, NOT release optimization or an end-to-end Durable test.

| Arm | p50 / p90 / p95 / p99 ms | Median estimated managed B/row |
| --- | --- | ---: |
| Baseline | 271.90 / 381.94 / 426.96 / 426.96 | 8870.61 |
| Candidate | 271.01 / 403.78 / 446.69 / 446.69 | 8870.88 |

Median differs only ~0.33%; observed p99/max is ~4.62% worse. No useful gain is
demonstrated. Large time drift is preserved in the raw samples, not filtered or
retried. Do not compare these absolute times against an earlier run as an
optimization result. Allocation estimates are non-atomic live-heap plus
reclaimed-byte deltas, not allocation-site/native/RSS measurements.

All 8,647,784 output bytes agree on every sample. Both arms observe changed
timestamp input. No timing callbacks, SDK queue, native insertion, WAL or I/O
were exercised. No model/ACK/default/dependency pin was changed. No native
performance sweep or PR is warranted by this result.

The experiment stays on `perf/codec-integer-block-copy-20261021`; do not merge
its candidate kernel merely because its functional tests pass. The next useful
larger slice is direct row/projection construction, not further decimal-output
bookkeeping. Preserve the earlier row-view negative result: removing only the
row vector was insufficient; any new prototype must also remove repeated
projection dispatch/intermediate structures, with policy ordering intact.

Commands from the exporter root, using the official stock executable through
`/home/chuck/ai-src/tools/jolt-with-chez-10.4.1`:

```sh
jolt -Srepro -Sdeps '{:paths ["incubator/codec/src" "incubator/codec/test"]}' -m jolt.codec.standalone-smoke-test
jolt -Srepro -Sdeps '{:paths ["src" "test" "incubator/codec/src" "research/scalar-view-exporter-20261020/app/src"]}' research/native-cpu-attribution-20261021/integer-block-copy-screen.clj research/native-cpu-attribution-20261021/results/integer-block-copy-screen.edn
```

Both processes are terminal, exit 0. Only small source/EDN evidence was created;
no synthetic native store, app build or large profiler artifact needs cleanup.
