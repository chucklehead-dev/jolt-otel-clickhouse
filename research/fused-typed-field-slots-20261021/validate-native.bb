(require '[clojure.edn :as edn])
(defn ensure! [condition label]
  (when-not condition (throw (ex-info "Invalid field-slot native receipt" {:label label}))))

(let [[root arm] *command-line-args*
      w (edn/read-string (slurp (str root "/writer.edn")))
      r (edn/read-string (slurp (str root "/reader.edn")))
      samples (:samples w)
      q (fn [p] (nth (vec (sort (map :nanos samples)))
                    (dec (long (Math/ceil (* p (count samples)))))))
      close? (fn [a b] (< (Math/abs (- (double a) (double b))) 1.0e-8))]
  (ensure! (contains? #{"indexed" "fused"} arm) :known-arm)
  (ensure! (= arm (:field-slots-arm w)) :selected-arm)
  (ensure! (= [175000 5000 32 3 1 (if (= arm "fused") 1 0)]
              (mapv w [:stored-rows :batch-size :rounds :warmups :factory-calls :factories-selected])) :geometry)
  (ensure! (= (vec (range 32)) (mapv :round samples)) :all-rounds)
  (ensure! (every? #(and (true? (:ok %)) (= 5000 (:rows %)) (pos? (:nanos %))) samples)
           :all-confirmed-callbacks)
  (ensure! (and (:research-factory-selection w) (:same-wrapper-both-arms w)
                (false? (:per-row-probes w)) (false? (:production-default-changed w))
                (false? (:sdk-queue-used w))) :scope)
  (ensure! (>= (:confirmed-window-nanos w) (reduce + (map :nanos samples))) :whole-window)
  (ensure! (close? (:confirmed-rows-per-second w) (/ 160e12 (:confirmed-window-nanos w))) :rate)
  (ensure! (close? (:estimated-bytes-per-row w)
                   (/ (- (+ (get-in w [:after :heap]) (get-in w [:after :gc-bytes]))
                         (+ (get-in w [:before :heap]) (get-in w [:before :gc-bytes])))
                      160000.0)) :allocation-estimate)
  (ensure! (every? true? (map close? (:p50-p90-p95-p99-ms w) (map #(/ (q %) 1e6) [0.5 0.9 0.95 0.99]))) :quantiles)
  (ensure! (= [true 175000 5000 625 true true 35 true]
              (mapv r [:passed :rows :groups :trace-count :full-physical-rows-equal
                       :typed-values-status :per-record-multiplicity :deterministic-tie-order]))
           :independent-recovery)
  (prn {:verified true :arm arm :independently-recovered-rows (:rows r)
        :confirmed-rows-per-second (:confirmed-rows-per-second w)
        :p50-p90-p95-p99-ms (:p50-p90-p95-p99-ms w)
        :inverse-latency-rows-per-second (mapv #(/ 5e6 %) (:p50-p90-p95-p99-ms w))
        :estimated-bytes-per-row (:estimated-bytes-per-row w)
        :causal-throughput-qualified false :population-tail-qualified false :target-qualified false}))
