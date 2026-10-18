(require '[clojure.edn :as edn])
(defn quantiles [xs]
  (let [xs (vec (sort xs)) n (count xs)]
    (into {} (for [[k p] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [k (/ (double (nth xs (dec (long (Math/ceil (* p n)))))) 1e6)]))))
(let [reports
      (mapv (fn [arm]
              (let [prefix (str "/home/chuck/ai-src/evidence/sdk-partitioned-5k-" arm "-20261018")
                    w (edn/read-string (slurp (str prefix ".writer.edn")))
                    r (edn/read-string (slurp (str prefix ".reader.edn")))
                    v (edn/read-string (slurp (str prefix ".validation.edn")))
                    batches (:batches w)
                    overlap-count (count (for [a batches b batches
                                               :when (and (< (:lane a) (:lane b))
                                                          (< (:start-nanos a) (:end-nanos b))
                                                          (< (:start-nanos b) (:end-nanos a)))] true))]
                (assert (and (:verified v) (:passed r) (= 175000 (:rows r))
                             (= 160000 (:rows w)) (= 32 (count batches))))
                (assert (and (false? (:tail-qualified w))
                             (false? (:force-flush-before-measurement w))
                             (true? (:serial-worker-per-exporter w))))
                (assert (= (:confirmed-rows-per-second w)
                           (/ (double (* (:rows w) 1000000000)) (:confirmed-window-nanos w))))
                {:arm arm :lanes (:lanes w) :rows (:rows w) :recovered-rows (:rows r)
                 :confirmed-rows-per-second (:confirmed-rows-per-second w)
                 :callback-latency-ms (quantiles (map :nanos batches))
                 :callback-count (count batches) :cross-lane-overlap-pairs overlap-count
                 :allocated-B-per-row (/ (double (get-in w [:counter-delta :scheme-heap-bytes-allocated]))
                                         (:rows w))
                 :queue-capacity (:total-queue-capacity w)
                 :capacity-wait-ms (/ (:load-generator-capacity-wait-nanos w) 1e6)
                 :per-lane (mapv (fn [[lane xs]] {:lane lane :callbacks (count xs)
                                                 :rows (reduce + (map :rows xs))
                                                 :callback-latency-ms (quantiles (map :nanos xs))})
                                 (sort-by key (group-by :lane batches)))}))
            ["A1" "B" "A2"])
      result {:scope :partitioned-sdk-capacity-research-not-library-speedup
              :tail-qualified false :application-ingress-qualified false
              :total-verified-recovery-rows (reduce + (map :recovered-rows reports))
              :arms reports}]
  (assert (= 525000 (:total-verified-recovery-rows result)))
  (spit "/home/chuck/ai-src/evidence/sdk-partitioned-summary-20261018.edn" (pr-str result))
  (doseq [r reports] (prn (dissoc r :per-lane))))
