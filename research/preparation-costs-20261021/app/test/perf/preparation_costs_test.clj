(ns perf.preparation-costs-test
  (:require [clojure.test :refer [deftest is run-tests]] [perf.preparation-costs :as p]))

(defn clock [] (let [tick (atom 0)]
                #(let [n (swap! tick inc)]
                   {:nanos n :cpu-nanos n :heap n :gc-bytes 0 :gc-count 0 :gc-nanos 0})))

(deftest delegates-values-arguments-and-errors-without-retaining-them
  (let [samples (atom []) round (atom 3) value (Object.) calls (atom 0)
        f (p/observer samples round 5 :snapshot (clock)
                      (fn [a b] (swap! calls inc) (is (= [1 2] [a b])) value))]
    (is (identical? value (f 1 2))) (is (= 1 @calls))
    (is (= #{:phase :round :rows :wall-nanos :cpu-nanos :gc-count :gc-real-nanos :estimated-allocated-bytes}
           (set (keys (first @samples)))))
    (let [error (ex-info "private" {:private value})
          f (p/observer samples round 5 :snapshot (clock) #(throw error))]
      (is (identical? error (try (f) (catch Throwable e e))))
      (is (= 2 (count @samples)))
      (is (= 1 (:wall-nanos (last @samples)))))))

(deftest coverage-rejects-missing-duplicate-and-wrong-phase-controls
  (let [samples (vec (for [phase p/phases round (range 2)] {:phase phase :round round}))]
    (is (true? (p/require-coverage! samples 2)))
    (is (thrown? Exception (p/require-coverage! (pop samples) 2)))
    (is (thrown? Exception (p/require-coverage! (assoc samples 0 (nth samples 1)) 2)))
    (is (thrown? Exception (p/require-coverage! (assoc-in samples [0 :phase] :unknown) 2)))))

(deftest failed-preparation-still-retires-the-unsubmitted-capability
  (doseq [outcome [:success :false :throw]]
    (let [events (atom []) error (ex-info "synthetic" {})
          adapter (into {:reserve! (fn [n] (swap! events conj [:reserve n]) :cap)}
                        (for [op [:abort! :release! :retire!]]
                          [op (fn [cap] (swap! events conj [op cap]) true)]))
          result (try
                   (p/prepared-cycle! adapter
                     (fn [cap rows]
                       (swap! events conj [:prepare cap (count rows)])
                       (case outcome :success true :false false :throw (throw error))) [1])
                   :completed
                   (catch Throwable e e))]
      (is (= [[:reserve 1] [:prepare :cap 1] [:abort! :cap] [:release! :cap] [:retire! :cap]]
             @events))
      (case outcome :success (is (= :completed result))
                    :false (is (instance? Throwable result))
                    :throw (is (identical? error result))))))

(deftest finite-sample-quantiles-and-summary-are-explicit
  (is (= [8 15 16 16] (p/quantiles (range 1 17))))
  (is (thrown? Exception (p/quantiles [])))
  (let [samples (for [phase p/phases round (range 16)]
                  {:phase phase :round round :rows 1 :wall-nanos (inc round)
                   :cpu-nanos 1 :gc-real-nanos 0 :estimated-allocated-bytes 4})
        summary (p/summary samples)]
    (is (= p/phases (set (keys summary))))
    (doseq [s (vals summary)]
      (is (= 16 (:samples s)))
      (is (= [0.000008 0.000015 0.000016 0.000016] (:p50-p90-p95-p99-ms s)))
      (is (= 4.0 (:estimated-bytes-per-row s))))))

(defn -main [& _]
  (let [r (run-tests 'perf.preparation-costs-test)]
    (assert (= 4 (:test r)))
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
