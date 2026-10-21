# Real SDK preparation screen

Research only. This exercises the real SDK worker, exporter and local Durable
writer. It is not an Oscope repin/default or a published throughput claim.

The app reuses the existing public rich typed fixture and schema installer
from `research/scalar-view-exporter-20261020/app/src/perf/public_pipeline.clj`.
It reuses **source only**, not that app's older dependency coordinates.
The new app selects published SDK `d8db5da`, driver `a752c0c`, codec `c75579b`,
JSON `0f51b99` and stock crypto `5effcc8`, on stock Jolt 0.8.23/native 26.9.0.
Both arms must use the SAME default release executable.

## Measurements and boundaries

`synchronous` uses an ordinary SDK batch processor and synchronous exporter.
`overlap` selects the explicit two-slot preparation callback map. A public
exporter wrapper and scalar callback observations record confirmation; there
are no private-Var probes, SDK redefinitions, direct drain calls or fabricated
receipts. Capability-to-index bookkeeping is discarded on real retirement.
Completed scalar batch observations are flushed to the run log incrementally,
before attempting later batches. Both arms pay this coarse diagnostic-printing
cost inside the cadence window; no telemetry bodies/attributes are logged.

This is a **saturated-queue** screen. A startup-only first-warmup barrier lets
the producer fill the declared count-bounded raw queue before encoding starts.
For 3 warmups + 32 measured 5k batches, that capacity is 175,000 spans. This
does not model the default SDK queue, paced production, queue-full drops,
on-end allocation or simultaneous remote export. Queue-inclusive latency is
deliberately reported and will be large; do not hide that backlog.

Writer receipts report p50/p90/p95/p99 separately for:

- Consecutive confirmed-callback return intervals, anchored at the final
  warmup confirmation. The window rate counts the next 32 confirmed batches.
- Processing latency, from the SDK's export/preparation callback entry to
  that batch's confirmed callback return. Preparation of a later batch can
  delay observation of an already-persisted earlier ticket.
- First producer enqueue start to confirmed callback return, including queue
  residence and batch population time.

No physical publication-completion timestamp is claimed. Host allocation/GC/
CPU counters are coarse separate snapshots; estimated allocation is not a
heap/RSS bound. With 32 measurements, nearest-rank p99 is the sample maximum,
not population-tail qualification. Both arms use a supported 16MiB insert
bound; the inherited older synchronous app used 64MiB, which exceeds the new
cached-WAL capability. Do not silently compare these SDK rates to the earlier
direct synchronous 14.6k result as an identical experiment.

## Local invocation

Use the mandatory workspace Chez10.4.1 wrapper, explicit stock Jolt binary,
`JOLT_NO_USER_DEPS=1`, `JOLT_AOT_CACHE=0`, and the verified native library.
From `app/`, load/source-check first. Tiny functional invocations are:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 "$JOLT_BIN" -Srepro -m perf.sdk-preparation-pipeline /absolute/fresh/control synchronous 8 2 1 source-functional
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 "$JOLT_BIN" -Srepro -m perf.sdk-preparation-pipeline /absolute/fresh/candidate overlap 8 2 1 source-functional
```

Build with **build first**, through the same wrapper:
`/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 "$JOLT_BIN" build
-m perf.sdk-preparation-pipeline -o /absolute/owned/app`.
Run that one binary with separate fresh roots:

```sh
app /absolute/fresh/control synchronous 5000 32 3 release
app /absolute/fresh/candidate overlap 5000 32 3 release
```

Capture source, classpath, binary/library/wrapper hashes before and after.
Keep raw samples and incremental receipts. Do not run timed comparisons during
another compiler, model, profiler or agent review workload. A first sequential
pair is only a screen; qualify promising changes with matched order controls.

## Independent recovery

`perf.sdk-preparation-recovery` is source-only and not linked into the timed
app. Override its reader graph explicitly to older driver `3314b79`, SDK
`6cd57c8` and JSON `3adc8c5`, retaining stock crypto. Record actual resolved
providers rather than inferring them from declarations.

For EACH writer root, invoke four **separate processes** with arguments
`ROOT oracle`, `ROOT actual`, `ROOT expected`, `ROOT compare`. Each process
may own only one chDB storage lifetime, even after close. The ordinary
JSONEachRow/data.json oracle writes one copy of the same typed fixture into a
separate fresh namespace. Fresh snapshot processes verify every physical
column/type, exact timestamp/event ticks and per-row multiplicity. Map-order-
independent tagged canonical values are compared by SHA256; compact receipts
avoid retaining row dumps. This trusts the collision resistance of SHA256,
the ordinary control exporter and the shared fixture/schema definition; it
does not test persistent attribute-catalog ownership or fresh capability minting.

Retain failed stores until diagnosed. After all processes are terminal and
independent recovery/source checks pass, retire explicitly identified synthetic
stores/build intermediates, keeping small logs/manifests/samples. Keep at most
the useful control binary; no binary/store uploads are needed.
