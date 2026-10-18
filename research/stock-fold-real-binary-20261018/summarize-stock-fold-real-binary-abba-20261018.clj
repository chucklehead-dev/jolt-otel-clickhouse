(require '[clojure.edn :as edn])
(defn read-report [prefix suffix]
  (edn/read-string (slurp (str prefix "." suffix ".edn"))))
(def runs
  (mapv (fn [index arm]
          (let [prefix (str "/home/chuck/ai-src/evidence/stock-fold-real-binary-abba-20261018-" index "-" (name arm))
                w (read-report prefix "writer")
                r (read-report prefix "reader")
                v (read-report prefix "validation")]
            (assert (and (:verified v) (:passed r) (= 175000 (:rows r))))
            (assert (= 32 (count (:batches w))))
            (assert (= 160000 (:rows w)))
            {:index index :arm arm :writer w :reader r}))
        (range 4) [:baseline :candidate :candidate :baseline]))
(defn percentiles [times]
  (let [ordered (vec (sort times)) n (count ordered)]
    (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [label (/ (double (nth ordered (dec (long (Math/ceil (* n q)))))) 1e6)]))))
(defn summarize [arm]
  (let [ws (mapv :writer (filter #(= arm (:arm %)) runs))
        times (mapcat #(map :nanos (:batches %)) ws)
        latency (percentiles times)
        rows (reduce + (map :rows ws))
        window (reduce + (map :confirmed-window-nanos ws))
        ;; This is the existing native counter estimate delta(live-heap+GC-bytes),
        ;; not a new allocation profiler or a cumulative live-heap counter.
        allocated (reduce + (map #(get-in % [:counter-delta :scheme-heap-bytes-allocated]) ws))]
    {:runs (count ws) :samples (count times) :measured-rows rows
     :confirmed-window-rows-per-second (/ (* 1e9 rows) window)
     :callback-ms latency :callback-max-ms (/ (double (apply max times)) 1e6)
     :callback-rows-per-second-at-latency-quantile
     (into {} (map (fn [[q ms]] [q (/ 5000000.0 ms)]) latency))
     :estimated-allocated-bytes-per-row (/ (double allocated) rows)
     :gc-count (reduce + (map #(get-in % [:counter-delta :gc-count]) ws))
     :gc-real-ms (/ (double (reduce + (map #(get-in % [:counter-delta :process-gc-real-nanos]) ws))) 1e6)}))
(let [baseline (summarize :baseline) candidate (summarize :candidate)]
  (prn {:scope :real-compiled-runtime-optional-stock-fold-abba
        :order [:baseline :candidate :candidate :baseline]
        :source-overlay false :all-independent-recoveries-passed true
        :physical-recovered-rows (reduce + (map #(get-in % [:reader :rows]) runs))
        :tail-qualified false :hosted-artifact-qualified false
        :fixed-exporter "4d48871" :fixed-sdk "7878a2e" :fixed-native-json "96f3e47"
        :baseline-runtime "1ae7e0d" :candidate-runtime "631984d6"
        :baseline baseline :candidate candidate
        :window-throughput-ratio (/ (:confirmed-window-rows-per-second candidate)
                                    (:confirmed-window-rows-per-second baseline))
        :estimated-allocation-reduction-fraction
        (- 1.0 (/ (:estimated-allocated-bytes-per-row candidate)
                  (:estimated-allocated-bytes-per-row baseline)))
        :per-run (mapv (fn [{:keys [index arm writer]}]
                         {:index index :arm arm
                          :confirmed-window-rows-per-second (:confirmed-rows-per-second writer)
                          :callback-ms (percentiles (map :nanos (:batches writer)))}) runs)}))
