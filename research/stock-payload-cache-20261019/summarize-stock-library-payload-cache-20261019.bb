(require '[clojure.edn :as edn])
(defn read-edn [path] (edn/read-string (slurp path)))
(defn quantiles [values]
  (let [xs (vec (sort values))]
    (into {} (for [[label p] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [label (/ (nth xs (dec (int (Math/ceil (* p (count xs)))))) 1e6)]))))
(let [[directory output] *command-line-args*
      arms (mapv (fn [[index arm]]
                   (let [prefix (str directory "/" index "-" arm)
                         w (read-edn (str prefix ".writer.edn"))
                         r (read-edn (str prefix ".reader.edn"))
                         v (read-edn (str prefix ".validation.edn"))
                         ms (quantiles (map :nanos (:batches w)))
                         os (:os-process-window w)]
                     (assert (and (= arm (:payload-cache-arm w)) (:verified v)
                                  (:passed r) (:full-physical-rows-equal r)))
                     (assert (= [160000 32 5000 1 175000]
                                [(:rows w) (count (:batches w)) (:batch-size w) (:lanes w) (:rows r)]))
                     {:ordinal index :arm arm :rows (:rows w)
                      :window-nanos (:confirmed-window-nanos w)
                      :confirmed-rows-per-second (:confirmed-rows-per-second w)
                      :callback-ms ms
                      :batch-rows-per-second-at-latency-quantile
                      (into {} (map (fn [[q ms]] [q (/ 5000000.0 ms)])) ms)
                      :estimated-bytes-per-row
                      (/ (get-in w [:counter-delta :scheme-heap-bytes-allocated]) (double (:rows w)))
                      :process-cpu-us-per-row
                      (/ (* 1e6 (+ (:user-ticks-delta os) (:system-ticks-delta os)))
                         (* (:ticks-per-second os) (double (:rows w))))
                      :recovered-rows (:rows r)})) [[0 "off"] [1 "on"] [2 "on"] [3 "off"]])
      pooled (fn [arm] (let [xs (filter #(= arm (:arm %)) arms)]
                        (/ (* 1e9 (reduce + (map :rows xs))) (reduce + (map :window-nanos xs)))))
      controls (map :confirmed-rows-per-second (filter #(= "off" (:arm %)) arms))
      report {:scope :stock-library-cache-sdk-local-durable-abba
              :runtime "official-stock-0.8.23" :native-library "26.9.0"
              :pooled-off-rows-per-second (pooled "off") :pooled-on-rows-per-second (pooled "on")
              :observed-rate-ratio (/ (pooled "on") (pooled "off"))
              :control-drift-ratio (/ (apply max controls) (apply min controls))
              :total-independently-recovered-rows (reduce + (map :recovered-rows arms))
              :tail-qualified false :target-qualified false :s3-qualified false
              :end-to-end-span-latency-measured false :local-source-overrides true :arms arms}]
  (spit output (pr-str report))
  (doseq [arm arms] (prn arm))
  (prn (dissoc report :arms)))
