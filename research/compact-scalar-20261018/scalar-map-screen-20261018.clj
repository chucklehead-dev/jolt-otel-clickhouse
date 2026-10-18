;; Reuse the unchanged paired screen; replace only candidate construction.
;; Per-value policy lookup in v1 was expensive; v2 admits a whole callback-free
;; primitive map and queries the policy once per map, not per attribute.
(require '[clojure.string :as string])
(let [source (slurp "/home/chuck/ai-src/evidence/scalar-text-screen-20261018.clj")
      before "(def candidate-transform\n  (delay (attrs/load-transform! #'e/key-string #'scalar-value @#'e/original-attribute-key-string)))"
      after
      "(def candidate-transform
         (delay
           (let [path (clojure.java.io/resource \"otel/exporter/chdb/native_attribute_value_clone.ss\")
                 clone-source (slurp path)
                 old \"(jolt-invoke1 (var-cell-deref value-var) (cdr child))\"
                 _ (assert (string/includes? clone-source old))
                 clone (scheme/eval-string (string/replace clone-source old \"(value-var (cdr child))\"))
                 factory (scheme/eval-string (slurp \"/home/chuck/ai-src/evidence/scalar-map-factory-20261018.ss\"))]
             (factory clone @baseline-transform #'e/key-string #'e/value-string
                      @#'e/original-attribute-key-string @#'e/original-attribute-value-string
                      #'any/default-string-value-max-chars any/default-string-value-max-chars))))
       (let [m (into {} (map (fn [i] [(str \"key-\" i) (case (mod i 3) 0 \"s\" 1 false i)]) (range 16)))
             normal @baseline-transform fast @candidate-transform]
         (doseq [x [m {} nil (assoc m \"nil\" nil) (assoc m :keyword 42)
                    (assoc m \"nested\" {:x 3}) (assoc m \"huge\" 9223372036854775807)]]
           (assert (= (normal x) (fast x))))
         (with-redefs [e/value-string (fn [_] \"changed\")]
           (assert (= (normal m) (fast m))))
         (with-redefs [any/try-scalar-string (fn [_] \"sdk-change\")]
           (assert (= (normal m) (fast m)))))"
      _ (assert (string/includes? source before))
      changed (-> source (string/replace before after)
                  (string/replace ":scalar-text-component-not-persistence"
                                  ":scalar-map-component-not-persistence"))]
  (load-string changed))
