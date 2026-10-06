(ns otel.exporter.chdb-benchmark-percentiles-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [otel.exporter.chdb-benchmark :as benchmark]))

(deftest nearest-rank-latency-percentiles
  (let [summary (#'benchmark/latency-summary
                 (reverse (map #(* % 1000000) (range 1 101))))]
    (is (= {:count 100 :p50-ms 50.0 :p90-ms 90.0
            :p95-ms 95.0 :p99-ms 99.0 :max-ms 100.0}
           (select-keys summary
                        [:count :p50-ms :p90-ms :p95-ms :p99-ms :max-ms])))
    (is (= 5050.0 (:total-ms summary))))
  (is (= [1.0 1.0 1.0 1.0]
         ((juxt :p50-ms :p90-ms :p95-ms :p99-ms)
          (#'benchmark/latency-summary [1000000])))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'otel.exporter.chdb-benchmark-percentiles-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Benchmark percentile checks failed" {:fail fail :error error})))))
