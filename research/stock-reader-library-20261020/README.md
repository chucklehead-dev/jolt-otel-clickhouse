# Explicit stock-Jolt reader extraction

The optional `jolt.codec.reader.experimental` namespace in `incubator/codec`
extracts the existing whole-document/Unicode readers from data.json bad53dc.
It adds an explicit caller-supplied numeric/fallback adapter rather than
requiring a JSON fork, patched compiler or process-wide parser replacement.
No exporter, Oscope default, dependency pin, WAL, ACK or model logic changes.

The earlier parser-core read-only review approved a next bounded stage, not
general conformance. This extraction tightens successful cursor validation to
positive offsets and consistently declines raw controls to caller policy. A
real token-kernel mutant removing that check is detected by the strict-fallback
control. Valid escaped controls and permissive configured values remain intact.
`try-read-string` exposes the existing per-document [value end] result so actual
native acceptance/decline can be counted separately from handler invocation.

## Executed local gates

Official stock Jolt0.8.23 binary SHA256
`7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94`,
mandatory `/home/chuck/ai-src/tools/jolt-with-chez-10.4.1` wrapper throughout.

- Dependency-free reader source: **10 tests / 363 assertions**, no failures.
- Actual independent Linux standalone reader binary, run from its temporary
  directory: **10 / 363**, no data.json/encoder/traversal namespaces loaded.
- Configured data.json0f51b991 adapter: **3 / 630**, including callbacks,
  unread suffixes, errors, Unicode/control values, numeric classes and generated
  telemetry-shaped values.
- Stock Maven data.json2.5.2 + canonical jolt-lang/time a28ab100 adapter smoke:
  passed, verified Maven resource path; no JSON/time/compiler forks selected.
  This bounded smoke is not full stock data.json compatibility qualification.
- Existing encoder/traversal independent source suite: **32 / 314**, unchanged.

Small actual stdout logs are retained beside this report. Initial loading of
stock Maven data.json without a time provider failed at DateTimeFormatter;
adding the canonical provider resolved it without changing this library.
Superseded standalone binaries are retired after terminal successful tests and
checksum capture; no large generated binary is committed.

The CI workflow repeats source, reader-only standalone, configured-adapter and
stock-Maven checks separately. See [usage and contracts](../../incubator/codec/READER.md).

## Remaining gates

The earlier approximately2x confirmed socket result used the old hook, not this
extracted API. Independently review the extraction, test its exact published Git
subproject, then replicate actual socket writes/fresh recovery with per-document
acceptance accounting. Broader whole-reader property/conformance, input bounds,
consumer startup/failure/standalone and cross-platform qualification remain
separate. Original Durable throughput/tail targets are active and unmet.

## Publication and review

The exact published Git subproject at77eb2899b1faec30f09bb5e1272f29a94bcd50ed
passed a neutral consumer check. Its resource resolved inside that Git SHA;
data.json, encoding and traversal namespaces remained absent. Hosted stock-library
CI on that exact head also passed. This is component qualification, not app use.

Claude read-only extraction review approved77eb289 for an owned PR and the next
consumer-qualification stage. Its one non-blocking cursor note is clarified:
the offset is neither UTF-8 bytes nor JVM UTF-16 units. There is no local Jolt
slicing bug (Jolt String indexing is already scalar); this is an explicit
cross-runtime warning, not a change to returned cursor/value semantics. Number
adapter failures intentionally decline to fallback as the contract says; no
claim is made for arbitrary impure or semantically incorrect adapters.
