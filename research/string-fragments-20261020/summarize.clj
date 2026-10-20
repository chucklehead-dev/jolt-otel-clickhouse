;; Babashka, scalar summaries only. Input: completed ABBA output directory.
(require '[clojure.edn :as edn])
(let [directory (first *command-line-args*)
      arms [[0 "off"] [1 "on"] [2 "on"] [3 "off"]]
      reports
      (mapv (fn [[index arm]]
              (let [prefix (str directory "/" index "-" arm)
                    x (edn/read-string (slurp (str prefix ".writer.edn")))
                    validation (edn/read-string (slurp (str prefix ".validation.edn")))
                    samples (vec (sort (map :nanos (:batches x))))
                    q (fn [p] (nth samples (dec (long (Math/ceil (* p (count samples)))))))]
                (assert (= arm (:string-fragments-arm x)))
                (assert (= "on" (:shape-plans-arm x)))
                (assert (= 32 (count samples)))
                (assert (= 160000 (:rows x)))
                (assert (= 175000 (:recovered-rows validation)))
                (assert (every? :ok (:batches x)))
                (assert (every? #(and (zero? (:queue-size %))
                                     (zero? (:failed-span-count %))
                                     (zero? (:dropped-count %))) (:stats x)))
                {:index index :arm arm
                 :confirmed-rows-per-second (:confirmed-rows-per-second x)
                 :estimated-bytes-per-row (/ (get-in x [:counter-delta :scheme-heap-bytes-allocated])
                                              (double (:rows x)))
                 :callback-ms (mapv #(/ (q %) 1e6) [0.50 0.90 0.95 0.99])
                 :inverse-callback-rows-per-second (mapv #(/ 5e12 (q %)) [0.50 0.90 0.95 0.99])
                 :window-nanos (:confirmed-window-nanos x)})) arms)
      by-arm (group-by :arm reports)
      rate (fn [arm] (/ 320000e9 (reduce + (map :window-nanos (by-arm arm)))))
      off (rate "off") on (rate "on")]
  (prn {:scope :local-durable-string-fragment-abba :reports reports
        :pooled-confirmed-rows-per-second {:off off :on on}
        :pooled-improvement-percent (* 100 (dec (/ on off)))
        :causal-improvement-established false
        :control-drift-percent (* 100 (dec (/ (:confirmed-rows-per-second (last reports))
                                               (:confirmed-rows-per-second (first reports)))))
        :recovered-rows 700000 :tail-qualified false :s3-qualified false
        :live-app-capture false}))
