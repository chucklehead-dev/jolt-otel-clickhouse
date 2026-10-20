(ns jolt.codec.experimental
  "Incubating explicit ASCII JSON codec. Not a data.json backend. Source-mode
  qualified on stock Jolt 0.8.23 and selected runtime7098; AOT is not qualified."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]
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
            (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map?) (let () " json-source
                 "\n(jolt-vector ffc-json-ascii-bytes ffc-json-project-prefixed-ascii-lines-bytes"
                 " (lambda (a) (eq? (record-type-uid (record-rtd a)) 'jolt-array-v1))"
                 " ffc-json-nested-text)))"))
          [scalar project qualify nested] (apply factory (traversal/native-kernels walk))]
      (when-not (and (qualify (byte-array 0))
                    (= "{\"a\":1}" (String. (scalar {"a" 1} 100 128) "UTF-8")))
        (throw (ex-info "Unsupported experimental codec runtime" {:type ::unavailable})))
      (qualify-nested-text! scalar nested)
      [scalar project (traversal/native-pmap-folder walk) nested])))

(deftype ^:private Codec [tag scalar project fold nested])
(defn open-codec []
  (let [[scalar project fold nested] @backend] (Codec. seal scalar project fold nested)))
(defn- require-codec! [codec]
  (when-not (and (instance? Codec codec) (identical? seal (.-tag codec)))
    (throw (ex-info "Invalid experimental codec" {:type ::invalid-codec})))
  codec)

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
