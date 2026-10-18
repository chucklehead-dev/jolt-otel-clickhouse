# Keep sorted maps on the opt-in native byte emitter

## Motivation

Sorted maps selected the normal map JSONWriter but then fell out of the native
byte emitter into generic object serialization. Nested sorted attributes made
this fallback expensive even when all values were ordinary scalar data.

Keep tagged-table map receivers on the byte emitter **only after** their live
stock map method is selected. Use their public sequence operations instead of
reading a private tree or changing the caller's data representation. Publish
and synchronize the real writer around observable callbacks.

Custom JSONWriter methods and dispatcher replacements remain live. Nondefault
options retain the existing portable path. Default backend selection, writer
ownership and public APIs are unchanged.

## Validation

- Byte/segmented/focused local gate:57tests/973assertions before extra review
  controls; native string-writer gate:38tests/621assertions.
- Final focused gate:10tests/34assertions, including custom table operations,
  lazy-next ordering, prefix replacement, reentry, failures, list entries,
  tagged continuations, custom options and live method replacement.
- Same-output negative control disables the new map branch and detects that it
  was not used; output equality alone cannot establish optimization selection.
- Exact a18ae06 CI green: qualified native writer, portable Jolt/JVM, and JVM
  version matrix. The native lane explicitly runs the added namespace.
- Same-byte 2,000-record nested-map component,16rotating pairs: p50
  217.35->154.31ms; managed allocation68.86->46.97KB/row. No p99/SLO claim.

Read-only review reconciliation and cumulative persistence qualification are
recorded separately and are not inferred from these source/component gates.
