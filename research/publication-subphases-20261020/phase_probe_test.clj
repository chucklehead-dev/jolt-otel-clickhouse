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

(deftest writer-observer-keeps-only-closed-scalar-fields
  (reset! p/events []) (reset! p/diagnostic-error? false) (reset! p/active 9)
  (p/writer-event! {:phase :wal-immutable-put :status :complete :nanos 3 :bytes 4096
                   :sql "PRIVATE-CANARY" :reference {:private "PRIVATE-CANARY"}})
  (is (= [{:batch 9 :phase :wal-immutable-put :status :complete :nanos 3 :bytes 4096
           :counter-sampled? false}] @p/events))
  (is (false? @p/diagnostic-error?))
  (p/writer-event! {:phase :unknown :status :complete :nanos 1 :bytes 1})
  (is (= 1 (count @p/events))) (is (true? @p/diagnostic-error?))
  (reset! p/active nil))

(deftest writer-observer-is-inactive-outside-batch-and-bounded
  (reset! p/events []) (reset! p/diagnostic-error? false) (reset! p/active nil)
  (p/writer-event! {:phase :unknown})
  (is (empty? @p/events)) (is (false? @p/diagnostic-error?))
  (reset! p/active 1) (reset! p/events (vec (repeat 1024 {:bounded true})))
  (p/writer-event! {:phase :wal-join :status :complete :nanos 1 :bytes 1})
  (is (= 1024 (count @p/events))) (is (true? @p/diagnostic-error?))
  (reset! p/active nil))

(defn -main [& _]
  (let [r (run-tests 'phase-probe-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
