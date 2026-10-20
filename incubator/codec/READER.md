# Explicit experimental JSON reader

This optional namespace parses ordinary Jolt values on stock Jolt, without
requiring our data.json fork or compiler changes. It is an independent layer
inside the same dependency-free `incubator/codec` component. Loading it does not
load traversal, encoding, telemetry, storage or data.json namespaces.

It reuses the earlier guarded whole-document and Unicode token readers from
`casselc/data.json@bad53dc6eb44210fdc9ad8aa257f3d46f225c3b3`. Their resources
retain the source project's EPL-1.0 notice and accompanying license in
`READER-LICENSE-EPL-1.0`. The surrounding component's existing license is unchanged.

## Use with your configured parser

Numeric conversion and fallback are explicit adapters, not global replacements:

```clojure
(require '[jolt.codec.reader.experimental :as fast]
         '[clojure.data.json :as json])

(defn configured-read [source options]
  (apply json/read-str source (mapcat identity options)))

(def reader
  (fast/open-reader {:number-reader configured-read
                     :fallback-reader configured-read}))

(fast/read-string reader "{\"active\":false,\"values\":[7,null]}")
;; => {"active" false, "values" [7 nil]}

(fast/read-string reader "1.25" {:bigdec true})
;; uses your numeric conversion and its exact precision/type behavior
```

The library declares no JSON dependency. The example's parser is chosen by the
consumer; an adapter need not be data.json. The number adapter must strictly
decode a complete JSON numeric token, be pure, and agree with the fallback's
numeric semantics. It may be invoked before a later duplicate, malformed or
deep value makes the native parser decline. The adapter is a trusted contract,
not a property proven merely by a successful constructor probe.

A bounded smoke also passes with **stock Maven data.json 2.5.2**, canonical
`jolt-lang/time@a28ab100111050356957eb42b7ec89a4065de754`, and stock Jolt 0.8.23;
it does not select our JSON/time/compiler forks. The time provider is needed
to load stock data.json's DateTimeFormatter, not by this reader library.
That smoke checks basic values, an escaped Unicode object, wide integers and
callback fallback. It is not blanket compatibility qualification for stock
data.json on Jolt or all its value shapes/options.

Fallback accepts `(source options)` and is called at most once per `read-string`.
Its result or exception is preserved. String input and an options map are
required; invalid library arguments reject before adapters run. Only number/eof
options (`:bigdec`, `:eof-error?`, `:eof-value`) are eligible for native parsing.
Other options, including key/value/extra-data callbacks, bypass it entirely so
their effects and unread-input behavior stay with the configured parser.

## Observe selection without guessing

```clojure
(fast/try-read-string reader "false")
;; => [false 5]
(fast/try-read-string reader "null")
;; => [nil 4]
(fast/try-read-string reader "{\"a\":1,\"a\":2}")
;; => nil: declined, without invoking fallback
```

On success the existing native result vector contains `[value end]`; `end` is
a positive **scalar-character offset**, before trailing whitespace, not a UTF-8
byte offset or JVM UTF-16 char index. This module is Jolt-only: Jolt strings
already use scalar indexing. Translate offsets explicitly if transferring them
to another runtime; this complete-document API is not a streaming continuation.
A parsed false or nil is distinct from a declined attempt. This
small per-document result permits explicit acceptance/fallback accounting;
there is no token-level counter or callback in the normal scan loop.

Duplicate decoded keys, nesting beyond 64, unsupported malformed tokens and
EOF decline without changing source or caller cursor. String keys, vectors,
maps, booleans, nil and numeric values retain their normal representation.
Every invocation has its own parser cursor and decoded storage; no process-wide
parser or shared mutable result scratch is installed. Inputs must remain
immutable during calls. Callback concurrency behavior remains the caller's job.
No close is required. Structural cursor checks do not prove parser semantics.

This is not a strict-JSON policy upgrade. Raw control characters consistently
decline to the configured parser, including when an earlier escape selected
the token decoder. Strict fallbacks can reject them and permissive fallbacks
can preserve their existing values. Valid escaped controls still decode
natively. Nothing sanitizes or redacts telemetry. This is a small deliberate
extraction change from the inherited token decoder; transport/input size limits
remain the consumer's responsibility.

## Qualification and limits

The extracted implementation is experimental, not application default behavior.
The source-mode gate and independent Linux standalone gate run on official
stock Jolt 0.8.23. Reader-only tests explicitly assert that data.json, encoder
and traversal namespaces are absent. A separate adapter suite uses the parent
project's configured JSON parser; it is not a dependency-free test.

These are bounded checks, not full JSON conformance, every-platform packaging
or whole-application qualification. The earlier whole-reader socket experiment
used the old source-mode hook, not this extracted API: its approximately 2x
first-pair improvement must be replicated with the new adapter/selection path.
Existing encoder and cross-runtime baselines remain separate and unchanged.
