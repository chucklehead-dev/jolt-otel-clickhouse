# Experimental explicit JSON codec

This is an incubating library, not a data.json replacement. It walks ordinary
Jolt data directly in Chez and writes to private byte buffers. It does not
replace JSONWriter methods or register global Scheme helpers.

The library itself has no dependencies beyond Jolt. Its tests run on the
official Jolt 0.8.23 release. The full exporter experiment still uses the selected
SDK/driver/native-JSON stack; **the full consumer is not yet stock-qualified**.
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
