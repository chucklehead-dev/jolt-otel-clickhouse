(require '[clojure.edn :as edn])
(let [[root] *command-line-args*
      report (fn [arm suffix] (edn/read-string (slurp (str root "/" arm "." suffix ".edn"))))
      q (fn [xs p] (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))
      arms (mapv (fn [name]
                   (let [w (report name "writer") r (report name "reader") v (report name "validation")
                         batches (:batches w) times (map :nanos batches)
                         enabled? (= "on" (:shape-plans-arm w))]
                     (assert (= 32 (count batches)))
                     (assert (:verified v))
                     (assert (and (:passed r) (:full-physical-rows-equal r) (:typed-values-status r)))
                     (assert (= [175000 5000 35] (mapv r [:rows :groups :per-record-multiplicity])))
                     (assert (= :equal-distinct-roots (:sharing w)))
                     (assert (:producer-representation-controls w))
                     (assert (= (if enabled? 36 0) (:shape-planner-constructors w)))
                     (when enabled? (assert (<= 1 (first (:last-shape-stats w)) 8))
                                   (assert (<= (second (:last-shape-stats w)) 8192)))
                     {:arm name :enabled? enabled? :measured-rows (:rows w)
                      :confirmed-window-nanos (:confirmed-window-nanos w)
                      :confirmed-window-rows-per-second (:confirmed-rows-per-second w)
                      :estimated-bytes-per-row (/ (get-in w [:counter-delta :scheme-heap-bytes-allocated]) (double (:rows w)))
                      :callback-ms (into {} (for [[k p] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                              [k (/ (q times p) 1e6)]))
                      :reciprocal-batch-rates-not-throughput-quantiles
                      (into {} (for [[k p] [[:inverse-p50 0.5] [:inverse-p90 0.9] [:inverse-p95 0.95] [:inverse-p99 0.99]]]
                                 [k (/ 5e12 (q times p))]))
                      :independent-recovered-rows (:rows r)})) ["0-off" "1-on" "2-on" "3-off"])
      pooled (into {} (for [enabled? [false true]
                           :let [xs (filter #(= enabled? (:enabled? %)) arms)]]
                       [enabled? (/ (* 1e9 (reduce + (map :measured-rows xs)))
                                    (reduce + (map :confirmed-window-nanos xs)))]))]
  (prn {:scope :local-durable-sdk-queue-rich-unshared-attributes
        :runtime :official-stock-jolt-0.8.23 :arms arms :pooled-confirmed-rows-per-second pooled
        :pooled-ratio (/ (pooled true) (pooled false))
        :control-ratio (/ (:confirmed-window-rows-per-second (last arms))
                          (:confirmed-window-rows-per-second (first arms)))
        :independent-recovered-rows (reduce + (map :independent-recovered-rows arms))
        :tail-qualified false :s3-qualified false :target-qualified false
        :schema-inference-included true :live-app-capture false}))
