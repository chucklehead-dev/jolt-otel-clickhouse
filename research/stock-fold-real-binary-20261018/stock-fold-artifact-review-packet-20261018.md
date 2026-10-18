# Read-only review: integrated stock-fold artifact producer

Review ONLY these files; no shell, fetch, edits, environment/credentials,
persisted data, publication or delegation. <=650 words, concrete defects only.

- `/home/chuck/ai-src/worktrees/jolt-qualified-stock-fold-artifact-20261018/.github/workflows/durable-runtime-artifact.yml`
- `/home/chuck/ai-src/worktrees/jolt-runtime-artifact-0821-20261014/.github/workflows/durable-runtime-artifact.yml` (old producer1533b9fe)
- `/home/chuck/ai-src/worktrees/jolt-qualified-stock-sorted-fold-20261018/host/chez/host-table.ss` lines 220-265
- `/home/chuck/ai-src/evidence/build-stock-fold-local-candidate-20261018.sh`
- `/home/chuck/ai-src/evidence/qualify-stock-fold-real-binary-20261018.clj`

New source631984d66445b8b123f2cfa154fc6442cf468fd4, tree79cd95a4fbee07670da584d0b1c0fd201581f05e,
parent qualified1ae7e0d19aa3088789410106610beb171bcfb0ef. Exact helper/test code
copied from independently reviewed latest-Jolt646fcfcb (upstream proposal stays
on0.8.23). Native79/79 +existing sorted58/58 actually pass on this baseline.
Only source changes: helper, focused test, Makefile registration and changelog.

Existing workflow already independently pins compiler source/tree/released
upstream ancestry, full make ci/shared build, literal expected version,
allowlisted provenance, archive/binary digests. New topic changes source/tree/
version/artifact-name pins, branch trigger and isolated non-cancelling group;
adds real-binary fold/empty/custom-op admission checks and pass provenance line.
No existing run is cancelled, no user install/default/canonical aspects changes.

Local candidate is explicitly PROVISIONAL until hosted full CI and app gates.
Local script borrows no mutable existing build directory; exact source hashes
before/after, unique owned temp root, no automatic recursive deletion while a
process might be live. Retains only copied candidate binary and scalar receipts
after main verifies terminal ownership. Test script must require real native
helpers and exact per-namespace counts, never a Scheme source overlay.

Assess source/identity binding, fail-closed version/capability probes, accidental
exposure/cancellation and any false qualification claim. Do not claim runtime
tests from source inspection; main owns actual executions and final decisions.
