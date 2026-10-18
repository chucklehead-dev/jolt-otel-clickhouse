;; No source overlay: all runtime helpers must come from the selected binary.
(println :stock-fold-real-binary :entered)
(require '[jolt.scheme :as scheme])
(assert (scheme/eval-string
          "(and (top-level-bound? 'stock-sorted-map?)
                (top-level-bound? 'sorted-map-fold-seq-order))"))
(require '[clojure.data.json-stock-sorted-fold-test]
         '[clojure.data.json-byte-writer-test]
         '[clojure.data.json-sorted-byte-test]
         '[clojure.data.json-segmented-batch-test]
         '[clojure.test :as test])
(let [results (mapv (fn [[namespace expected-tests]]
                      (let [r (test/run-tests namespace)]
                        (assert (= expected-tests (:test r)))
                        (assoc r :namespace namespace)))
                    [['clojure.data.json-stock-sorted-fold-test 5]
                     ['clojure.data.json-byte-writer-test 36]
                     ['clojure.data.json-sorted-byte-test 10]
                     ['clojure.data.json-segmented-batch-test 14]])
      ok? (every? #(zero? (+ (:fail %) (:error %))) results)]
  (prn {:gate :stock-fold-real-binary :complete true :passed ok? :source-overlay false :results results})
  (System/exit (if ok? 0 1)))
