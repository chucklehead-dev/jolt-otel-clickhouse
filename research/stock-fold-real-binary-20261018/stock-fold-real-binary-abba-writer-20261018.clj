;; Real compiled runtimes only; the older binary must lack the optional fold.
;; No helper/factory source overlay. Product Git dependencies remain fixed.
(require '[jolt.scheme :as scheme])
(let [arm (System/getenv "SDK_STOCK_FOLD_ARM")
      present? (scheme/eval-string
                 "(and (top-level-bound? 'stock-sorted-map?) (top-level-bound? 'sorted-map-fold-seq-order) (procedure? (top-level-value 'stock-sorted-map?)) (procedure? (top-level-value 'sorted-map-fold-seq-order)))")]
  (assert (contains? #{"baseline" "candidate"} arm))
  (assert (= (= arm "candidate") (boolean present?))))
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [original fixture/spans]
  (with-redefs [fixture/spans (fn [region] (nested-sorted-sdk-shape (original region)))]
    (load-file "/home/chuck/ai-src/evidence/sdk-trace-affinity-screen-20261018.clj")))
