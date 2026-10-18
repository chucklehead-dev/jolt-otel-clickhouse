(println :stock-fold-consumer-absent-runtime :entered)
(require '[jolt.scheme :as scheme])
(assert (not (scheme/eval-string "(top-level-bound? 'stock-sorted-map?)")))
(require '[clojure.data.json-byte-writer-test]
         '[clojure.data.json-sorted-byte-test]
         '[clojure.data.json-segmented-batch-test]
         '[clojure.test :as test])
(let [results (mapv (fn [[namespace expected-tests]]
                      (let [r (test/run-tests namespace)]
                        (assert (= expected-tests (:test r)))
                        (assoc r :namespace namespace)))
                    [['clojure.data.json-byte-writer-test 36]
                     ['clojure.data.json-sorted-byte-test 10]
                     ['clojure.data.json-segmented-batch-test 14]])
      ok? (every? #(zero? (+ (:fail %) (:error %))) results)]
  (prn {:gate :stock-fold-consumer-without-runtime-capability :complete true :results results})
  (System/exit (if ok? 0 1)))
