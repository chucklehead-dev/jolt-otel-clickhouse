(ns jolt.codec.experimental
  "Incubating explicit ASCII JSON codec. Not a data.json backend. Source-mode
  qualified on stock Jolt 0.8.23 and selected runtime7098. Linux standalone
  library gates pass; complete exporter/application AOT is not qualified."
  (:require [clojure.java.io :as io] [clojure.string :as str] [jolt.scheme :as scheme]
            [jolt.traversal.experimental :as traversal]))

(def ^:private seal (Object.))
(defmacro ^:private embedded [path]
  (if-let [resource (io/resource path)] (slurp resource)
    (throw (ex-info "Missing experimental codec resource" {}))))
(def ^:private json-source (embedded "jolt/codec/json.ss"))
(defn- qualify-nested-text! [scalar nested]
  ;; Exercise this backend's producer/consumer bridge before storage acquisition.
  ;; A raw JSON splice mutant must differ from the prior String operation.
  (let [probe {"probe" "quote\"/\\\nλ😀"}
        ordinary (String. (scalar probe 1000 128) "UTF-8")]
    (when-not (= (String. (scalar ordinary 4000 128) "UTF-8")
                 (String. (scalar (nested probe 1000 128) 4000 128) "UTF-8"))
      (throw (ex-info "Unsupported experimental nested-text runtime" {:type ::unavailable})))))
(def ^:private backend
  (delay
    ;; Lexical definitions avoid process-global Scheme helper replacement.
    (let [walk (traversal/open-traversal)
          factory (scheme/eval-string
            (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?) (let () " json-source
                 "\n(jolt-vector ffc-json-ascii-bytes ffc-json-project-prefixed-ascii-lines-bytes"
                 " (lambda (a) (eq? (record-type-uid (record-rtd a)) 'jolt-array-v1))"
                 " ffc-json-nested-text ffc-json-cached-project-prefixed-ascii-lines-bytes"
                 " ffc-json-row-box ffc-json-row-append ffc-json-row-width ffc-json-nested-scope ffc-json-scalar-map-box ffc-json-nested-literal ffc-json-nested-literal-scope)))"))
          [scalar project qualify nested cached-project row-box row-append row-width nested-scope scalar-map-box nested-literal literal-scope]
          (apply factory (traversal/native-kernels walk))]
      (when-not (and (qualify (byte-array 0))
                    (= "{\"a\":1}" (String. (scalar {"a" 1} 100 128) "UTF-8")))
        (throw (ex-info "Unsupported experimental codec runtime" {:type ::unavailable})))
      (qualify-nested-text! scalar nested)
      [scalar project (traversal/native-pmap-folder walk) nested cached-project
       row-box row-append row-width nested-scope scalar-map-box nested-literal literal-scope])))

(defn native-scalar-text-view-box
  "Research-only trusted Scheme bridge for scalar-text plan output. This is
  not an application constructor, a plain map, or authorization to skip policy."
  []
  (nth @backend 9))

