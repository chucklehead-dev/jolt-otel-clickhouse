(require '[clojure.edn :as edn])
(let [runs (mapv (fn [i arm]
                   (let [prefix (str "/home/chuck/ai-src/evidence/default-omission-sdk-abba-20261018-" i "-" arm)
                         w (edn/read-string (slurp (str prefix ".writer.edn")))
                         v (edn/read-string (slurp (str prefix ".validation.edn")))
                         r (edn/read-string (slurp (str prefix ".reader.edn")))]
                     (assert (and (:verified v) (:old-stack-independent-reader v) (:passed r)))
                     (assert (= [175000 175000 160000 32 35]
                                [(:recovered-rows v) (:rows r) (:rows w) (:rounds w) (:stream-calls w)]))
                     {:index i :arm arm :writer w :validation v}))
                 (range 4) ["baseline" "candidate" "candidate" "baseline"])
      summarize (fn [arm]
                  (let [selected (filter #(= arm (:arm %)) runs)
                        writers (map :writer selected)
                        batches (mapcat :batches writers)
                        times (vec (sort (map :nanos batches))) n (count times)
                        rows (reduce + (map :rows writers))
                        window (reduce + (map :confirmed-window-nanos writers))]
                    (assert (= [2 64 320000] [(count writers) n rows]))
                    {:process-repetitions 2 :batches n :rows rows
                     :confirmed-window-rows-per-second (/ (double (* rows 1000000000)) window)
                     :process-confirmed-rows-per-second (mapv :confirmed-rows-per-second writers)
                     :callback-ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                            (let [index (dec (long (Math/ceil (* q n))))]
                                              [label (/ (double (nth times index)) 1e6)])))
                     :estimated-allocated-bytes-per-row
                     (/ (double (reduce + (map #(get-in % [:counter-delta :scheme-heap-bytes-allocated]) writers))) rows)}))]
  (prn {:scope :local-sdk-to-durable-matched-json-library-abba
        :order [:baseline :candidate :candidate :baseline] :runtime "7098a8d6"
        :baseline-json "96f3e47" :candidate-json "2172061" :exporter "4d48871" :sdk "7878a2e"
        :baseline (summarize "baseline") :candidate (summarize "candidate")
        :independent-old-stack-recovered-rows 700000 :all-four-recoveries-passed true
        :source-overlay false :s3-qualified false :tail-qualified false}))
