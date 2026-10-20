# Experimental explicit JSON codec

This is an incubating library, not a data.json replacement. It walks ordinary
Jolt data directly in Chez and writes to private byte buffers. It does not
replace JSONWriter methods or register global Scheme helpers.

The library itself has no dependencies beyond Jolt. Its tests run on the
official Jolt 0.8.23 release. An actual exporter/Durable local-root candidate
also passed stock-runtime write and independent readback. Published dependency
pins and the complete Oscope consumer are not yet stock-qualified; the selected
SDK/ordinary-JSON/driver ecosystem still includes our forks.
Linux default standalone library builds also pass the smoke gate below; this
does not qualify an exporter/application build or every build option/platform.
Traversal and byte/JSON are separate
namespaces within this one dependency-free library, not separately published
repositories. Loading traversal does not load the codec or exporter.

## Independent traversal

```clojure
(require '[jolt.traversal.experimental :as traversal])
(def walk (traversal/open-traversal))
(traversal/fold-map walk {"a" 1 "b" 2}
                    (fn [key value total] (+ total value)) 0)
;; => 3
(traversal/fold-vector walk [1 2 3] (fn [value total] (+ total value)) 0)
;; => 6
```

These are explicit **full folds**, not drop-in `reduce` or `reduce-kv`:
accumulator is the last callback argument, and `Reduced` does not terminate the
walk. Ordinary maps retain their seq order (including hash collisions); stock
sorted maps retain tree order without invoking comparators. The kernel builds
no entries or seq cursors for maps/vectors. This does not make callbacks or
vector indexing allocation-free, and collision traversal uses recursion stack.
`fold-records` also accepts sequential sources, which may allocate or realize
chunks according to their own semantics; it asks for no next record after a
callback throws. Inputs must remain immutable throughout each call.

Native consumers can capture `native-kernels` once in Scheme, or pass
`native-pmap-folder` to a compatible SDK constructor. This avoids per-element
Clojure wrappers. Unsupported layouts reject; there is no runtime-global helper
installation. The bridge is trusted and version-sensitive, not a stable ABI.
The codec now receives these native functions rather than embedding its own
copy of the traversal implementation.

To use only traversal as a local dependency, point `:local/root` at this
`incubator/codec` directory and require only `jolt.traversal.experimental`.
Neither the exporter project nor its SDK/driver/data.json dependencies are
needed. Public distribution and standalone/AOT builds remain separate gates.

## Use as an independent Git dependency

On stock Jolt 0.8.23, `:deps/root` selects this subproject without resolving
the parent exporter's dependencies:

```clojure
{:deps
 {io.github.chucklehead-dev/jolt-codec
  {:git/url "https://github.com/chucklehead-dev/jolt-otel-clickhouse.git"
   :git/sha "05fa8b86f38b0d1872322822fe68bf729b483613"
   :deps/root "incubator/codec"}}}
```

This is a separately selectable library component in the existing repository,
not a newly created repository. That exact published revision has the same
traversal/codec kernels; its resolved tree contains only this dependency.
An isolated consumer successfully folds a map and encodes JSON without
loading exporter or SDK namespaces. Requiring only traversal still does not
load JSON. It remains experimental and qualified for the named stock runtime,
not a stable collection-layout ABI or all future nightlies.

In the parent exporter checkout, `:stock-span-codec` selects this published
component; `:experimental-span-codec` instead selects the local source for
development. Do not combine them in a provenance check: local source would
shadow the published component. Application Git dependencies do not inherit
these aliases; declare the component above in the application's own deps.

The combined exporter check is:

```sh
jolt -M:stock-span-codec:stock-span-codec-test
```

Local workspace runs must prefix that command with the required Chez 10.4.1
wrapper. This gate checks the exact component resource paths before running
the 39 exporter/cache/codec/traversal tests. It is not actual Durable throughput
or a whole-application standalone qualification.

## API

```clojure
(require '[jolt.codec.experimental :as codec])
(def encoder (codec/open-codec))
(codec/write-nested encoder {"enabled" false})
;; => "{\"enabled\":false}"
(codec/encode-prefixed-records encoder "" [1 2] vector 8)
;; fresh byte[] containing "[1]\n[2]\n"
```

For record batches with repeated immutable String->String maps, opt in with
`(codec/open-codec {:flat-map-cache? true})`. The cache belongs to each
`encode-prefixed-records` invocation, not the codec object. It copies complete
encoded maps into private fragments: at most 64 insertions, 128KiB of fragment
bytes (plus bounded table/entry overhead), and 4KiB per fragment. Only ordinary persistent maps with at least eight entries
are considered. Non-flat maps, sorted maps, and maps crossing buffer chunks
use the existing serialization path. Hits still enforce current output/depth
limits. There is no caller-provided raw JSON, retained source wrapper/metadata,
or mutable output alias. Source maps and strings must remain immutable during
the call. Scalar/nested encoders are unchanged; nested calls have separate state.
This option is experimental and remains off by default.

The exporter selects it separately with `:experimental-span-flat-map-cache? true`
(requires `:experimental-span-codec? true` and its alias). It can compose with
`:experimental-span-payload-cache? true`, which caches SDK normalization rather
than wire bytes. Neither option changes admission or persistence acknowledgement.
Missing requested capabilities reject before database acquisition; defaults do
not require the incubating library.

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

The `packaging` project supplies only library/test source roots and no external
dependencies. On official stock Jolt 0.8.23, a default Linux standalone binary
runs **19 tests / 186 assertions**, including dynamic sorted-map operation
checks, nested calls and caches. A separate traversal-only binary runs
**5 / 42**, with the JSON namespace absent. These are functional packaging
checks, not throughput measurements or a stable collection-layout ABI.
The stock-library CI workflow also builds and executes the combined binary;
the local result is not itself evidence of a completed hosted run.

For a local build through the required workspace wrapper, first create a
task-specific temporary directory and pass its absolute output path:

```sh
cd packaging
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  env JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0 \
  /home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt \
  build -m jolt.codec.standalone-smoke-test -o /absolute/task-temp/codec-smoke
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 /absolute/task-temp/codec-smoke
```

Keep `build` first: the official 0.8.23 executable's build driver is unbound
when `-Sdeps` precedes the command. The packaging project avoids that launcher
path rather than patching the compiler. Retire the exact temporary binary and
its `.build` directory after saving terminal logs. Cross-platform, shared
library, closed-world/direct-link and complete SDK/exporter builds are not
qualified by this gate.

The traversal-only process gate uses the same isolated runtime command below
with `-m jolt.traversal.experimental-test` instead of `-e ...`. It checks that
the JSON namespace is absent both before and after the tests.

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
