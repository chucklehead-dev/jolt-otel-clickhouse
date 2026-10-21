(require '[clojure.java.io :as io] '[clojure.string :as str]
         '[jolt.scheme :as scheme] '[jolt.traversal.experimental :as traversal]
         '[jolt.codec.experimental :as codec] '[otel.exporter.chdb :as exporter])

(defn replace-once [source before after]
  (let [at (str/index-of source before)]
    (assert (some? at) [:missing-patch before])
    (assert (nil? (str/index-of source before (+ at (count before)))) [:duplicate-patch before])
    (str (subs source 0 at) after (subs source (+ at (count before))))))

(def original-source (slurp (io/resource "jolt/codec/json.ss")))
(def candidate-source
  (let [at (str/index-of original-source "(define-record-type ffc-fragments")]
    (assert (some? at))
    (-> (subs original-source at)
        (replace-once "(map-flat? #f))"
                      "(map-flat? #f) (template-cache #f) (template-attempts 0) (template-bytes 0))")
        (replace-once
          "(define (scalar-text-entry! k ordinal printed?)\n      (when printed? (byte! 44))\n      (key! k) (byte! 58)\n      (when (fx>=? container-depth max-depth) (error 'ffc-json \"depth limit exceeded\"))\n      (let ((value (vector-ref scalar-values ordinal)))\n        (cond ((string? value) (string! value))\n              ((boolean? value) (string! (if value \"true\" \"false\")))\n              ((fixnum? value) (byte! 34) (integer! value) (byte! 34))\n              (else (error 'scalar-text-view \"invalid trusted scalar\"))))\n      #t)"
          "(define (scalar-text-value! ordinal)\n      (when (fx>=? container-depth max-depth) (error 'ffc-json \"depth limit exceeded\"))\n      (let ((value (vector-ref scalar-values ordinal)))\n        (cond ((string? value) (string! value))\n              ((boolean? value) (string! (if value \"true\" \"false\")))\n              ((fixnum? value) (byte! 34) (integer! value) (byte! 34))\n              (else (error 'scalar-text-view \"invalid trusted scalar\")))))\n    (define (scalar-text-entry! k ordinal printed?)\n      (when printed? (byte! 44)) (key! k) (byte! 58)\n      (scalar-text-value! ordinal) #t)")
        (replace-once "    (define (emit! x depth)"
                      (str (slurp "research/template-key-prefixes-20261021/prefix-plan.ss")
                           "\n    (define (emit! x depth)"))
        (replace-once "(ffc-map-fold (ffc-json-scalar-map-template x) scalar-text-entry! #f)"
                      "(scalar-prefix-map! (ffc-json-scalar-map-template x))"))))

(def disabled-source
  (replace-once candidate-source "(fx<? template-attempts 8)" "(fx<? template-attempts 0)"))

(defn compile-prefix-native
  ([candidate disabled] (compile-prefix-native candidate disabled nil))
  ([candidate disabled observer]
  (let [factory (scheme/eval-string
                  (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record? ffc-prefix-observe) (let () "
                       original-source
                       "\n(let ((ffc-prefix-key-encode ffc-json-encode/plain)"
                       " (baseline (jolt-vector ffc-json-cached-project-prefixed-ascii-lines-bytes ffc-json-ascii-bytes ffc-json-nested-scope ffc-json-nested-text ffc-json-bytes)))"
                       " (let () " disabled
                       "\n(let ((off (jolt-vector ffc-json-cached-project-prefixed-ascii-lines-bytes ffc-json-ascii-bytes ffc-json-nested-scope ffc-json-nested-text ffc-json-bytes)))"
                       " (let () " candidate
                       "\n(jolt-vector baseline off (jolt-vector ffc-json-cached-project-prefixed-ascii-lines-bytes ffc-json-ascii-bytes ffc-json-nested-scope ffc-json-nested-text ffc-json-bytes)"
                       " ffc-json-scalar-map-box)))))))"))]
    (apply factory (conj (traversal/native-kernels (traversal/open-traversal)) observer)))))

(def prefix-native (compile-prefix-native candidate-source disabled-source))
(defn prefix-context [arm]
  (assert (contains? #{:original :disabled :prefix} arm))
  (let [[lines scalar scope nested] (nth prefix-native ({:original 0 :disabled 1 :prefix 2} arm))
        box (nth prefix-native 3)]
    (with-redefs [codec/encode-prefixed-records (fn [_ prefix rows project maximum] (lines prefix rows project maximum 128))
                  codec/prepare-nested-text (fn [_ value] (if (and (vector? value) (empty? value)) "[]" (nested value 67108864 128)))
                  codec/with-nested-text-writer
                  (fn [_ use-writer]
                    (let [[prepare finish] (scope)]
                      (try (use-writer (fn [value] (prepare value 67108864 128)))
                           (finally (finish)))))
                  codec/native-scalar-text-view-box (fn [] box)]
      (#'exporter/load-experimental-span-codec! true true true true true))))

(def bytes-equal?
  (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))"))
