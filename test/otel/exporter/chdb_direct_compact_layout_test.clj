(ns otel.exporter.chdb-direct-compact-layout-test
  (:require [clojure.test :as test]
            [otel.exporter.chdb-compact-wire-test]
            [otel.exporter.chdb-compact-span-layout-test]
            [otel.exporter.chdb-compact-log-layout-test]
            [otel.exporter.chdb-metric-row-test]))

(defn -main [& _]
  (let [result (test/run-tests
                 'otel.exporter.chdb-compact-wire-test
                 'otel.exporter.chdb-compact-span-layout-test
                 'otel.exporter.chdb-compact-log-layout-test
                 'otel.exporter.chdb-metric-row-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
