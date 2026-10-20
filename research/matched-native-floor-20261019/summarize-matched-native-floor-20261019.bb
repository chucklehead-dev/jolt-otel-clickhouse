(require '[clojure.edn :as edn])
(defn read-edn [p] (edn/read-string (slurp p)))
(defn quantiles [xs]
  (let [v (vec (sort xs))]
    (into {} (for [[q p] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [q (/ (nth v (dec (int (Math/ceil (* p (count v)))))) 1e6)]))))
(let [[original corrected output] *command-line-args*
      paths [[original "0-sdk" 1] [original "1-native" 1]
             [original "2-native" 1] [original "3-sdk" 1] [corrected "0-native" 2]]
      arms (mapv (fn [[directory label version]]
                   (let [prefix (str directory "/" label)
                         w (read-edn (str prefix ".writer.edn"))
                         v (read-edn (str prefix ".validation.edn"))
                         ms (quantiles (map :nanos (:batches w)))
                         cpu (:os-process-window w)]
                     (assert (:verified v))
                     (assert (= 175000 (:recovered-rows v)))
                     {:label label :harness-version version :arm (:native-floor-arm w)
                      :rows (:rows w) :window-nanos (:confirmed-window-nanos w)
                      :confirmed-rows-per-second (:confirmed-rows-per-second w)
                      :batch-ms ms
                      :reciprocal-batch-rows-per-second (into {} (map (fn [[q ms]] [q (/ 5000000.0 ms)])) ms)
                      :estimated-bytes-per-row (/ (get-in w [:counter-delta :scheme-heap-bytes-allocated]) 160000.0)
                      :process-cpu-us-per-row (/ (* 1e6 (+ (:user-ticks-delta cpu) (:system-ticks-delta cpu)))
                                                 (* 160000.0 (:ticks-per-second cpu)))
                      :statement-sha256 (:statement-sha256 w)
                      :statement-bytes (:statement-bytes w) :prepared-wal-bytes (:prepared-wal-bytes w)
                      :recovered-rows (:recovered-rows v)})) paths)
      sdk (filter #(= "sdk" (:arm %)) arms)
      corrected-arm (last arms)
      _ (doseq [key [:statement-sha256 :statement-bytes :prepared-wal-bytes]]
          (assert (= 1 (count (set (map key arms))))))
      native-ms (/ (:window-nanos corrected-arm) 32e6)
      envelopes (mapv (fn [w]
                        (let [sdk-ms (/ (:window-nanos w) 32e6)
                              difference (- sdk-ms native-ms)]
                          {:sdk-control (:label w) :mean-sdk-window-ms-per-batch sdk-ms
                           :mean-corrected-native-window-ms-per-batch native-ms
                           :inferred-front-end-difference-ms difference
                           :conditional-ideal-two-stage-rows-per-second (/ 5000000.0 (max difference native-ms))})) sdk)
      statement (first arms)
      report {:scope :matched-preencoded-native-floor-not-sdk-throughput
              :all-captured-statement-and-wal-byte-counts-match true
              :statement-sha256 (:statement-sha256 statement)
              :statement-bytes (:statement-bytes statement) :prepared-wal-bytes (:prepared-wal-bytes statement)
              :total-independently-recovered-rows (reduce + (map :recovered-rows arms))
              :corrected-native-production-helper-path-included true
              :corrected-native-is-additional-run-not-repeated-abba true
              :source-and-acknowledgement-unchanged true
              :target-qualified false :tail-qualified false :s3-qualified false
              :conditional-two-stage-model envelopes
              :two-stage-assumptions "Fixed measured stage costs; enough CPU; no ordering, interference or scheduling penalty. Front difference includes SDK/producer/oracle costs, not only encoding. Not a measured pipeline."
              :dsl-10mbps-ideal-upload-only-wal-rows-per-second
              (/ 1250000.0 (/ (:prepared-wal-bytes statement) 5000.0))
              :wal-upload-mbps-at-25000-rows-per-second
              (/ (* 8.0 25000 (/ (:prepared-wal-bytes statement) 5000.0)) 1e6)
              :arms arms}]
  (assert (= 875000 (:total-independently-recovered-rows report)))
  (spit output (pr-str report))
  (doseq [arm arms] (prn arm))
  (prn (dissoc report :arms)))