(deftype ^:private Codec [tag scalar project fold nested row-box row-append row-width nested-scope])
(defn open-codec
  "Open a stateless explicit codec. Optional :flat-map-cache? (default false)
  enables bounded, per-invocation String->String pmap byte reuse in record
  encoding only. Scalar/nested encoders keep their existing path. No global
  cache or raw JSON input; source collections/strings must remain immutable."
  ([] (open-codec {}))
  ([options]
   (when-not (and (map? options) (every? #{:flat-map-cache?} (keys options))
                  (boolean? (get options :flat-map-cache? false)))
     (throw (ex-info "Invalid experimental codec options" {:type ::invalid-options})))
   (let [[scalar project fold nested cached-project row-box row-append row-width nested-scope] @backend]
     (Codec. seal scalar (if (:flat-map-cache? options) cached-project project) fold nested
             row-box row-append row-width nested-scope))))
(defn- require-codec! [codec]
  (when-not (and (instance? Codec codec) (identical? seal (.-tag codec)))
    (throw (ex-info "Invalid experimental codec" {:type ::invalid-codec})))
  codec)

(defn row-builder
  "Compile a fixed-arity constructor for an explicit codec row (0..32 fields).
  Capture once per schema, not per record. It evaluates supplied fields eagerly
  and creates private native storage instead of a persistent vector. Rows encode
  as JSON arrays with normal depth/size/value rules. This is not raw JSON or a
  mutable buffer; referenced input values must remain immutable during use."
  [codec width]
  (require-codec! codec)
  (when-not (and (integer? width) (<= 0 width 32))
    (throw (ex-info "Invalid experimental row width" {:type ::invalid-row-width})))
  (let [arguments (str/join " " (map #(str "field" %) (range width)))
        factory (scheme/eval-string
                  (str "(lambda (box) (lambda (" arguments ") (box (vector " arguments "))))"))]
    (factory (.-row-box codec))))

(defn append-row-fields
  "Return a row view referencing its base and an immutable vector suffix.
  No base/suffix values are copied. Only a row without a nonempty suffix is
  accepted; this does not build unbounded chains or invoke value converters."
  [codec row suffix]
  (require-codec! codec)
  ((.-row-append codec) row suffix))

(defn row-width
  "Return the explicit row's field count; rejects ordinary vectors/non-rows."
  [codec row]
  (require-codec! codec)
  ((.-row-width codec) row))

(defn native-map-folder
  "Internal synchronous Scheme visitor bridge for ordinary persistent maps.
  No entries/sequences are built. Not a portable reduce-kv or Reduced API."
  [codec]
  (require-codec! codec)
  (.-fold codec))

(defn write-nested
  "Return ASCII JSON text for one value. Each call owns its storage; empty
  immutable vectors have the fixed explicit-codec spelling, no JSONWriter hook."
  [codec value]
  (require-codec! codec)
  (if (and (vector? value) (empty? value)) "[]"
    (String. ((.-scalar codec) value 67108864 128) "UTF-8")))

(defn prepare-nested-text
  "Internal opaque JSON-text value for this codec's outer STRING encoding.
  Eagerly encode once into private bytes, without an intermediate String.
  Not raw JSON, a mutable byte array, or a replacement for write-nested."
  [codec value]
  (require-codec! codec)
  (if (and (vector? value) (empty? value)) "[]"
    ((.-nested codec) value 67108864 128)))

(defn prepare-nested-literal
  "Research-only eager nested JSON STRING encoding with a fused escaped sink.
  Not caller-supplied raw JSON. Same inner byte/depth limits; outer limits are
  enforced when copied into the final output. Does not use the fragment cache."
  [codec value]
  (require-codec! codec)
  (if (and (vector? value) (empty? value)) "[]"
    ((nth @backend 10) value 67108864 128)))

(defn with-nested-text-writer
  "Call use-writer with a synchronous nested-text encoder for one payload.
  Across calls it reuses complete ASCII fragments for immutable builtin maps
  with String keys and scalar values. Bounds: 64 insertions, 128KiB retained
  fragment bytes, 4KiB/fragment; keys are weak roots. No global cache or raw JSON.
  Do not use the writer concurrently or across fibers. Inputs stay immutable.
  On callback return/throw the cache is cleared and retained writers reject,
  including empty inputs. Outputs made during the scope remain independent."
  [codec use-writer]
  (require-codec! codec)
  (when-not (ifn? use-writer)
    (throw (ex-info "Invalid nested-text scope callback" {:type ::invalid-scope-callback})))
  (let [[prepare finish] ((.-nested-scope codec))]
    (try
      (use-writer (fn [value] (prepare value 67108864 128)))
      (finally (finish)))))

(defn with-nested-literal-writer
  "Research-only fused nested STRING writer with a separate bounded cache.
  Complete immutable escaped fragments only, no raw JSON or global registration.
  Writer expires on return/throw; already produced values remain independent."
  [codec use-writer]
  (require-codec! codec)
  (when-not (ifn? use-writer)
    (throw (ex-info "Invalid nested-literal scope callback" {:type ::invalid-scope-callback})))
  (let [[prepare finish] ((nth @backend 11))]
    (try (use-writer (fn [value] (prepare value 67108864 128)))
         (finally (finish)))))

(defn encode-prefixed-records
  "Return fresh ASCII bytes for prefix + projected JSON lines. Payload limit
  excludes prefix but includes row newlines. Projection runs once per record;
  after a failed row, no later record is requested. No admission/persistence."
  [codec prefix records project max-bytes]
  (require-codec! codec)
  (when-not (and (string? prefix) (or (nil? records) (sequential? records))
                 (ifn? project) (integer? max-bytes) (<= 0 max-bytes 67108864))
    (throw (ex-info "Invalid experimental codec request" {:type ::invalid-request})))
  ((.-project codec) prefix records project max-bytes 128))
