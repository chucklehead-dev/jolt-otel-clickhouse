(require '[clojure.edn :as edn] '[clojure.string :as str])
(let [reports (mapv (fn [i arm]
                      (let [prefix (str "/home/chuck/ai-src/evidence/jolt-sorted-function-core-abba-20261018-"
                                        i "-" arm ".edn")
                            r (edn/read-string (slurp prefix))
                            mechanism (edn/read-string (slurp (str prefix ".mechanism.edn")))]
                        (assert (= arm (:json-arm r) (:arm mechanism)))
                        (assert (str/includes? (:json-resource r) "data-json-optional-stock-sorted-fold-20261018"))
                        (assert (= [5000 28 32 8648398] (mapv r [:rows :columns :samples :payload-bytes])))
                        (assert (= "862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39"
                                   (:payload-sha256 r)))
                        (assert (every? true? (map r [:ordinary-one-row-reference :changed-input-output-control
                                                    :all-output-hashes-equal :native-batch-binding-context])))
                        (assert (if (= arm "candidate")
                                  (< (:estimated-bytes-per-comparison mechanism) 1024)
                                  (> (:estimated-bytes-per-comparison mechanism) 2048)))
                        r)) (range 4) ["baseline" "candidate" "candidate" "baseline"])
      summarize (fn [arm]
                  (let [rows (filter #(= :lazy (:mode %))
                                     (mapcat :observations (filter #(= arm (:json-arm %)) reports)))
                        times (vec (sort (map :nanos rows))) n (count rows)]
                    (assert (= 32 n))
                    {:samples n
                     :latency-ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                           (let [index (dec (long (Math/ceil (* q n))))]
                                             [label (/ (double (nth times index)) 1e6)])))
                     :estimated-allocated-bytes-per-row
                     (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) rows)))
                        (* 5000 n))}))]
  (prn {:scope :sorted-function-core-overlay-full-encoding-not-durable
        :order [:baseline :candidate :candidate :baseline]
        :runtime "7098a8d6" :source-overlay true :candidate-core "388a3ad6"
        :json "96f3e47-unchanged-both-arms" :baseline (summarize "baseline")
        :candidate (summarize "candidate") :all-output-controls-passed true :tail-qualified false}))
