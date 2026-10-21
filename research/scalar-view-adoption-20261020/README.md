# Published-stack adoption

SDK PR68 merged into `perf/default-scalar-options-20261020`, not SDK main:
`6cd57c86e0a93fc56af6cb10ee433b178b289e3b`. Both exact-head checks passed.
Parents5aff604 +2a80b02 and tree4251cbb match the tested GitHub merge candidate.
The SDK implementation remains d11752a; target integration changed only CI/deps.

Exporter default SDK now selects that merged consumer commit. The optional
stock-codec alias selects published component68ee1ca, the same codec implementation
already tested/reviewed as dbbe399. No local-root dependency is needed to enable
the exporter option. Default feature flags remain false. Crypto5effcc8,
driver3314b79 and JSON0f51b99 are unchanged; this is not upstream-only.

Current exact dependency/resource gates were updated, not historical receipts.
The new published view gate checks both SDK and codec source coordinates and
rejects nil/false as evidence of successful view selection. The older assertion
that a result was merely "not a map" could mistake a decline for a view; other
SDK byte-equivalence checks covered it, but an exporter fallback could hide a
never-selected optimization. The explicit truthy-result assertion closes that
consumer-oracle gap without changing runtime behavior.

## Local qualification

- Actual published SDK+codec view gate:3tests/19assertions.
- Existing published library/consumer regression:71/553.
- Existing inferred-shape gate against new exact pins:4/15.
- Actual dependency-resolution controls:61, including wrong/old-coordinate
  rejection. Child processes use the same explicit stock0.8.23 executable.
- Small mixed-signal Durable native gate: separate writer/fresh reader for
  raw-ticks and auto/ISO,12 physical rows each. Full fields/multiplicities checked;
  spans still use owned statements, logs/metrics remain configured String SQL.
  Dynamic event attribute counts force distinct roots after shape learning,
  rather than only shared-root cache hits. All four processes terminated0.

These small native checks exercise the published stack, NOT new throughput,
live SDK queue, Oscope/Langfuse or S3 qualification. Earlier local-root350k-row
recovery and allocation results remain separate, unchanged historical evidence.
The additional two synthetic stores total3132KiB and were retired after complete
receipt validation and copied-file comparisons; no expected telemetry/data
objects or binary was retained. No unchanged exhaustive model run was added.

## Next larger performance question

The current value-slot view lowers allocation but only modestly changes time.
Preserve it while evaluating compiled field/key emission and bounded ordered
preparation/publication overlap. Do not promote independent phase quantiles into
exclusive cost accounting or claim the25k/20k goal achieved. New asynchronous
lifecycle/model logic requires the appropriate approved scope first.

The GLM source-only overlap audit timed out after360s, session
ses_ea3b772dcffelpfotVJ5VF5YPj. Exported metadata contains only the user message,
no assistant/tool response; it yielded NO evidence and was not blindly retried.
Main independently confirmed existing chDB stream calls are already marked
blocking in driver3314's ABI manifest; do not propose adding a missing flag.
Actual stock runtime carrier/GC overlap remains to be established.
