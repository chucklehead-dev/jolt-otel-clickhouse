(require '[clojure.test :as t]
         'oscope.dependency-resolution-test
         'oscope.config-test
         'oscope.export-options-test
         'oscope.embedded-test
         'oscope.server-test)
(let [namespaces '[oscope.dependency-resolution-test oscope.config-test
                   oscope.export-options-test oscope.embedded-test oscope.server-test]
      results (mapv (fn [ns] (assoc (t/run-tests ns) :namespace ns)) namespaces)
      tests (reduce + (map :test results))
      assertions (reduce + (map :pass results))
      passed (and (= 112 tests) (>= assertions 1586)
               (every? #(zero? (+ (:fail %) (:error %))) results))]
  (println (pr-str {:gate :oscope-default-omission-exporter
                   :complete true :passed passed :tests tests
                   :assertions assertions :exporter "4a42716" :native-json "2172061"
                   :scope :root-focused-not-minimal-profile-or-throughput
                   :results results}))
  (System/exit (if passed 0 1)))
