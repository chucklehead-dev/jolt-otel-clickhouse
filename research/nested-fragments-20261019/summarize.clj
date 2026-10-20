(require '[clojure.edn :as edn])
(defn quantile [xs q]
  (nth (vec (sort xs)) (max 0 (dec (long (Math/ceil (* q (count xs))))))))
(let [root (first *command-line-args*)
      records (mapv (fn [index arm]
                      (let [prefix (str root "/" index "-" arm)
                            w (edn/read-string (slurp (str prefix ".writer.edn")))
                            r (edn/read-string (slurp (str prefix ".reader.edn")))
                            v (edn/read-string (slurp (str prefix ".validation.edn")))
                            samples (map :nanos (:batches w))]
                        (assert (= arm (:nested-fragments-arm w)))
                        (assert (and (:verified v) (:passed r) (= 175000 (:rows r))))
                        {:index index :arm arm :window-nanos (:confirmed-window-nanos w)
                         :rows (:rows w) :samples (count samples) :recovered-rows (:rows r)
                         :confirmed-window-rows-per-second (:confirmed-rows-per-second w)
                         :callback-ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                                [label (/ (quantile samples q) 1e6)]))
                         :reciprocal-batch-rates-not-throughput-quantiles
                         (into {} (for [[label q] [[:p50-latency 0.5] [:p90-latency 0.9]
                                                  [:p95-latency 0.95] [:p99-latency 0.99]]]
                                    [label (/ 5e12 (quantile samples q))]))
                         :estimated-bytes-per-row (/ (get-in w [:counter-delta :scheme-heap-bytes-allocated])
                                                    (double (:rows w)))
                         :process-cpu-micros-per-row
                         (/ (* 1e6 (+ (get-in w [:os-process-window :user-ticks-delta])
                                      (get-in w [:os-process-window :system-ticks-delta])))
                            (* (:rows w) (get-in w [:os-process-window :ticks-per-second])))}))
                    (range 4) ["off" "on" "on" "off"])
      pooled (into {} (for [arm ["off" "on"]
                           :let [selected (filter #(= arm (:arm %)) records)]]
                       [arm (/ (* 1e9 (reduce + (map :rows selected)))
                               (reduce + (map :window-nanos selected)))]))
      drift (* 100.0 (Math/abs (- (/ (:confirmed-window-rows-per-second (last records))
                                                  (:confirmed-window-rows-per-second (first records))) 1.0)))
      effect (* 100.0 (- (/ (get pooled "on") (get pooled "off")) 1.0))
      result {:scope :real-sdk-local-durable-nested-fragments-abba :runtime :official-stock-0.8.23
              :rows-independently-recovered (reduce + (map :recovered-rows records))
              :records records :pooled-confirmed-window-rows-per-second pooled
              :baseline-drift-percent drift :pooled-change-percent effect
              :qualified-throughput-win false :tail-qualified false :target-qualified false}]
  (spit (str root "/summary.edn") (pr-str result))
  (doseq [record records] (prn record))
  (prn (dissoc result :records)))
