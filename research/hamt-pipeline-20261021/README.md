# Ordinary-map views in the real SDK/Durable pipeline

Research-only qualification on stock Jolt 0.8.23, Chez 10.4.1 and chDB 26.9.0.
This does not enable a product default or qualify Oscope, S3 or paced traffic.

The exporter is based on `4650e3542705e7698d8895ff95cf9b43e8f2288a` with a
temporary constructor-selection patch. The SDK is the clean published
`a4a13b07276deac31ffc55bb264fc79c7cd0aa80` worktree. Driver `312812f`, JSON
`0f51b99` and stock crypto `5effcc8` are explicit in deps.edn. The resolved
classpath, file hashes, patch and binary hashes are captured beside each run.

Both arms use the new SDK and one default release executable. `disabled`
selects sorted-only shape views; `enabled` additionally selects ordinary HAMT
views. Both retain root caching, original conversion/fallback policy and the
same two-slot preparation/persistence overlap. This is not an old-SDK timing
comparison. The research dynamic binding is read during exporter construction;
the selected Boolean is captured by the factory before workers start. The
measured checkpoint `e22ecee` nevertheless kept that dynamic binding active
around the entire pipeline; no timing effect of that scope has been established.
The current source replaces it with a research atom read only at construction,
without a worker-lifetime binding. This later control has functional mechanism
evidence, not a newly measured native throughput result.

The existing public pipeline and rich typed fixture are unchanged. A tiny
native smoke is recovered first, followed by disabled/enabled/enabled/disabled
5k-row runs, 3 warmups and 32 measurements each. Raw-queue preload, diagnostic
printing, callback-return confirmation scope, host-counter limitations and
finite-sample tail limitations are unchanged from the preparation-overlap app.

For each arm, separate source processes use older driver `3314b79`, SDK
`6cd57c8`, JSON `3adc8c5` and ordinary JSONEachRow export for an independent
oracle. Recovery compares all physical field values/types, exact timestamp
ticks and multiplicity through canonical SHA256 receipts, not count alone.
The shared fixture/control exporter and SHA256 remain trusted dependencies.

The net product source at this research branch's tip is unchanged from the
prototype. Before a new source check or build, manually apply the isolated
research selector with `git apply research/hamt-pipeline-20261021/constructor-only.patch`
from the repository root. Neither the selector nor this research app belongs
in a feature PR. Without the patch, the app fails to resolve its research Var
before opening storage; there is no automatic product patching.

Run `bash run-qualified.sh /absolute/owned/evidence-root` after building `app`
there. The runner fails closed, preserves failed stores, and retires successful
synthetic stores only after copying compact receipts. Build intermediates can
be retired separately after the build and all runtime handles are terminal.
Never merge the research constructor switch into the feature branches.

`perf.hamt-fixture-mechanism` is a separate source-only diagnostic. It checks
copy-helper calls and actual opaque-view selection at the exporter payload
handoff using eight rows of the real rich fixture, plus complete nested byte
equivalence. It has deliberate private probes but no SDK worker, Durable writer
or timings. Requested-selector `hamt-mode.edn` manifests alone do not prove
that a view was selected for any input.
