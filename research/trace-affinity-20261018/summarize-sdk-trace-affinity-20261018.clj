(require '[clojure.edn :as edn])
(defn quantiles [xs scale]
  (let [xs (vec (sort xs)) n (count xs)]
    (into {} (for [[k p] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [k (/ (double (nth xs (dec (long (Math/ceil (* p n)))))) scale)]))))
(let [arms (mapv
             (fn [arm]
               (let [prefix (str "/home/chuck/ai-src/evidence/sdk-trace-affinity-" arm "-20261018")
                     w (edn/read-string (slurp (str prefix ".writer.edn")))
                     r (edn/read-string (slurp (str prefix ".reader.edn")))
                     v (edn/read-string (slurp (str prefix ".validation.edn")))
                     bs (:batches w) sizes (map :rows bs)
                     rate (/ (* 1e9 (:rows w)) (:confirmed-window-nanos w))]
                 (assert (and (:verified v) (:passed r) (= 175000 (:rows r))
                              (= [5000 625 35] (mapv r [:groups :trace-count :per-record-multiplicity]))))
                 (assert (and (= 160000 (:rows w)) (:per-trace-counts-checked w)
                              (:per-trace-callback-order-checked w) (false? (:tail-qualified w))))
                 (assert (= rate (:confirmed-rows-per-second w)))
                 {:arm arm :lanes (:lanes w) :arrival (:arrival w)
                  :confirmed-rows-per-second rate
                  :actual-offered-rows-per-second (/ (* 1e9 (:rows w)) (:producer-nanos w))
                  :callbacks (count bs) :native-streams (:stream-calls w)
                  :callback-latency-ms (quantiles (map :nanos bs) 1e6)
                  :callback-size {:minimum (apply min sizes) :maximum (apply max sizes)
                                  :quantiles (quantiles sizes 1.0)}
                  :expected-lane-counts (:expected-lane-counts w)
                  :allocated-B-per-row (/ (double (get-in w [:counter-delta :scheme-heap-bytes-allocated])) (:rows w))
                  :pacing-wait-ms (/ (:pacing-wait-nanos w) 1e6)
                  :capacity-wait-ms (/ (:load-generator-capacity-wait-nanos w) 1e6)
                  :maximum-arrival-lateness-ms (/ (:maximum-arrival-lateness-nanos w) 1e6)
                  :recovered-rows (:rows r)}))
             ["saturated-A1" "saturated-B" "saturated-A2" "paced-A1" "paced-B" "paced-A2"])
      report {:scope :trace-affinity-sdk-research-not-product-default
              :tail-qualified false :end-to-end-span-latency-measured false
              :total-recovered-rows (reduce + (map :recovered-rows arms)) :arms arms}]
  (assert (= 1050000 (:total-recovered-rows report)))
  (spit "/home/chuck/ai-src/evidence/sdk-trace-affinity-summary-20261018.edn" (pr-str report))
  (doseq [r arms] (prn r)))
