(ns jolt.codec.standalone-smoke-test
  "Standalone packaging gate for the independent library, not the exporter."
  (:require [clojure.test :as test]
            [jolt.traversal.experimental-test]
            [jolt.traversal.leaf-block-test]
            [jolt.codec.experimental-test]
            [jolt.codec.row-view-test]
            [jolt.codec.flat-map-cache-test]))

(defn -main [& _]
  (assert (nil? (find-ns 'otel.exporter.chdb)))
  (let [result (test/run-tests 'jolt.traversal.experimental-test
                             'jolt.codec.experimental-test
                             'jolt.codec.flat-map-cache-test
                             'jolt.traversal.leaf-block-test
                             'jolt.codec.row-view-test)]
    (assert (= 23 (:test result)))
    (assert (nil? (find-ns 'otel.exporter.chdb)))
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
