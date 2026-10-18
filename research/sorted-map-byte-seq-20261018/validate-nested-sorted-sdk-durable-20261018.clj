(require '[clojure.edn :as edn])
(let [prefix (first *command-line-args*)
      read-report #(edn/read-string (slurp (str prefix %)))
      w (read-report ".writer.edn") p (read-report ".paired.edn") r (read-report ".reader.edn")
      xs (:observations p) batches (sort-by :start-nanos (:batches w))
      summary (fn [samples]
                (let [s (vec (sort (map :nanos samples))) n (count s)]
                  {:samples n
                   :mean-ms (/ (double (reduce + (map :nanos samples))) n 1e6)
                   :ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                  [label (/ (double (nth s (dec (long (Math/ceil (* n q)))))) 1e6)]))
                   :managed-B-per-row
                   (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) samples)))
                      (* n 5000))}))]
  (assert (= [160000 5000 32 1 1 1 10000 625 8 "saturated"]
             (mapv w [:rows :batch-size :rounds :lanes :distinct-exporter-count
                      :shared-connection-owner-count :total-queue-capacity
                      :trace-count :siblings-per-trace :arrival])))
  (assert (= [{:queue-size 0 :attempted-span-count 160000 :exported-span-count 160000
              :failed-span-count 0 :dropped-count 0}] (:stats w)))
  (assert (and (:per-trace-counts-checked w) (:per-trace-callback-order-checked w)))
  (assert (= 32 (count xs) (count batches)))
  (assert (= (vec (range 32)) (mapv :ordinal xs)))
  (assert (= (vec (take 32 (cycle [:baseline :candidate :candidate :baseline]))) (mapv :arm xs)))
  (assert (every? #(= 5000 (:rows %)) xs))
  (assert (= 160000 (reduce + (map :rows batches))))
  (assert (= 35 (:stream-calls w)))
  (assert (every? :ok batches))
  (assert (every? (fn [[a b]] (<= (:end-nanos a) (:start-nanos b))) (partition 2 1 batches)))
  (doseq [[x b] (map vector xs batches)]
    (assert (<= (:start-nanos b) (:start-nanos x) (:end-nanos x) (:end-nanos b))))
  (assert (= {:projection-and-encoding 32 :native-and-confirm 32}
             (frequencies (map :phase (:phases p)))))
  (doseq [phase (:phases p)]
    (let [x (nth xs (:ordinal phase))]
      (assert (= (:arm x) (:arm phase)))
      (assert (<= (:start-nanos x) (:start-nanos phase) (:end-nanos phase) (:end-nanos x)))))
  (assert (and (:passed r) (:full-physical-rows-equal r) (:typed-values-status r)
               (= [175000 5000 625 35] (mapv r [:rows :groups :trace-count :per-record-multiplicity]))))
  (assert (and (false? (:force-flush-before-measurement w)) (false? (:tail-qualified w))
               (false? (:tail-qualified p))))
  (prn {:verified true :scope :same-queue-connection-event-rich-abba-not-throughput
        :independent-old-stack-reader true :event-timestamps-compared-as-exact-stored-ticks true
        :recovered-rows 175000 :tail-qualified false
        :insert (into {} (for [[arm rows] (group-by :arm xs)] [arm (summary rows)]))
        :phases (into {} (for [[label rows] (group-by (juxt :phase :arm) (:phases p))]
                          [label (summary rows)]))}))
