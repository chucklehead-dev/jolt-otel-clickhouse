(ns otel.exporter.chdb-timestamp-wire-test
  (:require [clojure.test :refer [deftest is]] [otel.exporter.chdb :as e]))

(deftest utc-formatter-and-physical-domain
  (with-bindings {#'e/*timestamp-wire* :iso-utc}
    (doseq [[n expected] [[0 "1970-01-01T00:00:00Z"]
                         [1 "1970-01-01T00:00:00.000000001Z"]
                         [1000000000 "1970-01-01T00:00:01Z"]
                         [1700000000123456789 "2023-11-14T22:13:20.123456789Z"]
                         [9223372036854775807 "2262-04-11T23:47:16.854775807Z"]]]
      (is (= expected (#'e/timestamp n)))
      (is (#'e/valid-row-value? "Timestamp" expected)))
    (doseq [bad [nil 0 -1 "not-time" "1969-12-31T23:59:59Z"
                 "2262-04-11T23:47:16.854775808Z" "2263-01-01T00:00:00Z"
                 "2023-02-30T00:00:00Z" "2023-11-14T22:13:20.1+00:00"]]
      (is (false? (#'e/valid-row-value? "Timestamp" bad))))
    (is (= ["1970-01-01T00:00:00Z" "1970-01-01T00:00:00.000000001Z"]
           (get (#'e/event-columns [{:timestamp-unix-nano 0} {:timestamp-unix-nano 1}]) "Events.Timestamp")))
    (is (= "1970-01-01T00:00:00.000000002Z"
           (get (#'e/log-row {:timestamp-unix-nano 0 :observed-time-unix-nano 2} nil) "Timestamp")))
    (doseq [bad [-1 9223372036854775808N nil 0.5]]
      (is (thrown? clojure.lang.ExceptionInfo (#'e/timestamp bad)))))
  ;; An ISO owner must not change another owner or bare legacy helper.
  (is (= 1 (#'e/timestamp 1)))
  (is (#'e/valid-row-value? "Timestamp" 1))
  (is (false? (#'e/valid-row-value? "Timestamp" "1970-01-01T00:00:00Z"))))
