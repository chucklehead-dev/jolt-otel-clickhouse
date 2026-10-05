(ns otel.exporter.chdb-compact-wire-native-test
  "Fresh-process ordinary-query smoke; no streaming insert or anchor reset."
  (:require [clojure.test :as test :refer [deftest is]]
            [db.jdbc] [jdbc.chdb] [jdbc.core :as jdbc]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-benchmark :as benchmark]
            [otel.sdk.export :as export] [otel.sdk.logs :as logs]))

(deftest ordinary-compact-wire-retains-question-marks-unicode-and-ticks
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (let [target (exporter/exporter {:connection connection
                                   :insert-format :json-compact-each-row
                                   :signals #{:spans :logs :metrics}})
          service "compact-native-smoke"
          span (#'benchmark/span service 0)
          body "why? \"quoted\" é😀\n"
          record (assoc (#'benchmark/log-record service 0) :body body)]
      (is (true? (export/export-spans! target [span])))
      (is (true? (logs/export-logs! target [record])))
      (is (true? (export/export-metrics! target (#'benchmark/resource service)
                                       (#'benchmark/metrics 0 1))))
      (is (nil? (exporter/last-error target)))
      (is (= {:body body :flags 1 :severity 17}
             (jdbc/fetch-one connection
                ["select Body body, TraceFlags flags, SeverityNumber severity from otel_logs where ServiceName=?" service])))
      (let [row (jdbc/fetch-one connection
                    ["select toUnixTimestamp64Nano(Timestamp) tick, Duration duration, SpanAttributes['http.request.method'] method from otel_traces where ServiceName=?" service])]
        (is (= 1700000000000000000 (:tick row)))
        (is (= 1000000 (:duration row)))
        (is (= "GET" (:method row))))
      (doseq [table ["otel_metrics_gauge" "otel_metrics_sum" "otel_metrics_histogram"]]
        (is (= 1 (:n (jdbc/fetch-one connection
                       [(str "select count() n from " table " where ServiceName=?") service]))))))))

(defn -main [& _]
  (let [result (test/run-tests 'otel.exporter.chdb-compact-wire-native-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
