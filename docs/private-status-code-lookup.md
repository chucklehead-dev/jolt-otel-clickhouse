# Typed status lookup

The public status codebook remains a sorted, immutable map with the same
values and iteration order. Projection uses a private compact copy only when
the active codebook is that exact stock object. If the public Var is replaced,
projection uses the replacement directly. No telemetry, conversion results,
or per-row decisions are cached.

The scalar rules, duplicate-key handling and native projector's live callback
lookup are unchanged. This is not a new schema, WAL or confirmation rule.
The prototype on 10k prepared wide16 spans with two declared fields reduced
typed projection allocation from11.52MB to8.00MB and median time from about
34ms to24ms. This prepared diagnostic is not complete ingestion, S3, real-app
or repeated-tail qualification; qualify the actual source separately.
