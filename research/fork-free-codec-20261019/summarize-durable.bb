(require '[clojure.edn :as edn])
(def root (first *command-line-args*))
(defn read-result [name] (edn/read-string (slurp (str root "/" name))))
(defn q [xs fraction]
  (let [ordered (vec (sort xs))]
    (nth ordered (max 0 (dec (long (Math/ceil (* fraction (count ordered)))))))))
(defn percentiles [xs]
  (into {} (for [[label p] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
             [label (q xs p)])))
(def arms
  (mapv (fn [i arm]
          (let [prefix (str i "-" arm) w (read-result (str prefix ".writer.edn"))
                r (read-result (str prefix ".reader.edn"))
                v (read-result (str prefix ".validation.edn"))
                batches (:batches w)]
            (assert (and (:verified v) (:passed r) (:full-physical-rows-equal r)))
            (assert (= [160000 5000 32] (mapv w [:rows :batch-size :rounds])))
            (assert (= arm (:experimental-codec-arm w)))
            (assert (= 32 (count batches)))
            (assert (every? #(and (:ok %) (= 5000 (:rows %)) (pos? (:nanos %))
                                  (= (:nanos %) (- (:end-nanos %) (:start-nanos %)))) batches))
            {:ordinal i :arm arm :writer w :recovered-rows (:rows r)
             :rate (:confirmed-rows-per-second w)
             :estimated-bytes-per-row (/ (get-in w [:counter-delta :scheme-heap-bytes-allocated]) 160000.0)
             :callback-ms (percentiles (map #(/ (:nanos %) 1e6) batches))}))
        (range 4) ["off" "on" "on" "off"]))
(def summaries
  (into {} (for [arm ["off" "on"]
                 :let [selected (filter #(= arm (:arm %)) arms)
                       writers (map :writer selected)
                       durations (mapcat #(map :nanos (:batches %)) writers)
                       latency (percentiles durations)]]
             [arm {:confirmed-window-rows-per-second
                   (/ (* 1000000000.0 (reduce + (map :rows writers)))
                      (reduce + (map :confirmed-window-nanos writers)))
                   :callback-ms (update-vals latency #(/ % 1e6))
                   :service-rows-per-second-at-callback-latency-percentile
                   (update-vals latency #(/ 5000000000000.0 %))
                   :estimated-bytes-per-row (/ (reduce + (map :estimated-bytes-per-row selected)) 2.0)
                   :callbacks (count durations)}])))
(def summary
  {:scope :actual-sdk-exporter-local-durable-explicit-codec-abba
   :order ["off" "on" "on" "off"] :summaries summaries
   :arms (mapv #(dissoc % :writer) arms)
   :pooled-on-over-off-rate (/ (get-in summaries ["on" :confirmed-window-rows-per-second])
                              (get-in summaries ["off" :confirmed-window-rows-per-second]))
   :control-end-over-start-rate (/ (:rate (last arms)) (:rate (first arms)))
   :estimated-allocation-reduction-fraction
   (- 1.0 (/ (get-in summaries ["on" :estimated-bytes-per-row])
             (get-in summaries ["off" :estimated-bytes-per-row])))
   :independently-recovered-rows (reduce + (map :recovered-rows arms))
   :runtime "7098a8d6" :native-library "26.9.0" :batch-size 5000
   :stock-runtime-full-consumer-qualified false :tail-qualified false
   :end-to-end-span-latency-measured false :all-success-cases-not-fault-qualification true
   :allocation-estimate-nonatomic-host-counters true})
(assert (= 700000 (:independently-recovered-rows summary)))
(spit (str root "/summary.edn") (pr-str summary))
(prn summary)
