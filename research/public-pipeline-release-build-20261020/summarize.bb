;; Read small completed writer/independent-reader receipts; never opens a store.
(require '[clojure.edn :as edn])
(let [[writer reader] *command-line-args*
      w (edn/read-string (slurp writer))
      r (edn/read-string (slurp reader))
      samples (:samples w)
      times (vec (sort (map :nanos samples)))
      q (fn [p] (nth times (dec (long (Math/ceil (* p (count times)))))))
      total (* 5000 (+ 3 32))]
  (assert (= :public-synchronous-exporter-source-release (:scope w)))
  (assert (= [5000 3 32] ((juxt :batch-size :warmups :rounds) w)))
  (assert (= 32 (count samples)))
  (assert (= (vec (range 32)) (mapv :round samples)))
  (assert (every? #(and (true? (:ok %)) (= 5000 (:rows %))
                       (pos-int? (:nanos %))) samples))
  (assert (= 160000 (:rows w)))
  (assert (>= (:confirmed-window-nanos w) (reduce + times)))
  (assert (= total (:stored-rows w) (:rows r)))
  (assert (= [true 5000 625 true true 35 true]
             ((juxt :passed :groups :trace-count :full-physical-rows-equal
                    :typed-values-status :per-record-multiplicity :deterministic-tie-order) r)))
  (assert (false? (:sdk-queue-used w)))
  (assert (false? (:private-var-probes w)))
  (assert (= "26.9.0" (:native-package w)))
  (assert (false? (:tail-qualified w)))
  (prn {:scope :verified-public-synchronous-exporter-receipts
        :mode (:mode w) :independently-recovered-rows total
        :confirmed-window-rows-per-second (/ 160000e9 (:confirmed-window-nanos w))
        :p50-p90-p95-p99-ms (mapv #(/ (q %) 1e6) [0.5 0.9 0.95 0.99])
        :inverse-p50-p90-p95-p99-rows-per-second
        (mapv #(/ 5000e9 (q %)) [0.5 0.9 0.95 0.99])
        :estimated-bytes-per-row (:estimated-bytes-per-row w)
        :samples 32 :sample-p99-is-maximum true :population-tail-qualified false
        :sdk-queue-qualified false :dependency-provenance-external-to-receipts true}))
