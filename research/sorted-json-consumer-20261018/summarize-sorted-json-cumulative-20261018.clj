(require '[clojure.edn :as edn])
(let [report (edn/read-string (slurp (first *command-line-args*)))
      full (filter #(= :full (:phase %)) (:samples report))
      groups (group-by :arm full)
      sorted-only (sort-by :round (get groups :sorted-json))
      combined (sort-by :round (get groups :combined))
      ratios (map (fn [a b]
                    (assert (= (:round a) (:round b)))
                    (/ (double (:nanos b)) (:nanos a))) sorted-only combined)]
  (assert (= 16 (count sorted-only) (count combined)))
  (prn {:scope :cumulative-component-not-throughput :tail-qualified false
        :combined-over-sorted
        {:pairs (count ratios) :faster-pairs (count (filter #(< % 1) ratios))
         :mean-ratio (/ (reduce + ratios) (count ratios))}
        :phase-summary (:summary report)
        :full-counter-totals (into {} (for [[arm xs] groups]
                                       [arm (apply merge-with + (map :counters xs))]))}))
