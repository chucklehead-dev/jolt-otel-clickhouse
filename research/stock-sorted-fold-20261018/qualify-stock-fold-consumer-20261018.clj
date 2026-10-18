;; A source-overlay qualification, NOT a claim about a rebuilt compiler.
(println :stock-fold-consumer-overlay :entered)
(require '[jolt.scheme :as scheme] '[clojure.string :as string])
(let [source (slurp "/home/chuck/ai-src/worktrees/jolt-0823-stock-sorted-fold-20261018/host/chez/host-table.ss")
      start (.indexOf source ";; Internal native-consumer helpers,")
      end (.indexOf source ";; --- extend the collection dispatchers")]
  (assert (and (<= 0 start) (< start end)))
  (scheme/eval-string (subs source start end)))
(require '[clojure.data.json-stock-sorted-fold-test]
         '[clojure.data.json-byte-writer-test]
         '[clojure.data.json-sorted-byte-test]
         '[clojure.data.json-segmented-batch-test]
         '[clojure.test :as test])
(let [results (mapv (fn [[namespace expected-tests]]
                      (let [r (test/run-tests namespace)]
                        (assert (= expected-tests (:test r)) (str "wrong gate namespace: " namespace))
                        (assoc r :namespace namespace)))
                    [['clojure.data.json-stock-sorted-fold-test 5]
                     ['clojure.data.json-byte-writer-test 36]
                     ['clojure.data.json-sorted-byte-test 10]
                     ['clojure.data.json-segmented-batch-test 14]])
      ok? (every? #(zero? (+ (:fail %) (:error %))) results)]
  (prn {:gate :stock-fold-consumer-source-overlay :complete true :results results})
  (System/exit (if ok? 0 1)))
