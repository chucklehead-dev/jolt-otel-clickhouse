# Read-only cumulative exporter integration review

Review exact head4a42716286177fd0c033d69deb708b29dd162933 against main
e3750302c44cd10273c6e1277dc3912975cac8f5. Static complete diff:
/home/chuck/ai-src/evidence/exporter-main-identity-rollup-20261018.patch.
Current source root:
/home/chuck/ai-src/worktrees/exporter-default-omission-json-20261018.

Read only that patch and its12named source/test/config/docs files, consulting
unchanged surrounding functions only when necessary. No edits, commands,
secrets/env files, telemetry, persisted data, other repos, delegation or public
actions. Return <=600words on concrete defects/integration risks. Parent slices
were reviewed separately; this is the final cumulative check before landing the
working library's dependency/native scalar normalization stack into main.

The last3file delta pins optional native JSON2172061; ordinary JSON0f51b991
is unchanged. SDK7878a2e carries source-owned guarded scalar map conversion for
stock HAMT/sorted inputs. Previous native consumers keep changed-root and
unsupported/custom-operation fallbacks, callback order, typed source values,
descriptor provenance/status, and post-callback policy checks. Driver3d766fa
and runtime/default backend selection are unchanged. Check those claims against
actual guards; do not infer arbitrary callback purity from a type/probe.

Newest source review incorrectly suggested dropping the dedicated historical
a18 SHA mutant lets an actual a18 dependency revert pass. Main rejected that
claim: the oracle compares the sole actual canonical provider to exact217;
every old SHA still fails. A direct old-a18 rejection control passes. The new
immediate predecessor96f has its own mutant, plus8d/a017older cases. There is
no need for an ever-growing list of every historical SHA to make exact matching
sound. Raise a genuine oracle hole if one exists, not that mistaken claim.

Current local head passes56focused tests/981assertions and58dependency checks
on qualified7098actual git deps. Four matched real SDK/local Durable runs pass
old-stack independent recovery700000rows total. JSONproducer217 CI is green
and its owned-fork PR20 merged (merge tree matches tested217). New exporter CI
is starting. This packet is source only: no throughput, S3, full application or
formal proof conclusion should be inferred from these receipts.
