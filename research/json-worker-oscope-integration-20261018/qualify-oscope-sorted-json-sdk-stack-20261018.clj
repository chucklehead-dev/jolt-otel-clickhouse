(println :oscope-sorted-json-sdk-stack :entered)
(require '[clojure.test :as test]
         '[oscope.dependency-resolution-test]
         '[oscope.config-test]
         '[oscope.export-options-test]
         '[oscope.embedded-test]
         '[oscope.server-test])
(let [results (mapv (fn [[namespace expected]]
                      (let [r (test/run-tests namespace)]
                        (assert (= expected (:test r)))
                        (assert (zero? (+ (:fail r) (:error r))))
                        (assoc r :namespace namespace)))
                    [['oscope.dependency-resolution-test 16]
                     ['oscope.config-test 26]
                     ['oscope.export-options-test 12]
                     ['oscope.embedded-test 32]
                     ['oscope.server-test 26]])]
  (prn {:gate :oscope-sorted-json-sdk-stack :complete true :passed true
        :tests (reduce + (map :test results)) :assertions (reduce + (map :pass results))
        :scope :root-application-focused-tests-not-minimal-profile-or-throughput
        :results results}))
