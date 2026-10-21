## Summary

Add explicit experimental helpers for JSON stored inside a JSON STRING field:

- `prepare-nested-literal` fuses inner JSON encoding with outer STRING escaping.
- `with-nested-literal-writer` composes that sink with a separate bounded,
  payload-local fragment cache.
- Copy only privately produced complete literals into the outer output. This
  is not caller-supplied raw JSON or a data.json/global-runtime replacement.

The motivation is the existing nested-text path: encode inner JSON, then scan
those bytes again when escaping the outer String. Uncached fusion alone did
not beat our existing cache, so this preserves and composes the cache work.
The component remains independently usable on stock Jolt0.8.23, without OTel,
storage, compiler-fork or data.json dependencies of its own.

Existing writer APIs, exporter options/defaults and consumer pins are unchanged.
This draft does NOT yet wire the new helpers into the exporter or claim the
overall Durable performance target is achieved. Changelog/docs included.

## Correctness and negative controls

Keep ordinary STRING semantics, logical inner byte/depth limits and independent
outer byte limits. Escaped fragments carry original logical byte counts;
hits re-check limits/depth. Raw and escaped tables never mix, and scope cleanup
runs on return/throw. Startup probes exercise both fused capabilities.

Red/green controls include raw-splice rejection, one-byte-under-limit rejection,
cached hits under a reduced logical budget, scope expiry, nested literal-as-
value escaping, output mutation isolation and failure before the next row.
Exact bytes agree with the old writer for false/nil, Unicode/supplementary
values, big integers, key ordering and64KiB boundaries. A wrong-arm receipt
also fails instead of accepting generic success metadata.

An initial CI test-selection error is documented: feature tests accidentally
ran against the intentionally old published component. They now have their
own candidate namespace; the old71-test gate is unchanged, not weakened.

## Validation and measured scope

- Stock source + actual neutral-directory standalone:46tests/769assertions.
- Existing published consumer:71/553; corrected preflight CI must pass on the
  final PR head, not be inferred from an older run.
- Same release executable, A/B/B/A local Durable runs,32 measured5k callbacks
  plus3 warmups per run. All four independent older-stack readers verified
  full fields, types, exact timestamps and multiplicities:700000 rows total.
- Cached controls:12.2k and10.0k confirmed rows/s. Fused+cached:13.5k and13.6k.
  Fused estimated allocation is about0.8% higher. Control drift is -18.3%, so
  this is promising but NOT a precise causal speedup or population-tail claim.
- Direct public exporter calls, not a live SDK queue or Oscope/Langfuse/S3
  throughput result. Native timings precede the preflight-only follow-up;
  that follow-up has its own source/standalone tests, unchanged byte kernel.
- Targeted Claude source review and frozen follow-up review found no blockers.
  Execution/verification remains Codex-owned; static review is not run proof.

Full scripts, raw small reports, caveats and retention receipts:
`research/fused-nested-literal-20261020/native-comparison.md`.

## Draft gates

Before adoption/merge, isolate original/default-encoder regression cost rather
than comparing only two strategies in the candidate encoder, and qualify any
actual exporter capability selection. Do not add retries or pool drifting
controls into a target claim. Existing temporary stores/build intermediates
were retired after validated recovery; only small evidence and one explicitly
retained control executable remain locally.
