# Private tail-length type-check screen: not adopted

After the guarded byte-store improvement, test whether avoiding a repeated
bytevector type check on the PRIVATE tail length helps the clean String and
encoded-nested-text loops. Only two `bytevector-length` calls change to the
`#3%` primitive. Cursor/capacity and output-budget checks are unchanged. Tail
is always the private allocated bytevector or qualified1024-byte shared scratch;
no public array/index is accepted. No global unsafe compiler mode is selected.

Baseline product950c59ded5343ed58f35fdb7f100f8e459795255 already has guarded fast
byte stores and checked numeric formatter output. Do not compare this result
against the older checked-store baseline or add it to the PR158 effect size.
Same official stock Jolt0.8.23/required Chez10.4.1, actual SDK per-payload
converter/cache, typed projection, nested and outer JSON. Existing independent
library source gate43tests/645assertions passes, zero failures/errors.

One-process source ABBA16/arm, no per-row observers, same rich5k fixture:

| Arm | p50 / p90 / p95 / p99 ms | Estimated managed B/row |
| --- | --- | ---: |
| Current guarded-store baseline | 164.94 / 204.48 / 286.24 / 286.24 | 8870.60 |
| Private tail length | 164.06 / 216.44 / 227.43 / 227.43 | 8870.60 |

All8,647,784 output bytes equal after every sample; changed-timestamp controls
observe changes in both arms. Median differs only~0.53%, p90 is worse, no
allocation change. Sample max is lower but does not establish a tail effect or
meaningful median throughput gain. Preserve drift/all samples; no favorable
repeat or native sweep is warranted. This does NOT prove the compiler already
eliminates those type checks or establish any architectural performance ceiling.

The kernel change remains ONLY on research branch
`perf/private-tail-length-20261021`, not the reviewed product PR158. No new
compiler/SDK/data.json/API/pin/default/format/model/confirmation change is adopted.
No native store/build was created. Local source run65104 and test88596 terminal0;
remote158CI was concurrent, but no local heavy build/reviewer/native run.

The script uses a separately compiled baseline codec resource from950c59d and
temporary codec function bindings only while constructing contexts, not in
timed calls. This is a private SOURCE component comparison, not release AOT,
actual Durable, live SDK queue, S3 or population-tail qualification.
