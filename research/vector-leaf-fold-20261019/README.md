# Stock-Jolt vector leaf-block experiment

Base: `60e1f69bbb06766c36ca9ef0c34af51a97e69c34`.
Official Jolt 0.8.23 executable SHA256:
`7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94`.
All local commands use the workspace Chez 10.4.1 wrapper. No compiler fork,
data.json modification, production option change, or new concurrency is used.

## Change

The traversal kernel previously called `pvec-nth!` per element. It now uses
the runtime's `pv-leaf-for` once per leaf and visits that leaf's elements
directly. The resolver handles sliced/relaxed trees and nonzero offsets;
hard-coding 32-aligned leaves would not be correct. Traversal still requires
immutable inputs, preserves order and stops at an exception. No intermediate
sequence, entry, cursor object or flattened vector is introduced.

Private runtime procedures and collection layouts remain trusted,
version-sensitive dependencies. This is a library-contained optimization,
not a stable upstream ABI. The baseline already avoided cursor allocation;
the change targets repeated lookup work, not allocation elimination.

## Measurements

`screen.clj` constructs two lexical traversal/JSON backends in one process.
The baseline replaces only `ffc-vector-fold` with its exact parent body;
the JSON implementation is identical. Both rich arms enable the same SDK
normalization cache. ABBA order, warmups, raw counters, full-output SHA256
checks outside timing and changed-event controls are retained in `result.edn`.
No external helper source or old benchmark result is loaded by this script.

Native fold microbenchmarks visit approximately one million elements per
observation, with 16 observations per arm and shape:

| Vector size | Indexed p50 | Leaf-block p50 |
| --- | ---: | ---: |
| 24 | 7.71ms | 3.90ms |
| 1,057 | 19.82ms | 3.78ms |
| 5,000 | 19.67ms | 3.80ms |
| 32,769 | 19.54ms | 3.72ms |

The representative rich 5,000-row projection/nested-JSON/outer-encoding screen
has 32 observations per arm:

| Measurement | Indexed | Leaf-block |
| --- | ---: | ---: |
| p50 | 133.27ms | 136.84ms |
| p90 | 180.91ms | 172.65ms |
| p95 | 195.75ms | 196.07ms |
| p99 sample quantile | 250.71ms | 197.85ms |
| Median estimated allocation/row | 11,356B | 11,357B |

**No overall rich-encoding speedup or allocation improvement is established.**
The microbenchmark improvement is not a Durable/SDK throughput result. The
p99 above is the largest of only 32 observations, not a qualified population
tail. No native insertion, WAL, S3 or recovery work is measured here. This
does not qualify the 25k p50 / 20k p99 confirmed-row objective.

The result makes projection/normalization/nested encoding the next place to
look for a larger gain. Do not rerun this unchanged screen just to obtain a
favorable median, or discard the neutral rich result when quoting the micro.

## Tests and reproduction

The source gate passes **20 tests / 199 assertions**. It covers boundary and
deep vectors, metadata, slices, assoc/conj/pop, nil/false, nested calls, JSON
limits/ownership and callback failures. A separate lexical mechanism test
requires 34 resolutions for 1,057 elements, one for 24 and zero for empty.
It is intentionally separate from compatibility tests of the older published
codec pin; those must not demand a new mechanism from the old revision.

From the repository root:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0 \
  /home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt \
  -A:test:experimental-span-codec-test \
  research/vector-leaf-fold-20261019/screen.clj /absolute/path/result.edn
```

Keep only the small report and logs. This experiment creates no database or
native store. Standalone build outputs belong in a task-owned temporary
directory and must be removed after terminal execution and receipt retention.
