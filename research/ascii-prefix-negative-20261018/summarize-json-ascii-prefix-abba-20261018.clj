(require '[clojure.edn :as edn] '[clojure.string :as str])
(defn latency [rows selector]
  (let [v (vec (sort (map selector rows))) n (count v)]
    (assert (pos? n))
    (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [label (/ (double (nth v (dec (long (Math/ceil (* q n)))))) 1e6)]))))
(def reports
  (mapv (fn [index arm]
          (let [path (str "/home/chuck/ai-src/evidence/json-ascii-prefix-abba-20261018-" index "-" arm ".edn")
                r (edn/read-string (slurp path))
                root (if (= arm "candidate") "data-json-ascii-byte-scratch-string-20261018"
                         "data-json-optional-stock-sorted-fold-20261018")]
            (assert (= arm (:json-arm r)))
            (assert (str/includes? (:json-resource r) root))
            (assert (= [5000 28 32] (mapv r [:rows :columns :samples])))
            (assert (= {:lazy 16 :prepared 16} (frequencies (map :mode (:observations r)))))
            (assert (every? true? (map r [:ordinary-one-row-reference :changed-input-output-control
                                        :all-output-hashes-equal :native-batch-binding-context])))
            (assert (= "862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39"
                       (:payload-sha256 r)))
            (assert (= 8648398 (:payload-bytes r)))
            r)) (range 4) ["baseline" "candidate" "candidate" "baseline"]))
(defn summary [arm mode]
  (let [rows (mapcat :observations (filter #(= arm (:json-arm %)) reports))
        rows (filter #(= mode (:mode %)) rows)
        allocation (fn [selector] (/ (double (reduce + (map selector rows))) (* 5000 (count rows))))]
    (assert (= 32 (count rows)))
    {:samples 32 :latency-ms (latency rows :nanos)
     :estimated-allocated-bytes-per-row (allocation #(get-in % [:counters :scheme-heap-bytes-allocated]))
     :preparation-ms (when (= mode :prepared) (latency rows #(get-in % [:preparation :nanos])))
     :preparation-allocated-bytes-per-row
     (when (= mode :prepared) (allocation #(get-in % [:preparation :counters :scheme-heap-bytes-allocated])))}))
(prn {:scope :ascii-prefix-component-abba-not-durable
      :order [:baseline :candidate :candidate :baseline]
      :baseline-json "96f3e47" :candidate-json "a3799fb" :runtime "7098a8d6"
      :baseline-lazy (summary "baseline" :lazy) :candidate-lazy (summary "candidate" :lazy)
      :baseline-prepared (summary "baseline" :prepared) :candidate-prepared (summary "candidate" :prepared)
      :all-output-controls-passed true :tail-qualified false
      :selection :not-promoted-no-demonstrated-throughput-benefit})
