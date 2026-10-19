# Experimental explicit JSON codec

This is an incubating library, not a data.json replacement. It walks ordinary
Jolt data directly in Chez and writes to private byte buffers. It does not
replace JSONWriter methods or register global Scheme helpers.

The library itself has no dependencies beyond Jolt. Its tests run on the
official Jolt 0.8.23 release. An actual exporter/Durable local-root candidate
also passed stock-runtime write and independent readback. Published dependency
pins and the complete Oscope consumer are not yet stock-qualified; the selected
SDK/ordinary-JSON/driver ecosystem still includes our forks.
Standalone/AOT packaging is not qualified. The traversal and byte/JSON kernels
are separated here but not yet separately published libraries.

## API

```clojure
(require '[jolt.codec.experimental :as codec])
(def encoder (codec/open-codec))
(codec/write-nested encoder {"enabled" false})
;; => "{\"enabled\":false}"
(codec/encode-prefixed-records encoder "" [1 2] vector 8)
;; fresh byte[] containing "[1]\n[2]\n"
```

The byte limit excludes the ASCII prefix and includes newlines. Failure returns
no partial byte array and stops before asking for another record. Projection
and an input sequence can have their own effects; those effects are not rolled
back. Vector batches avoid sequence intermediates. Other sequential sources
are consumed incrementally, subject to their own realization behavior.

Output uses ASCII JSON with correct surrogate-pair escapes for non-BMP text.
Supported values are nil, Boolean, strings, names, integers, finite doubles,
ordinary persistent maps, stock sorted maps, and vectors. Unknown value types,
non-string/name object keys, and replaced sorted-map operations reject rather
than silently dropping data. Third-party JSONWriter extensions are not part of
this explicit API. Existing exporter defaults continue to honor their old path.

Each call owns its buffers; the returned mutable byte array has independent
storage. Nested calls use separate state. The codec holds no native resources
and needs no close. Internal collection layouts/private core operation tables
are trusted and version-sensitive; this is not a new stable Jolt ABI.

The internal `prepare-nested-text` operation eagerly encodes a value into private
bytes and returns an opaque value for this codec's outer string fields. This
avoids building and rescanning an intermediate String for `EventsJSON` and
`LinksJSON`. It is **not raw JSON insertion**: the outer encoder quotes and
escapes those bytes, producing the same wire string as `write-nested`. The
public `write-nested` operation still returns a String. Neither operation
changes source telemetry or when conversion callbacks run.

An interleaved 5,000-row rich projection/nested/outer component screen (16
samples per mode on stock 0.8.23) measured median 241ms with intermediate
Strings versus 204ms with private encoded text, and estimated 15.3KB versus
13.1KB allocated per row. Full output digests matched; a changed event was
detected. This excludes native/Durable I/O and is not a throughput-target or
production-tail qualification. The upper percentiles remained noisy.

A separate four-process String/bytes/bytes/String SDK/local-Durable comparison
then checked all 700,000 rows with independent recovery. Estimated whole-window
allocation fell from 30.6KB to 28.4KB/row (about 7%). Pooled throughput was
9.07k versus 9.37k rows/s, but control drift was 4.7%, exceeding the apparent
3.3% difference. There is no qualified end-to-end throughput win. This remains
experimental; component improvements do not establish the Durable targets.

## Checks

From this directory, with the workspace's Chez 10.4.1 wrapper and the exact
official stock executable:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0 \
  /home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt \
  -Srepro -Sdeps '{:paths ["src" "test"] :deps {}}' \
  -e "(require 'jolt.codec.experimental-test 'clojure.test)
      (clojure.test/run-tests 'jolt.codec.experimental-test)"
```

These cover prefix-excluded limits, exact chunk boundaries, sorted traversal,
independent storage, nested calls and positive/failed lazy-source consumption.
They do not establish full-collector performance or persistence correctness.
