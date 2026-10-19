(require '[clojure.edn :as edn] '[clojure.string :as str])
(let [reports
      (mapv (fn [i arm]
              (let [r (edn/read-string
                        (slurp (str "/home/chuck/ai-src/evidence/exporter-singleton-row-emission-abba-20261019-"
                                 i "-" arm ".edn")))
                    root (if (= arm "candidate") "exporter-singleton-event-columns-20261019"
                           "exporter-default-omission-json-20261018")]
                (assert (= arm (:exporter-arm r)))
                (assert (str/includes? (:exporter-resource r) root))
                (assert (str/includes? (:json-resource r) "2172061060a102ccab779f5adc5d77f8b83a81f6"))
                (assert (= [5000 28 32 8648398] (mapv r [:rows :columns :samples :payload-bytes])))
                (assert (= "862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39"
                          (:payload-sha256 r)))
                (assert (every? true? (map r [:ordinary-one-row-reference :changed-input-output-control
                                             :all-output-hashes-equal :native-batch-binding-context])))
                (assert (= {:lazy 16 :prepared 16} (frequencies (map :mode (:observations r)))))
                r)) (range 4) ["baseline" "candidate" "candidate" "baseline"])
      summarize
      (fn [arm mode]
        (let [rows (filter #(= mode (:mode %))
                     (mapcat :observations (filter #(= arm (:exporter-arm %)) reports)))
              times (vec (sort (map :nanos rows))) n (count rows)]
          (assert (= 32 n))
          {:samples n
           :latency-ms
           (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                      [label (/ (double (nth times (dec (long (Math/ceil (* q n)))))) 1e6)]))
           :estimated-allocated-bytes-per-row
           (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) rows)))
             (* 5000 n))}))]
  (prn {:scope :singleton-event-columns-full-encoding-component-abba-not-durable
        :order [:baseline :candidate :candidate :baseline]
        :runtime "7098a8d6" :json "2172061" :sdk "7878a2e"
        :baseline-exporter "4a42716" :candidate-exporter "2c54b98"
        :baseline-lazy (summarize "baseline" :lazy)
        :candidate-lazy (summarize "candidate" :lazy)
        :baseline-prepared (summarize "baseline" :prepared)
        :candidate-prepared (summarize "candidate" :prepared)
        :all-output-controls-passed true :tail-qualified false}))
