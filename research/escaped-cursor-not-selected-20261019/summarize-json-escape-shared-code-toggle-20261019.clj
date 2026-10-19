(require '[clojure.edn :as edn])
(let [r (edn/read-string (slurp "/home/chuck/ai-src/evidence/json-escape-shared-code-toggle-20261019.edn"))
      control (edn/read-string (slurp "/home/chuck/ai-src/evidence/json-escape-shared-code-toggle-control-20261019.log"))
      os (:observations r)
      _ (assert (= [true 2 [{:fast? false :direct-escapes 0 :output-equal true}
                            {:fast? true :direct-escapes 3 :output-equal true}]]
                  ((juxt :passed :loader-calls :controls) control)))
      _ (assert (and (:source-overlay r) (:same-compiled-factory-body r)
                     (:all-output-hashes-equal r) (:ordinary-one-row-reference r)
                     (:changed-input-output-control r)))
      _ (assert (= [5000 64 8648398] ((juxt :rows :samples :payload-bytes) r)))
      _ (assert (= "862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39"
                  (:payload-sha256 r)))
      summarize (fn [fast? mode]
                  (let [rows (filter #(and (= fast? (:fast-escape? %)) (= mode (:mode %))) os)
                        times (vec (sort (map :nanos rows))) n (count rows)]
                    (assert (= 16 n))
                    {:samples n
                     :latency-ms
                     (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                [label (/ (double (nth times (dec (long (Math/ceil (* q n)))))) 1e6)]))
                     :estimated-allocated-bytes-per-row
                     (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) rows)))
                        (* 5000 n))}))]
  (prn {:scope :same-process-shared-code-escape-toggle-not-durable
        :source-overlay true :same-compiled-factory-body true
        :live-entry-and-branch-controls-passed true :all-output-controls-passed true
        :runtime "7098a8d6" :json "4ae3388"
        :off-lazy (summarize false :lazy) :on-lazy (summarize true :lazy)
        :off-prepared (summarize false :prepared) :on-prepared (summarize true :prepared)
        :tail-qualified false :not-a-product-runtime-qualification true}))
