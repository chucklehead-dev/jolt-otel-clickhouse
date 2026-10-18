(require '[clojure.edn :as edn])
(let [report (edn/read-string (slurp "/home/chuck/ai-src/evidence/nested-json-text-vs-byte-screen-20261018.edn"))
      _ (assert (= [5000 32 true true]
                   (mapv report [:rows :samples :ordinary-all-value-reference :all-values-equal])))
      summarize (fn [arm]
                  (let [rows (filter #(= arm (:arm %)) (:observations report))
                        times (vec (sort (map :nanos rows)))
                        _ (assert (= 16 (count rows)))]
                    {:samples (count rows)
                     :latency-ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                           [label (/ (double (nth times (dec (long (Math/ceil (* q (count times))))))) 1e6)]))
                     :estimated-allocated-bytes-per-row
                     (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) rows)))
                        (* 5000 (count rows)))}))]
  (prn {:scope (:scope report) :byte (summarize :byte) :text (summarize :text)
        :reference-hash (:reference-hash report) :all-values-equal true
        :selection :keep-existing-byte-backend :tail-qualified false}))
