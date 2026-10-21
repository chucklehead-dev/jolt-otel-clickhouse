(require '[clojure.edn :as edn])
(defn relative-change [new-value old-value] (dec (/ new-value old-value)))
(assert (< (Math/abs (- 0.2 (relative-change 120.0 100.0))) 1.0e-12))
(assert (< (Math/abs (+ 0.2 (relative-change 80.0 100.0))) 1.0e-12))
(assert (zero? (relative-change 100.0 100.0)))
(let [[root output] *command-line-args*
      ordered (mapv (fn [name]
                      (let [w (edn/read-string (slurp (str root "/" name ".writer.edn")))
                            r (edn/read-string (slurp (str root "/" name ".reader.edn")))]
                        (assert (= [true 175000 true true 35]
                                   (mapv r [:passed :rows :full-physical-rows-equal :typed-values-status :per-record-multiplicity])))
                        (assert (= [175000 5000 32 3 35]
                                   (mapv w [:stored-rows :batch-size :rounds :warmups :nested-scope-calls])))
                        {:name name :arm (:nested-writer-arm w)
                         :confirmed-rows-per-second (:confirmed-rows-per-second w)
                         :window-nanos (:confirmed-window-nanos w)
                         :p50-p90-p95-p99-ms (:p50-p90-p95-p99-ms w)
                         :estimated-bytes-per-row (:estimated-bytes-per-row w)
                         :cpu-ms (/ (- (get-in w [:after :cpu-nanos]) (get-in w [:before :cpu-nanos])) 1.0e6)
                         :gc-count (- (get-in w [:after :gc-count]) (get-in w [:before :gc-count]))
                         :gc-ms (/ (- (get-in w [:after :gc-nanos]) (get-in w [:before :gc-nanos])) 1.0e6)}))
                    ["cached" "fused" "fused2" "cached2"])
      _ (assert (= ["cached" "fused-cached" "fused-cached" "cached"] (mapv :arm ordered)))
      control-drift (relative-change (:confirmed-rows-per-second (last ordered))
                                    (:confirmed-rows-per-second (first ordered)))
      report {:scope :actual-local-durable-fused-encoding-abba
              :same-executable true :independently-recovered-rows 700000
              :full-physical-old-reader-checks true :runs ordered
              :control-rate-drift-fraction control-drift
              :fused-allocation-increase-fraction
              (relative-change (get-in ordered [1 :estimated-bytes-per-row])
                               (get-in ordered [0 :estimated-bytes-per-row]))
              :causal-comparison-qualified false :target-qualified false
              :population-tail-qualified false :s3-qualified false
              :shared-candidate-encoder true :original-encoder-regression-isolated false}]
  (spit output (pr-str report))
  (prn report))
