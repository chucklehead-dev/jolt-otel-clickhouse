(require '[clojure.edn :as edn] '[clojure.string :as str])
(let [reports
      (mapv (fn [i arm]
              (let [r (edn/read-string
                        (slurp (str "/home/chuck/ai-src/evidence/json-omission-publication-abba-20261019-"
                                 i "-" arm ".edn")))
                    root (if (= arm "candidate") "data-json-default-omission-publication-20261019"
                           "data-json-default-omission-identity-20261018")]
                (assert (= arm (:json-arm r)))
                (assert (str/includes? (:json-resource r) root))
                (assert (= [5000 28 32 8648398] (mapv r [:rows :columns :samples :payload-bytes])))
                (assert (= "862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39"
                          (:payload-sha256 r)))
                (assert (every? true? (map r [:ordinary-one-row-reference :changed-input-output-control
                                             :all-output-hashes-equal :native-batch-binding-context])))
                (assert (= {:lazy 16 :prepared 16} (frequencies (map :mode (:observations r)))))
                r))
        (range 4) ["baseline" "candidate" "candidate" "baseline"])
      summarize
      (fn [arm mode]
        (let [rows (filter #(= mode (:mode %))
                     (mapcat :observations (filter #(= arm (:json-arm %)) reports)))
              times (vec (sort (map :nanos rows))) n (count rows)]
          (assert (= 32 n))
          {:samples n
           :latency-ms
           (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                      [label (/ (double (nth times (dec (long (Math/ceil (* q n)))))) 1e6)]))
           :estimated-allocated-bytes-per-row
           (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) rows)))
             (* 5000 n))}))]
  (prn {:scope :omission-publication-full-encoding-component-abba-not-durable
        :order [:baseline :candidate :candidate :baseline]
        :runtime "7098a8d6" :baseline-json "2172061" :candidate-json "654c1f9"
        :baseline-lazy (summarize "baseline" :lazy)
        :candidate-lazy (summarize "candidate" :lazy)
        :baseline-prepared (summarize "baseline" :prepared)
        :candidate-prepared (summarize "candidate" :prepared)
        :all-output-controls-passed true :tail-qualified false}))
