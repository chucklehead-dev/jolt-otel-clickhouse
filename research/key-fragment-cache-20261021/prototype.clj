;; Research adapter: baseline compiles the EXACT unchanged kernel. The candidate
;; is an exact-match guarded source patch, not an installed library/backend.
(require '[clojure.java.io :as io] '[clojure.string :as str]
         '[jolt.scheme :as scheme] '[jolt.traversal.experimental :as traversal]
         '[jolt.codec.experimental :as codec] '[otel.exporter.chdb :as exporter])

(defn replace-once [source before after]
  (let [at (str/index-of source before)]
    (assert (some? at) [:missing-patch before])
    (assert (nil? (str/index-of source before (+ at (count before)))) [:duplicate-patch before])
    (str (subs source 0 at) after (subs source (+ at (count before))))))

(def key-cache-original-source (slurp (io/resource "jolt/codec/json.ss")))
(def key-cache-candidate-source
  (let [start (str/index-of key-cache-original-source "(define-record-type ffc-fragments")]
    (assert (some? start))
    ;; Native text/scalar-map/row types are shared lexically with the unchanged
    ;; control; only candidate fragment state has the extra cache fields.
    (-> (subs key-cache-original-source start)
        (replace-once "(mutable scratch) (mutable digits)))"
                      "(mutable scratch) (mutable digits) (mutable key-table) (mutable key-count) (mutable key-bytes)))")
        (replace-once "(make-ffc-fragments #f 0 0 (make-bytevector 1024) (make-bytevector 21)))"
                      "(make-ffc-fragments #f 0 0 (make-bytevector 1024) (make-bytevector 21) #f 0 0))")
        (replace-once "(define (key! k)\n      (cond ((string? k) (string! k))\n            ((keyword? k) (string! (keyword-t-name k)))\n            ((symbol-t? k) (string! (symbol-t-name k)))\n            (else (error 'ffc-json \"unsupported object key\"))))"
                      (slurp "/home/chuck/ai-src/worktrees/exporter-pipeline-phase-costs-20261021/research/key-fragment-cache-20261021/key-writer.ss"))
        (replace-once "(set! active? #f)"
                      "(set! active? #f)\n       (ffc-fragments-key-table-set! fragments #f)\n       (ffc-fragments-key-count-set! fragments 0)\n       (ffc-fragments-key-bytes-set! fragments 0)"))))

(defn compile-key-cache-native [candidate-source]
  (let [factory
        (scheme/eval-string
          (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?) (let () "
               key-cache-original-source
               "\n(let ((old-lines ffc-json-cached-project-prefixed-ascii-lines-bytes)"
               " (old-scalar ffc-json-ascii-bytes) (old-scope ffc-json-nested-scope) (old-nested ffc-json-nested-text))"
               " (let () " candidate-source
               "\n(jolt-vector (jolt-vector old-lines old-scalar old-scope old-nested)"
               " (jolt-vector ffc-json-cached-project-prefixed-ascii-lines-bytes ffc-json-ascii-bytes ffc-json-nested-scope ffc-json-nested-text)"
               " ffc-json-scalar-map-box)))))"))]
    (apply factory (traversal/native-kernels (traversal/open-traversal)))))

(def key-cache-native (compile-key-cache-native key-cache-candidate-source))

(defn key-cache-context [arm]
  (assert (contains? #{:original :key-cache} arm))
  (let [[lines scalar scope nested] (nth key-cache-native (if (= arm :original) 0 1))
        box (nth key-cache-native 2)]
    ;; The SAME facade wrappers in both arms. SDK still owns all conversion
    ;; anchors/limits/shape learning. No private SDK Var replacement/snapshot.
    (with-redefs [codec/encode-prefixed-records
                  (fn [_ prefix records project maximum]
                    (lines prefix records project maximum 128))
                  codec/prepare-nested-text
                  (fn [_ value] (if (and (vector? value) (empty? value)) "[]"
                                   (nested value 67108864 128)))
                  codec/with-nested-text-writer
                  (fn [_ use-writer]
                    (let [[prepare finish] (scope)]
                      (try (use-writer (fn [value] (prepare value 67108864 128)))
                           (finally (finish)))))
                  codec/native-scalar-text-view-box (fn [] box)]
      (#'exporter/load-experimental-span-codec! true true true true true))))
