;; Run with bb. Scalar-only reports; no encoded rows or stores are retained.
(require '[clojure.edn :as edn])
(defn quantiles [values]
  (let [ordered (vec (sort values))]
    (assert (seq ordered))
    (into {} (for [[label probability] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [label (nth ordered (dec (int (Math/ceil (* probability (count ordered))))))]))))
(doseq [[basename group-key expected-samples]
        [["rich-repeated-conversion-bound-20261019" :mode 48]
         ["rich-repeated-conversion-bound-unwrapped-20261019" :mode 60]
         ["rich-owned-wal-clean-words-screen-20261019" :arm 48]]]
  (let [prefix (str "/home/chuck/ai-src/evidence/" basename)
        data (edn/read-string (slurp (str prefix ".edn")))
        events (:observations data)
        _ (assert (= expected-samples (count events)))
        _ (assert (true? (:all-output-hashes-equal data)))
        _ (assert (false? (:durable-execution data)))
        groups (into {} (for [[group rows] (group-by group-key events)]
                          [group {:samples (count rows)
                                  :latency-ms (quantiles (map #(/ (:nanos %) 1e6) rows))
                                  :estimated-allocation-bytes-per-row
                                  (quantiles
                                    (map #(/ (get-in % [:counters :scheme-heap-bytes-allocated])
                                              (double (:rows data))) rows))}]))
        result (assoc (dissoc data :observations) :groups groups
                      :percentiles-descriptive-only true :service-tail-qualified false)]
    (spit (str prefix ".summary.edn") (pr-str result))
    (prn basename groups)))
