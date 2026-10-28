# Guarded cursor arithmetic

This checks one claim: the private encoder's newly unchecked increments cannot
overflow a fixnum or advance past a valid end position when their source guards
hold. Equal-to-length is a valid loop end; it is not permission to access that
index. This is not a proof of wire semantics, alias isolation, storage, concurrency
or persistence. Runtime byte oracles and lifecycle tests remain separate evidence.

The source is `src/jolt/codec/json.ss`. `string!` and `encoded-text!` start at
input index zero, stop at input length, and reload output state after an escape
or buffer change. A clean iteration has `i<n` and `cursor<length(tail)`;
`i+1` and `cursor+1` can reach but cannot pass those lengths. The escaped branch
advances only the input index; `escaped-char!` updates output through guarded
byte writes. `byte!` and `formatted-number!` call `ensure-space!` before their
store and `used+1`. The formatted-number store still checks the byte value.

`ensure-space!` either throws on an exhausted budget or returns a positive
capacity with room for the next byte. Initial capacity is positive unless the
budget is zero (which throws before a store). Later growth starts from a positive
capacity and positive remaining budget. Capacities are at most 65,536. Aggregate
budget arithmetic, allocation and growth stay checked; no global compiler
optimization level changes. Primitive String/bytevector lengths are fixnums;
the model includes conservative 32-bit and actual 64-bit Chez maxima. The local
64-bit runtime reports fixnum width61/max1,152,921,504,606,846,975.

The query derives `next_i`, `next_cursor` and the violation with equality.
Operation kinds distinguish input/output, output-only and input-only advances.
The input-guard switch is fixed true in the real query and false only in the
explicit mutation. Prior loop bounds and the `ensure-space!` postcondition are
source-audited premises, not a complete formalization of the encoder/runtime.

Chiasmus lint/verification finds no counterexample under these bounds (UNSAT).
Removing the input guard is SAT at `i=n=536870911`, next index536870912 and
violation=true. Removing the output guard is SAT at cursor1/capacity1, next2 and
violation=true. A one-byte safe control is UNSAT. The inclusive-boundary control
is SAT with next index equal to input length and next cursor equal to capacity,
violation=false. Named cores and witnesses are in `replay-20261027.json`.

Run `bash scripts/check-guarded-cursors.sh` from this folder (requires Z3 and
Python3). It checks the exact kernel digest and replays the same query/control
transformations. A digest is a drift alarm: changing it requires revisiting the
source mapping, controls and runtime tests. This fast arithmetic gate does not
justify rerunning unrelated exhaustive models.
