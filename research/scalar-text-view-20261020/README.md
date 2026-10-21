# Opaque scalar-text view: normalization/encoding mechanism prototype

Base exporter `f31107c7b984fcf7c8f966de0bdfe2cbb4dfbfb3`, official stock
Jolt0.8.23, Chez10.4.1. No compiler/data.json patch, SDK API change, exporter
adoption/default/pin or Durable/concurrency change. This remains unintegrated
research; an SDK payload-converter cannot return this view instead of the fresh
plain map it promises.

The existing fixed-key kernel now has an internal output strategy. The old
factory selects the original String-valued pmap output. The new trusted view
factory retains the same original policy anchors, scalar/shape validation,
bounded learning and pre/post admission checks, but boxes the template and fresh
raw value slots. The codec traverses template ordinals, not a freshly copied
HAMT. Fixnums are emitted directly as JSON STRING digits using existing integer
scratch, not temporary formatted Strings. Booleans remain string values too.
Ordinary Strings follow the existing escape/Unicode writer.

Templates retain only bounded schema keys/indexes. Each view owns fresh slots,
not a source map/tree or reused scratch loan. Ordinary Strings must remain
immutable. This is low-allocation, not zero-allocation. These native bridges
remain trusted/version-sensitive: the SDK must supply its ORIGINAL anchors and
control cache/admission/fallback. The constructor is not an application escape
hatch or general raw-JSON API.

## Evidence so far

Source and actual neutral-directory Linux standalone: **43 tests /596
assertions**, including4 new tests /128 assertions. Exact bytes compared with
the original plain-map representation at0/1/8/9/17/32/128 keys, reverse ordering,
collision-shaped keys, quoting/Unicode, negative numbers/booleans, independent
views/output mutation, exact/truncated limits, depth boundaries and producer
stop. Existing policy changes, unsupported input and bounds still decline.

Non-vacuity control instruments actual learned lookup: old output constructs
one pmap and five numeric Strings for the17-field fixture; view output constructs
zero of each. Both still allocate their own value slots. Shared existing tests
continue protecting malformed trees, custom ops, cache bounds and vector views.

Matched learned-plan+byte-encoding screen,5,000 distinct17-field sorted maps,
16 interleaved samples/arm, full output equality and changed-value rejection:

| Arm | p50 ms | p90 ms | p95/p99 ms | Median estimated B/map |
|---|---:|---:|---:|---:|
| Original plan, current encoder |22.38|38.55|43.71|2169|
| Current plain-map strategy |22.72|31.00|36.35|2169|
| Opaque view |21.66|27.38|30.62|1162|

About1,007B/map (~46%) less managed allocation in this component; median time
only modestly lower (~3% vs original). This is **not** a demonstrated end-to-end
throughput/tail gain. Original/current plans share the current candidate encoder;
this does not isolate its added view-dispatch branch from the old encoder.
The original source comes from the unchanged f31107c component worktree named
in the reproduction command. The first two-arm screen is also preserved; its
23.13→22.05ms /2169→1162B result is not an additional independent production win.

Admission/inference, SDK root cache, real span projection, queueing, native
insertion, WAL and publication are excluded. No native I/O or store was created.
p95/p99 here are sample maxima of16, not population-tail qualification. Counters
are separate coarse snapshots and exclude native allocations. Targets remain
unmet; do not extrapolate this into25k/20k or Rust-relative performance.

## Reproduction and next step

From `incubator/codec/`, with the mandatory workspace Chez wrapper and official
stock executable, run `-Srepro -Sdeps '{:paths ["src" "test"]}' -m
jolt.codec.standalone-smoke-test`. From `packaging/`, use the existing documented
default build gate. Keep `build` first, run the produced binary from a neutral
owned temp directory, then preserve scalar logs/hashes and remove that exact
build directory after terminal successful execution.

From `incubator/codec/`, run:

```sh
jolt -Srepro ../../research/scalar-text-view-20261020/screen.clj /absolute/result.edn /absolute/unchanged-f311-component/src/jolt/codec/scalar_text
```

Next implement a SEPARATE SDK-owned handoff that preserves root-cache-first /
plain-map-first behavior and original fallback/effect order, not a monkeypatch
or replacement return type. Compare the cumulative rich exporter stack and old
encoder control, then actual Durable independent recovery before adoption.
The ~25ms earlier frozen-normalization ceiling was never enough alone for the
overall target. Larger projection fusion and bounded ordered overlap remain
separate possible work, not benefits claimed by this small mechanism.

Claude read-only review of exact implementation commit `dbbe399`: APPROVE, no
blockers. The `text` helper's `(view-box value)` is a Scheme cond clause: test
the bare `view-box` flag, then return raw `value`; it does not call the box at
that point. Actual boxing occurs only after all admission/policy checks. See
`claude-review.txt` and `review-resolution.md`. Source/standalone/count controls
were independently run by Codex, not delegated.
