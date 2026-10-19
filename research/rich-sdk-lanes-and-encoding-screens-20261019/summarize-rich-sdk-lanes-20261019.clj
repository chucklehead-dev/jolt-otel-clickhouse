(require '[clojure.edn :as edn])
(defn quantiles [values scale]
  (let [ordered (vec (sort values))]
    (assert (seq ordered))
    (into {} (for [[label p] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [label (/ (double (nth ordered (dec (int (Math/ceil (* p (count ordered))))))) scale)]))))
(let [arms
      (mapv (fn [[index lanes]]
              (let [prefix (str "/home/chuck/ai-src/evidence/rich-sdk-lanes-abba-20261019-" index "-lanes-" lanes)
                    w (edn/read-string (slurp (str prefix ".writer.edn")))
                    r (edn/read-string (slurp (str prefix ".reader.edn")))
                    validation (edn/read-string (slurp (str prefix ".validation.edn")))
                    batches (:batches w)
                    overlap? (boolean
                               (some true? (for [a batches b batches :when (not= (:lane a) (:lane b))]
                                             (and (< (:start-nanos a) (:end-nanos b))
                                                  (< (:start-nanos b) (:end-nanos a))))))]
                (assert (and (:verified validation) (:passed r) (= 175000 (:rows r))))
                (assert (= lanes (:lanes w)))
                (assert (= (= 2 lanes) overlap?))
                {:ordinal index :lanes lanes :callbacks (count batches)
                 :actual-streams (:stream-calls w) :callback-overlap-observed overlap?
                 :rows (:rows w) :window-nanos (:confirmed-window-nanos w)
                 :confirmed-window-rows-per-second (:confirmed-rows-per-second w)
                 :callback-ms (quantiles (map :nanos batches) 1e6)
                 :callback-rows (quantiles (map :rows batches) 1.0)
                 :estimated-allocated-bytes (get-in w [:counter-delta :scheme-heap-bytes-allocated])
                 :recovered-rows (:rows r)})) [[0 1] [1 2] [2 2] [3 1]])
      groups
      (into {} (for [[lanes xs] (group-by :lanes arms)]
                 [lanes {:processes (count xs)
                         :rows (reduce + (map :rows xs))
                         :pooled-confirmed-rows-per-second
                         (/ (* 1e9 (reduce + (map :rows xs))) (reduce + (map :window-nanos xs)))
                         :estimated-allocation-bytes-per-row
                         (/ (double (reduce + (map :estimated-allocated-bytes xs)))
                            (reduce + (map :rows xs)))}]))
      report {:scope :rich-sdk-fixed-trace-affinity-local-abba-not-product-default
              :runtime "7098a8d6" :exporter "2c54b98" :sdk "7878a2e" :json "2172061"
              :driver "3d766fa" :native-library "26.9.0" :rows-per-source-batch 5000
              :order [1 2 2 1] :total-queue-capacity 10000 :source-overlay false
              :old-stack-independent-full-recovery true
              :total-recovered-rows (reduce + (map :recovered-rows arms))
              :tail-qualified false :end-to-end-span-latency-measured false
              :s3-qualified false :warmups-on-first-exporter-only 3
              :groups groups :arms arms}]
  (assert (= 700000 (:total-recovered-rows report)))
  (spit "/home/chuck/ai-src/evidence/rich-sdk-lanes-abba-20261019.summary.edn" (pr-str report))
  (prn report))
