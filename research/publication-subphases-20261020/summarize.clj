(require '[clojure.edn :as edn])
(let [x (edn/read-string (slurp (first *command-line-args*)))
      leaves #{:wal-prepare :wal-append :wal-join :wal-immutable-put :wal-immutable-verify
               :wal-head-cas :native-classify :placeholder-scan :stream-create
               :stream-append :stream-finish :stream-destroy}
      batches (group-by :batch (filter #(<= 3 (:batch %) 34) (:observations x)))
      _ (assert (= 32 (count batches)))
      residuals (mapv (fn [[id xs]]
                        (assert (= 17 (count xs)))
                        (assert (every? #(= 1 %) (vals (frequencies (map :phase xs)))))
                        (assert (every? #(= :complete (:status %)) xs))
                        (let [parent (:nanos (first (filter #(= :execute-and-confirm (:phase %)) xs)))
                              covered (reduce + (map :nanos (filter #(leaves (:phase %)) xs)))
                              residual (- parent covered)]
                          (assert (<= 0 residual))
                          {:batch id :residual-ms (/ residual 1e6)})) (sort-by key batches))]
  (prn (assoc (select-keys x [:scope :warmup-batches :measured-batches :rows-per-batch
                             :summary :counter-snapshots-non-atomic :native-phases-nested-in-parent
                             :diagnostic-printing-in-window :tail-qualified :causal-version-comparison])
         :events (count (:observations x)) :every-phase-once-per-measured-batch true
         :residual-summary {:mean-ms (/ (reduce + (map :residual-ms residuals)) 32.0)
                            :p50-ms (nth (vec (sort (map :residual-ms residuals))) 15)
                            :max-ms (apply max (map :residual-ms residuals))}
         :residual-not-a-named-operation true
         :observer-phase-allocation-measurements-absent true)))
