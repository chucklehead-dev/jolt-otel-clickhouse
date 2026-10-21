# Private byte-store specialization: candidate, not yet adopted

Product base exporter a6cbf939; research parent
581b50259e37b79ee51f7b1a881fad60150ec22f. Official stock Jolt 0.8.23/Chez10.4.1.
No compiler/data.json/SDK patch, global unsafe optimize-level, format, policy,
acknowledgement or concurrency change. Selected ecosystem dependencies still
include forks; stock-runtime qualification is not a fork-free whole application.

The fast helper `byte!` uses `#3%bytevector-u8-set!` AFTER the existing
`ensure-space!` capacity/output-budget check. The clean-string loop already uses
this private-store primitive with its own bound. This avoids redundant store
type/bounds checks on punctuation, escapes, digits and encoded scalar bytes.
No arbitrary public array, index, byte or callback receives this helper.
Runtime float/bignum formatter output takes a separate CHECKED store path,
preserving prior catchable rejection of an out-of-byte formatter character.

## Bounds argument to review

- `used` starts at zero; `tail` is a private bytevector. Successful stores
  advance `used` by one, never beyond capacity. Existing bulk-copy/string loops
  stop at capacity and publish their bounded cursor before calling this helper.
- `ensure-space!` either returns with `used < length(tail)` or raises before
  any store. At a full chunk, a full output budget raises; otherwise a positive
  remaining budget and prior positive chunk capacity produce a positive next
  chunk and reset `used` to zero. An initial zero-capacity buffer has a zero
  budget and raises, not a zero-capacity write.
- Every fast-helper argument is unsigned: fixed JSON punctuation; hex/decimal
  ASCII digits; validated ASCII prefix/literal text; bytevector reads; or
  bounded UTF scalar bit arithmetic. No generic input integer is passed directly
  to the fast store. Formatter text stays on checked stores; no local byte-range
  assumption about those formatter results is needed for memory access.
- Inputs and private contexts obey existing immutability/nonconcurrency
  contracts. This is local source reasoning, NOT a machine-checked proof of all
  native/runtime behavior. Do not blanket-enable unsafe compilation.

## Source gate and component screen

Independent final library gate **43 tests / 645 assertions**, zero failures/errors.
Added tests cover zero/exact/one-byte-short budgets with nil/booleans, 64-bit
extrema, finite/subnormal floats, all ASCII controls/delimiters and Unicode
expansion. Lexical formatter mutants returning out-of-byte Unicode fail at the
CHECKED store for both float/bignum paths; a valid formatter control succeeds
so a compile/setup error cannot masquerade as rejection. Existing chunk,
depth, reentry, cached fragments, records, row-view and ownership tests pass.
This is source execution; native qualification is pending.

One-process baseline/candidate/candidate/baseline, 16 samples/arm, rich 5,000
spans with real SDK conversion/cache, typed projection, nested and outer JSON.
The baseline codec is separately compiled from the exact parent resource;
temporary public codec bindings are ONLY used at context construction. No
policy replacements or per-row observers occur in timed calls. All 8,647,784
output bytes agree, and timestamp input changes are observed in both arms.

| Arm | p50 / p90 / p95 / p99 ms | Median estimated managed B/row |
| --- | --- | ---: |
| Baseline | 208.59 / 248.72 / 301.94 / 301.94 | 8870.58 |
| Candidate | 177.43 / 226.00 / 273.56 / 273.56 | 8870.60 |

This initial screen belongs to commit3d2e0bb, before the review-driven checked
formatter lane was added. Those formatter paths are not present in the rich
fixture, but a compiler-layout effect is not ruled out; do not label these
timings measured final-head results. Final native qualification is separate.
Observed p50 ~14.9% lower and sample p99/max ~9.4% lower, no allocation change.
This is a promising SOURCE component result, not confirmed Durable throughput,
release optimizer or population-tail qualification. Do not compare absolute
times against older experiments or repeat unchanged runs for a favorable number.

## Native qualification plan (declared before runs)

Initial candidate build9083a340bae36c48a9e454c87e39875907edf2d08d26c3569103fae0dabebf40
was superseded BEFORE ANY native timing when review identified the formatter
range assumption. Final kernel SHA256 is
169e8359b597bb005f0d21e87d0c7a347690441114d060117137447591a5aa2b.
Record the rebuilt executable separately; no favorable native repeat is involved.

Reuse the verified archived baseline app SHA256
702dda478703aae460a28c8ac8276485d16e33b175357331deb13a42831c50eb,
copied to an owned ext4 path, and build the candidate using the unchanged
`research/fused-typed-field-slots-20261021/app` public application/dependency
pins. Both invoke `perf.field-slot-pipeline` with `fused`, the same constructor
wrapper (no per-row probes). Source hashes for exporter and both app namespaces
match the baseline manifest; only codec JSON source differs. This is TWO
binaries, not a same-binary switch. Record selected source, classpath and binary
hashes; selected hashes are not complete transitive compile attestation.

Order A1 baseline / B1 candidate / B2 candidate / A2 baseline, each3warmups +
32measured5k confirmed callbacks, fresh local store, native26.9.0, same machine.
One native workload at a time, no reviewer/timing overlap. Independent older
reader0af1008/JSON3adc8c5d after EACH writer must check every physical field,
types/status/exact timestamps/35copies/tie order before any store cleanup.
Use the existing validation/mutation controls; retain all measurements including
drift. No S3, SDK queue, Langfuse, Oscope or target qualification is inferred.
Archive/cmp small receipts and retire each exact synthetic store after gates,
limiting simultaneous native store use to one. Keep build/binaries only until
reports/source/hash gates complete; do not upload native stores/binaries.
