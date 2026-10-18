# deps: qualify sorted telemetry SDK and native JSON stack

## What and why

Update the root application's SDK, exporter and optional native byte-JSON pins
together. The SDK supplies bounded sorted scalar-attribute conversion; the
exporter consumes it, and the byte writer can use the runtime's optional native
sorted-map fold instead of allocating intermediate map entries and sequences.
The coordinated pins prevent the application's direct SDK/JSON selections from
silently keeping older transitive implementations.

- SDK: `37c684a2` -> `7878a2eb`.
- Exporter: `67e28b59` -> `4d48871c`.
- Optional native byte JSON: `8d557cc1` -> `96f3e47d`.

Ordinary JSON, driver, runtime artifact pins, default encoding/storage options,
captured values and confirmation rules are unchanged. The native fold is
optional, with the existing sequence fallback on runtimes lacking it. This is
not a throughput or tail-performance claim. The separately pinned minimal
embedded profile is intentionally unchanged and needs its own qualification.
Includes an Unreleased changelog entry.

## Red/green checks

The real resolved classpath must contain each new SHA under ordinary and
optional byte-JSON startup. Three causal mutations force the immediately prior
SDK/exporter/byte-JSON coordinate, confirm that the old root really resolved,
and require the current-root predicate to reject it. The JSON mutation activates
the native alias with an override, so the alias cannot shadow the injected old
pin. This checks dependency selection, not a runtime compatibility rejection.

## Local validation

Qualified hosted runtime source `7098a8d6`, workflow `54a37192`, run
`39151375518`, artifact `12145549696`; archive/manifest/binary SHA checks pass.
Actual native library package26.9.0. No helper source overlay or local dependency
overrides for the updated product pins.

- 112 focused tests / 1,586 assertions: dependency resolution, configuration,
  option routing, embedded lifecycle and standalone server tests.
- Actual typed embedded SDK ingestion: fixture3, 8 assertions, native stream1,
  JSON companion2/bound2/empty-bound2; no fail/error, settled receipt.
- Actual embedded SDK/viewer/query capture and fresh-reader recovery: fixture4,
  18 assertions, native streams14, companion10/bound10/empty-bound8; no fail/error,
  reader terminal/settled/valid and final settled receipt.
- Actual standalone file-configured socket capture and fresh-reader recovery:
  fixture2, 40 assertions, native streams5, companion2/bound2/empty-bound2;
  no fail/error, reader terminal/settled/valid and final settled receipt.
- Claude read-only review found a weak alias control; corrected and retested.
  Follow-up review found no new defect. Codex inspected the diff and actual logs.

Hosted root/native-export CI has been dispatched on this exact head; its results
must be refreshed before submission or merge. Native-export CI deliberately
retains its existing1ae writer artifact and stock0.8.22 reader, independently
checking the optional-fold fallback rather than silently promoting a new pin.

## Review branch

Base: `d7558da043ad957515bbfa4b356c06198ade7fc0`.
Head: `a8b12af485c88de2d6fadba6d1c5df30c2104180`.

https://github.com/chucklehead-dev/oscope/compare/d7558da043ad957515bbfa4b356c06198ade7fc0...a8b12af485c88de2d6fadba6d1c5df30c2104180

Branch: `deps/sorted-json-sdk-stack-20261018`. Three changed files only.
No PR submitted at this checkpoint.
