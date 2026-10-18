## Summary

Update the root application's SDK, exporter and optional native byte-JSON pins
together. This makes the reviewed sorted-attribute conversion and lower-
allocation traversal available without direct application pins silently
selecting older transitive implementations.

- SDK: `37c684a2` -> `7878a2eb`.
- Exporter: `67e28b59` -> `4d48871c`.
- Optional native byte JSON: `8d557cc1` -> `96f3e47d`.
- Check actual resolved roots and reject the three immediately previous pins.
- Include an Unreleased changelog entry.

Ordinary JSON, driver, runtime artifact pins, default encoding/storage options,
captured values and persistence confirmation rules are unchanged. The runtime
sorted-map fold is optional; older runtimes retain sequence traversal. This is
not an application throughput or tail-performance claim.

The separately pinned minimal embedded profile is intentionally unchanged;
its stack refresh is tracked in #167. Root embedded-API tests do not qualify
applications selecting `:deps/root "profiles/embedded"`.

## Red / green and validation

- Three real dependency mutations force the prior SDK, exporter or byte-JSON
  SHA, confirm that the old root resolved, and require the new-root predicate
  to reject it. The JSON mutation activates the byte alias with an override so
  its normal extra-deps cannot shadow the old pin. These are selection checks,
  not assertions that old revisions are incompatible.
- Focused root suites: **112 tests / 1,586 assertions**, green. Covers dependency
  resolution, config, export-option routing, embedded lifecycle and server tests.
- Native typed embedded SDK capture: **1 test / 8 assertions**, green; actual
  native stream and bound JSON companion observed. Live typed-query evidence,
  not a fresh-reader claim for this specific fixture.
- Native embedded SDK/viewer/background-query fixture: **1 test / 18 assertions**,
  green, including fresh-reader recovery and settled reader receipt. Observed
  14 native streams and 10 bound JSON companion calls.
- Native file-configured standalone/socket fixture: **1 test / 40 assertions**,
  green, including fresh-reader recovery and settled reader receipt. Observed
  five native streams and two bound JSON companion calls.
- Local native gates use checksum-verified hosted runtime source `7098a8d6`
  (producer run `39151375518`, artifact `12145549696`) and native package26.9.0.
  No helper source overlay or local/root replacement for the selected product
  SDK/exporter/JSON coordinates. This does not change any CI artifact pin.
- Targeted Claude review found an alias-selection gap in the new negative
  control; fixed and retested. Follow-up review clear; Codex inspected the
  exact diff and actual receipts.

Root and native-export CI are running on this head. Native-export CI retains
the existing qualified1ae writer artifact and stock0.8.22 reader, also checking
the optional-fold fallback. No external Langfuse, S3-service throughput or full
application performance qualification is claimed from these local gates.
