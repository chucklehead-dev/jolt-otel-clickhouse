(ns jolt.codec.experimental
  "Incubating explicit ASCII JSON codec. Not a data.json backend. Source-mode
  qualified on stock Jolt 0.8.23 and selected runtime7098; AOT is not qualified."
  (:require [clojure.java.io :as io] [jolt.scheme :as scheme]))

(def ^:private seal (Object.))
(defmacro ^:private embedded [path]
  (if-let [resource (io/resource path)] (slurp resource)
    (throw (ex-info "Missing experimental codec resource" {}))))
(def ^:private traversal-source (embedded "jolt/codec/traversal.ss"))
(def ^:private json-source (embedded "jolt/codec/json.ss"))
(def ^:private backend
  (delay
    ;; Lexical definitions avoid process-global Scheme helper replacement.
    (let [[scalar project qualify]
          (scheme/eval-string
            (str "(let () " traversal-source "\n" json-source
                 "\n(jolt-vector ffc-json-ascii-bytes ffc-json-project-prefixed-ascii-lines-bytes"
                 " (lambda (m v s a)"
                 " (and (eq? (record-type-uid (record-rtd m)) 'chez-pmap-v6)"
                 "      (eq? (record-type-uid (record-rtd v)) 'chez-pvec-v4)"
                 "      (eq? (record-type-uid (record-rtd s)) 'chez-htable-v2)"
                 "      (eq? (record-type-uid (record-rtd a)) 'jolt-array-v1)"
                 "      (eq? (record-type-uid (record-rtd empty-hnode)) 'chez-hnode-v1)))))"))]
      (when-not (and (qualify {} [] (sorted-map) (byte-array 0))
                    (= "{\"a\":1}" (String. (scalar {"a" 1} 100 128) "UTF-8")))
        (throw (ex-info "Unsupported experimental codec runtime" {:type ::unavailable})))
      [scalar project])))

(deftype ^:private Codec [tag scalar project])
(defn open-codec []
  (let [[scalar project] @backend] (Codec. seal scalar project)))
(defn- require-codec! [codec]
  (when-not (and (instance? Codec codec) (identical? seal (.-tag codec)))
    (throw (ex-info "Invalid experimental codec" {:type ::invalid-codec})))
  codec)

(defn write-nested
  "Return ASCII JSON text for one value. Each call owns its storage; empty
  immutable vectors have the fixed explicit-codec spelling, no JSONWriter hook."
  [codec value]
  (require-codec! codec)
  (if (and (vector? value) (empty? value)) "[]"
    (String. ((.-scalar codec) value 67108864 128) "UTF-8")))

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
