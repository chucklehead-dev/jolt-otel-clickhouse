(require '[clojure.edn :as edn])
(let [reports (mapv (fn [i arm]
                      (let [r (edn/read-string
                                (slurp (str "/home/chuck/ai-src/evidence/stock-0823-row-preparation-linkage-"
                                            i "-" arm "-20261018.edn")))]
                        (assert (= arm (:arm r)))
                        (assert (= [5000 true] (mapv r [:rows :all-rows-equal])))
                        (assert (= 16 (count (:observations r))))
                        r)) (range 4) ["open" "direct" "direct" "open"])
      _ (assert (apply = (map :row-reference-sha256 reports)))
      summary (fn [arm]
                (let [reports (filter #(= arm (:arm %)) reports)
                      rows (mapcat :observations reports)
                      times (vec (sort (map :nanos rows))) n (count rows)]
                  (assert (= 32 n))
                  {:samples n
                   :latency-ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                         [label (/ (double (nth times (dec (long (Math/ceil (* q n)))))) 1e6)]))
                   :estimated-allocated-bytes-per-row
                   (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) rows))) (* 5000 n))
                   :attribute-root-controls (mapv :attribute-root-live? reports)
                   :json-root-controls (mapv :json-root-live? reports)}))]
  (prn {:scope :stock-0823-configured-json-row-preparation-not-durable
        :order [:open :direct :direct :open] :open (summary "open") :direct (summary "direct")
        :all-row-references-equal true :tail-qualified false
        :not-comparable-to-native-writer-durable-throughput true}))
