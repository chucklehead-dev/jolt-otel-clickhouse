(require '[clojure.edn :as edn])
(let [r (edn/read-string (slurp "/home/chuck/ai-src/evidence/nested-writer-construction-screen-20261018.edn"))]
  (assert (= [5000 16 true] (mapv r [:rows :rounds :all-output-controls])))
  (prn {:scope (:scope r) :tail-qualified false :overlapping-components-not-additive true
        :components
        (into {} (for [arm [:constructor :empty :one-scalar :event]]
                   (let [rows (filter #(= arm (:arm %)) (:observations r))
                         times (vec (sort (map :nanos rows))) n (count rows)]
                     (assert (= 16 n))
                     [arm {:samples n
                           :latency-ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                                 (let [index (dec (long (Math/ceil (* q n))))]
                                                   [label (/ (double (nth times index)) 1e6)])))
                           :estimated-allocated-bytes-per-row
                           (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) rows)))
                              (* 5000 n))}])))}))
