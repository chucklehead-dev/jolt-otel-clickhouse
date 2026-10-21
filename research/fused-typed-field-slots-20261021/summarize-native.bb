(require '[clojure.edn :as edn])

(let [root (or (first *command-line-args*)
               "research/fused-typed-field-slots-20261021/native-results")
      names ["A1" "B1" "B2" "A2"]
      writers (mapv #(edn/read-string (slurp (str root "/" % "/writer.edn"))) names)
      verified (mapv #(edn/read-string (slurp (str root "/" % "/validated.edn"))) names)
      quantiles (fn [samples]
                  (let [xs (vec (sort (map :nanos samples)))]
                    (mapv #(/ (nth xs (dec (long (Math/ceil (* % (count xs)))))) 1e6)
                          [0.5 0.9 0.95 0.99])))
      summarize (fn [ws]
                  (let [samples (vec (mapcat :samples ws))
                        ms (quantiles samples)]
                    {:measured-batches (count samples)
                     :confirmed-window-rows-per-second
                     (/ (* 5000.0 (count samples))
                        (/ (reduce + (map :confirmed-window-nanos ws)) 1e9))
                     :p50-p90-p95-p99-ms ms
                     :inverse-latency-rows-per-second (mapv #(/ 5e6 %) ms)
                     :mean-run-estimated-bytes-per-row
                     (/ (reduce + (map :estimated-bytes-per-row ws)) (count ws))}))
      change (fn [old new] (* 100.0 (dec (/ new old))))
      rates (mapv :confirmed-rows-per-second writers)]
  (assert (= ["indexed" "fused" "fused" "indexed"] (mapv :field-slots-arm writers)))
  (assert (every? :verified verified))
  (assert (= [175000 175000 175000 175000]
             (mapv :independently-recovered-rows verified)))
  (prn {:order names
        :independently-recovered-rows (reduce + (map :independently-recovered-rows verified))
        :arms (zipmap names (map #(summarize [%]) writers))
        :pooled-descriptive {:indexed (summarize [(writers 0) (writers 3)])
                             :fused (summarize [(writers 1) (writers 2)])}
        :forward-pair-confirmed-rate-change-percent (change (rates 0) (rates 1))
        :reverse-pair-confirmed-rate-change-percent (change (rates 3) (rates 2))
        :baseline-rate-drift-percent (change (rates 0) (rates 3))
        :scope :same-binary-serial-public-exporter-local-durable
        :sdk-queue-qualified false :s3-qualified false
        :population-tail-qualified false :target-qualified false}))
