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
      passed (and (= 113 tests) (>= assertions 1589)
               (every? #(zero? (+ (:fail %) (:error %))) results))]
  (println (pr-str {:gate :oscope-singleton-event-exporter
                   :complete true :passed passed :tests tests
                   :assertions assertions :exporter "2c54b98" :native-json "2172061"
                   :scope :root-focused-not-minimal-profile-or-throughput
                   :results results}))
  (System/exit (if passed 0 1)))
