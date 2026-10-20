# Published dependencies on stock Jolt

This slice replaces SDK/driver local-root qualification with ordinary published
Git pins and makes the independent codec subproject directly selectable. No
concurrent publication, default codec selection, new repository, compiler fork
or model logic is introduced. The bounded overlap model still awaits explicit
Revision B approval on its separate branch.

## Selected stack

- Official Jolt 0.8.23, binary SHA256
  `7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94`.
- Local commands use the required Chez 10.4.1 wrapper.
- Driver `3314b79e8a234fafdf5231439a0323c878a40040`.
- SDK `26186ce79941e84e0cc67eb148dd8dd43b4e1880`.
- Independent codec component `05fa8b86f38b0d1872322822fe68bf729b483613`,
  `:deps/root "incubator/codec"` in the existing exporter repository.
- Root crypto `5effcc89` and data.json `0f51b991` remain unchanged.

The stack still uses ecosystem forks (including SDK, data.json and db). This
does not establish an upstream-only dependency graph or BB/JVM qualification.

## Independent component, not an exporter dependency in disguise

The isolated consumer's `consumer-deps.edn` selects only the codec subproject.
Its actual `-Stree` contains ONE dependency. A fresh process folds an ordinary
map and encodes JSON, then checks that exporter and SDK namespaces are absent.
The source path is the Git checkout's `incubator/codec/src`, not a local-root
override. This is a normal Git dependency usable by applications now; aliases
from an exporter Git dependency still do not propagate to the application.

The exporter `:stock-span-codec` alias selects that same published component;
`:stock-span-codec-test` adds ONLY the local test directory. Its runner checks
four real resource paths against the exact owner/repo/SHA/subproject suffix.
Positive/prior-SHA/wrong-repo/wrong-root/extra-suffix controls are nonvacuous;
older-code rejection is an exact-pin claim, not a claim of ABI incompatibility.
A REAL second process adding the checkout-local codec alias is rejected with
exit 1 before tests; published-only selection passes 39 tests / 267 assertions.

## Functional qualification

All local commands completed with terminal exit 0, except the intentional
shadowing negative controls, which completed with expected exit 1:

- New published SDK/driver pins, checkout-local codec: **39 / 267**.
- Published SDK/driver AND Git-subproject codec: **39 / 267**, with final
  positive/negative resource-oracle controls.
- Direct compact-layout aggregate: **52 / 433**.
- Actual ordinary compact native insertion/readback on chDB 26.7.3: **2 / 21**.
- Canonical `-M:test` suite: terminal 0 / `all checks passed`, including actual
  loopback typed-log ingestion, schema/query tests, native readback, Hegel
  acknowledgement/lifecycle histories and existing ITF replay controls.
  This does not mean every SDK/Oscope namespace or model was checked.
- Existing singleton-event regression gate: **3 / 34**. The newer canonical
  main commit `0830f303` adds that optimization; this performance stack already
  contains it, plus its explicit attribute-converter extension. Do not drop
  this previously integrated gain while preparing a final main integration.
- Existing two-process typed Durable gate, root-pin mode, chDB 26.9.0 + raw
  ticks: writer and separate reader completed successfully; typed spans, logs,
  gauges and sums recovered. Exact SDK/driver provider revisions were checked,
  as were the binary/wrapper/native/header hashes. No SDK/driver local roots.

The full suite printed success before its process actually exited; terminal 0
was observed before starting the next native workload. No process was killed
or replaced to manufacture that result. No model/exhaustive run or new
throughput measurement was performed. Earlier throughput targets remain unmet.

The native gate's small synthetic store was removed only after terminal writer
and reader results and copied/cmp-verified receipts. The independent consumer's
temporary project was likewise retired after its command completed. No
database, native binary or compiler intermediate is retained in this packet.

## Review and hosted gate

Claude source review completed, followed by a completed narrow negative-oracle
review. Codex independently executed all gates above. The initial review
incorrectly suggested the component must be on main; Git fetchability from the
published branch suffices, and the independent consumer verified it. Review
does not independently attest to runtime logs it was not permitted to inspect.

The stock workflow retains isolated source checks and its actual standalone
build, then adds the published-component gate. Path triggers now include the
root pins and exporter source; its temporary build outputs remain covered by
the existing EXIT trap. Hosted execution must be observed separately before
calling the modified workflow green. Full Oscope repinning, embedded/standalone
startup, real socket span delivery and Oscope queries are still downstream
integration gates, not implied by this exporter qualification.
