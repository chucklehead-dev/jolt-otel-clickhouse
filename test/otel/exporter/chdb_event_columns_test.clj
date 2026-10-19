(ns otel.exporter.chdb-event-columns-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [otel.exporter.chdb :as e]))

;; Exact original three-pass implementation, independent of the fast branch.
(defn reference [events compact?]
  (let [times (mapv #(#'e/timestamp (:timestamp-unix-nano %)) events)
        names (mapv #(or (:name %) "") events)
        attributes (mapv #(#'e/attrs (:attributes %)) events)]
    (if compact? [times names attributes]
      {"Events.Timestamp" times "Events.Name" names "Events.Attributes" attributes})))

(deftest singleton-vector-results-match-the-original-passes
  (doseq [compact? [false true]
          wire [:unix-nanos :raw-ticks :iso-utc]
          event [{:timestamp-unix-nano 0}
                 {:timestamp-unix-nano 9223372036854775807 :name "λ😀\n"
                  :attributes (sorted-map "count" 42 "flag" false "text" "λ")}
                 {:timestamp-unix-nano 7 :name false :attributes {:alias [1 true]}}
                 {:timestamp-unix-nano 7 :name "" :attributes nil}]]
    (with-bindings {#'e/*timestamp-wire* wire}
      (is (= (reference [event] compact?) (#'e/event-columns [event] compact?))))))

(deftest singleton-path-avoids-mapv-but-general-inputs-do-not
  (let [event {:timestamp-unix-nano 7 :name "event" :attributes {"a" 42}}
        original mapv calls (atom 0)
        expected (reference [event] true)]
    (with-redefs [clojure.core/mapv
                  (fn [& args] (swap! calls inc) (apply original args))]
      (is (= expected (#'e/event-columns [event] true)))
      (is (zero? @calls))
      (reset! calls 0)
      (is (= expected (#'e/event-columns (list event) true)))
      (is (= 3 @calls)))))

(deftest callback-order-errors-and-lazy-realization-are-preserved
  (doseq [events [[{:timestamp-unix-nano 1 :attributes {:a 1}}]
                 [{:timestamp-unix-nano 1 :attributes {:a 1}}
                  {:timestamp-unix-nano 2 :attributes {:b 2}}]]]
    (let [seen (atom [])
          observe (fn [f]
                    (reset! seen [])
                    (with-redefs [e/timestamp (fn [x] (swap! seen conj [:time x]) x)
                                  e/attrs (fn [x] (swap! seen conj [:attrs x]) x)]
                      [(f events true) @seen]))]
      (is (= (observe reference) (observe #'e/event-columns)))))
  (let [seen (atom []) error (ex-info "timestamp-control" {})]
    (with-redefs [e/timestamp (fn [_] (swap! seen conj :timestamp) (throw error))
                  e/attrs (fn [_] (swap! seen conj :attrs) {})]
      (is (identical? error
            (try (#'e/event-columns [{:timestamp-unix-nano 1}] true)
              (catch Throwable x x))))
      (is (= [:timestamp] @seen))))
  (let [seen (atom [])
        events (lazy-seq (swap! seen conj :realized)
                 (list {:timestamp-unix-nano 7 :name "lazy"}))]
    (is (= (reference [{:timestamp-unix-nano 7 :name "lazy"}] true)
          (#'e/event-columns events true)))
    (is (= [:realized] @seen))))

(defn -main [& _]
  (let [r (run-tests 'otel.exporter.chdb-event-columns-test)]
    (assert (= 3 (:test r)))
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
