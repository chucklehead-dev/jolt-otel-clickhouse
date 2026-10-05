# Large metric payload preparation (#100)

This branch adds an internal preparation helper, not enabled exporter chunking.
Existing export defaults and the 8 MiB payload guard remain unchanged.

`otel.exporter.chdb.payload-chunks/prepare-payloads!` takes a finite row sequence
and an explicit serializer, physical-byte budget, total-byte budget and row
budget. It serializes each visited row once, includes LF in exact UTF-8 byte
accounting, and never splits a row. It finishes the full bounded preparation
before returning any chunks to its caller. It makes no driver calls and returns
no persistence or admission receipt.

The source ceilings are 8 MiB per physical payload, 64 MiB total serialized
output per preparation call and 65,536 rows. Input objects, serializer work and
the temporary encoding of a single row can allocate before the size is known;
this is not a bound on arbitrary callbacks or the whole process heap. Lazy input
may realize its own chunks. Byte rejection does not request a later row.

## Reproduce the preparation checks

Use the workspace's mandatory Chez 10.4.1 wrapper and selected qualified Jolt.
Run from this repository root and set `CHUNK_TEST_JOLT` to that executable's
absolute path.
No compiler build or native database is needed for the focused helper tests:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 "$CHUNK_TEST_JOLT" -Srepro \
  -Sdeps '{:paths ["src" "test"]}' \
  -m otel.exporter.chdb-payload-chunks-test
bb --classpath src:test -m otel.exporter.chdb-payload-chunks-test
```

The Babashka test uses native Cheshire, not the Jolt data.json fork. These
tests compare chunks with the selected host serializer's exact output; they
do not assert identical escaping choices across hosts.

The larger Jolt source-mode check uses the actual benchmark's 10k histogram
shape and service-name width. The existing whole-payload encoder must reject;
the helper must produce bounded chunks with identical concatenated bytes:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 "$CHUNK_TEST_JOLT" -Srepro \
  -Sdeps '{:paths ["src" "bench"]}' \
  -m otel.exporter.chdb-payload-chunks-qualification
```

Local preparation evidence: 8,477,600 total bytes, with 9,895 rows / 8,388,581
bytes in the first chunk and 105 rows / 89,019 bytes in the second. This is a
capacity/parity check, not a performance result or evidence of persistence.

## Gates before wiring into export

- Keep the current defaults; introduce an explicit, bounded opt-in contract.
- Validate every metric row/type before the first driver effect. Define an
  aggregate output budget across all physical tables, not merely per helper call.
- Confirm each physical chunk through the existing Durable boundary. Logical
  success must wait for all chunks; failure may leave a committed prefix.
  Whole-batch retry can duplicate that prefix: do not imply atomic rollback,
  resumable delivery or exactly-once semantics.
- Extend the bounded acknowledgement/publication model and associated traces
  before enabling this new path. Check concurrent callers and shutdown drain.
- Qualify confirmed typed descriptors/statuses, full-value independent readers,
  injected failures at chunk boundaries, local/S3 performance and memory.

The helper tests cannot discharge those integration gates. Claude review and
final integration remain pending.
