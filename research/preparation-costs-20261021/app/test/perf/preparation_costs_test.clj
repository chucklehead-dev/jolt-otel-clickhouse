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

(defn -main [& _]
  (let [r (run-tests 'perf.preparation-costs-test)]
    (assert (= 2 (:test r)))
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
