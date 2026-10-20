(require '[clojure.edn :as edn])

(let [[prefix] *command-line-args*
      w (edn/read-string (slurp (str prefix ".writer.edn")))
      r (edn/read-string (slurp (str prefix ".reader.edn")))
      v (edn/read-string (slurp (str prefix ".validation.edn")))
      samples (vec (sort (map :nanos (:batches w))))
      rows (:rows w)
      process (:os-process-window w)
      batch-ms (into {} (for [[k p] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                         [k (/ (nth samples (dec (long (Math/ceil (* p (count samples)))))) 1e6)]))]
  (assert (:verified v))
  (assert (= 32 (count samples)))
  (assert (= [175000 true true true]
             (mapv r [:rows :passed :full-physical-rows-equal :typed-values-status])))
  (assert (= "862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39"
             (:statement-sha256 w)))
  (assert (= [8648398 10508410] (mapv w [:statement-bytes :prepared-wal-bytes])))
  (prn {:scope :single-sdk-functional-gate-not-abba
        :confirmed-rows-per-second (:confirmed-rows-per-second w)
        :batch-ms batch-ms
        :reciprocal-batch-rows-per-second
        (into {} (map (fn [[k ms]] [k (/ (* 1000.0 (:batch-size w)) ms)]) batch-ms))
        :estimated-managed-bytes-per-row
        (/ (get-in w [:counter-delta :scheme-heap-bytes-allocated]) (double rows))
        :process-cpu-us-per-row
        (/ (* 1e6 (+ (:user-ticks-delta process) (:system-ticks-delta process)))
           (* (:ticks-per-second process) (double rows)))
        :independently-recovered-rows (:rows r)
        :statement-sha256 (:statement-sha256 w)
        :target-qualified false :tail-qualified false :s3-qualified false}))
