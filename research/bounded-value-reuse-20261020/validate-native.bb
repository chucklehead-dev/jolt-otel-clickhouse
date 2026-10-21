(require '[clojure.edn :as edn])
(defn ensure! [condition label]
  (when-not condition (throw (ex-info "Invalid value-reuse native receipt" {:label label}))))
(let [[root arm] *command-line-args*
      w (edn/read-string (slurp (str root "/writer.edn")))
      r (edn/read-string (slurp (str root "/reader.edn")))
      samples (:samples w)
      q (fn [p] (nth (vec (sort (map :nanos samples)))
                    (dec (long (Math/ceil (* p (count samples)))))))
      close? (fn [a b] (< (Math/abs (- (double a) (double b))) 1.0e-8))]
  (ensure! (contains? #{"views" "value-reuse"} arm) :known-arm)
  (ensure! (= arm (:value-reuse-arm w)) :selected-arm)
  (ensure! (= [175000 5000 32 3 1 36]
              (mapv w [:stored-rows :batch-size :rounds :warmups :provider-calls :planner-constructors])) :geometry)
  (ensure! (= (vec (range 32)) (mapv :round samples)) :all-rounds)
  (ensure! (every? #(and (true? (:ok %)) (= 5000 (:rows %)) (pos? (:nanos %))) samples)
           :all-confirmed-callbacks)
  (ensure! (and (:research-provider-selection w) (:same-wrapper-both-arms w)
                (false? (:production-default-changed w)) (false? (:sdk-queue-used w))) :research-scope)
  (when (= arm "value-reuse")
    (ensure! (= [1 302 9999 1] (:last-value-reuse-stats w)) :actual-cache-selection))
  (ensure! (>= (:confirmed-window-nanos w) (reduce + (map :nanos samples))) :whole-window)
  (ensure! (close? (:confirmed-rows-per-second w) (/ (* 1e9 5000 32) (:confirmed-window-nanos w))) :rate)
  (ensure! (every? true? (map close? (:p50-p90-p95-p99-ms w) (map #(/ (q %) 1e6) [0.5 0.9 0.95 0.99]))) :quantiles)
  (ensure! (= [true 175000 5000 625 true true 35 true]
              (mapv r [:passed :rows :groups :trace-count :full-physical-rows-equal
                       :typed-values-status :per-record-multiplicity :deterministic-tie-order]))
           :independent-full-recovery)
  (prn {:verified true :arm arm :independently-recovered-rows (:rows r)
        :confirmed-rows-per-second (:confirmed-rows-per-second w)
        :p50-p90-p95-p99-ms (:p50-p90-p95-p99-ms w)
        :inverse-latency-rows-per-second (mapv #(/ 5e6 %) (:p50-p90-p95-p99-ms w))
        :estimated-bytes-per-row (:estimated-bytes-per-row w)
        :causal-comparison-qualified false :tail-qualified false :target-qualified false}))
