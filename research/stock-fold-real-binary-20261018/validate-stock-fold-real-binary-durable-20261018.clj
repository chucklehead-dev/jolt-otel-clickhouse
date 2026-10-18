(require '[clojure.edn :as edn])
(let [prefix (first *command-line-args*)
      w (edn/read-string (slurp (str prefix ".writer.edn")))
      r (edn/read-string (slurp (str prefix ".reader.edn")))
      batches (vec (sort-by :start-nanos (:batches w)))
      times (vec (sort (map :nanos batches)))
      n (count times)]
  (assert (= [160000 5000 32 1 1 1 10000 625 8 "saturated"]
             (mapv w [:rows :batch-size :rounds :lanes :distinct-exporter-count
                      :shared-connection-owner-count :total-queue-capacity
                      :trace-count :siblings-per-trace :arrival])))
  (assert (= [{:queue-size 0 :attempted-span-count 160000 :exported-span-count 160000
               :failed-span-count 0 :dropped-count 0}] (:stats w)))
  (assert (and (:per-trace-counts-checked w) (:per-trace-callback-order-checked w)))
  (assert (= 32 n))
  (assert (= 35 (:stream-calls w)))
  (assert (every? #(and (= 5000 (:rows %)) (:ok %)) batches))
  (assert (every? (fn [[a b]] (<= (:end-nanos a) (:start-nanos b))) (partition 2 1 batches)))
  (assert (and (false? (:force-flush-before-measurement w)) (false? (:tail-qualified w))))
  (assert (and (:passed r) (:full-physical-rows-equal r) (:typed-values-status r)
               (= [175000 5000 625 35] (mapv r [:rows :groups :trace-count :per-record-multiplicity]))))
  (prn {:verified true :scope :real-local-binary-sdk-queue-candidate-only
        :source-overlay false :hosted-artifact-qualified false :tail-qualified false
        :old-stack-independent-reader true :recovered-rows 175000
        :samples n :confirmed-window-rows-per-second (:confirmed-rows-per-second w)
        :callback-ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                               [label (/ (double (nth times (dec (long (Math/ceil (* n q)))))) 1e6)]))}))
