(ns phase-probe-test
  (:require [clojure.test :refer [deftest is run-tests]] [phase-probe :as p]))

(deftest recorder-keeps-one-structured-event-and-the-original-result
  (reset! p/events []) (reset! p/diagnostic-error? false)
  (let [value (Object.) calls (atom 0)
        result (#'p/measured 7 :project-and-encode (fn [] (swap! calls inc) value))
        event (first @p/events)]
    (is (identical? value result)) (is (= 1 @calls))
    (is (= 1 (count @p/events)))
    (is (= #{:batch :phase :status :start-nanos :end-nanos :nanos :counters} (set (keys event))))
    (is (= [7 :project-and-encode :complete] (mapv event [:batch :phase :status])))
    (is (number? (:nanos event))) (is (map? (:counters event)))))

(deftest original-throwable-and-private-values-are-not-replaced-or-retained
  (reset! p/events []) (reset! p/diagnostic-error? false)
  (let [primary (ex-info "PRIVATE-CANARY" {:payload "not-metadata"})
        caught (try (#'p/measured 3 :wal-precompute #(throw primary)) nil (catch Throwable e e))]
    (is (identical? primary caught)) (is (= 1 (count @p/events)))
    (is (= :failed (:status (first @p/events))))
    (is (not (.contains (pr-str @p/events) "PRIVATE-CANARY")))))

(defn -main [& _]
  (let [r (run-tests 'phase-probe-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
