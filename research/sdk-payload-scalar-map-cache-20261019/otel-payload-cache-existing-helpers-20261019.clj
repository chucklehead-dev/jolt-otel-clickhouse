(require '[clojure.test :as t]
         'otel.scalar-string-map-test 'otel.sorted-scalar-string-map-test)
(let [result (t/run-tests 'otel.scalar-string-map-test 'otel.sorted-scalar-string-map-test)]
  (assert (zero? (+ (:fail result) (:error result))))
  (assert (= 11 (:test result))))
