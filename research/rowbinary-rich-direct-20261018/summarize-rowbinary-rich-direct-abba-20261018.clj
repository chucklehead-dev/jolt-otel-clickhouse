(require '[clojure.edn :as edn])
(defn percentiles [times]
  (let [ordered (vec (sort times)) n (count ordered)]
    (assert (pos? n))
    (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [label (/ (double (nth ordered (dec (long (Math/ceil (* n q)))))) 1e6)]))))
(def runs
  (mapv (fn [index arm]
          (let [prefix (str "/home/chuck/ai-src/evidence/rowbinary-rich-direct-abba-20261018-" index "-" (name arm))
                w (edn/read-string (slurp (str prefix ".writer.edn")))
                r (edn/read-string (slurp (str prefix ".reader.edn")))]
            (assert (= :writer-passed (:gate-status w)))
            (assert (= arm (:arm w)))
            (assert (= 32 (:samples w) (count (:observations w))))
            (assert (= (vec (range 32)) (mapv :ordinal (:observations w))))
            (assert (every? #(and (= arm (:arm %)) (= 5000 (:rows %)) (pos? (:nanos %)))
                            (:observations w)))
            (assert (= (if (= arm :json) [35 0] [0 35])
                       (mapv w [:native-streams :binary-queries])))
            (assert (and (:passed r) (:full-physical-rows-equal r) (:typed-values-status r)))
            (assert (= [175000 5000 625 35]
                       (mapv r [:rows :groups :trace-count :per-record-multiplicity])))
            (assert (false? (:sdk-queue w)))
            (assert (false? (:runtime-b64-replaced w)))
            {:index index :arm arm :writer w :reader r}))
        (range 4) [:json :rowbinary :rowbinary :json]))
(defn summarize [arm]
  (let [ws (mapv :writer (filter #(= arm (:arm %)) runs))
        observations (mapcat :observations ws)
        phases (mapcat :phases ws)
        times (map :nanos observations)
        allocation (fn [records] (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) records)))]
    (assert (= 64 (count observations)))
    (doseq [w ws]
      (assert (= (if (= arm :json) {:projection-and-encoding 32 :native-and-confirm 32}
                     {:projection-and-encoding 32 :framing 32 :native-and-confirm 32})
                 (frequencies (map :phase (:phases w))))))
    {:runs (count ws) :samples (count times)
     :callback-rows-per-second (/ (* 1e9 5000 (count times)) (reduce + times))
     :callback-ms (percentiles times)
     :estimated-allocated-bytes-per-row (/ (double (allocation observations)) (* 5000 (count observations)))
     :phases (into {} (for [[phase records] (group-by :phase phases)]
                        [phase {:latency-ms (percentiles (map :nanos records))
                                :estimated-allocated-bytes-per-row
                                (/ (double (allocation records)) (* 5000 (count records)))}]))
     :sizes (mapv :sizes ws)}))
(let [json (summarize :json) binary (summarize :rowbinary)]
  (prn {:scope :experimental-rich-format-best-native-path-direct-callback-abba
        :order [:json :rowbinary :rowbinary :json] :tail-qualified false
        :same-rich-fixture true :sdk-queue false :otlp-ingestion false
        :runtime "631984d6" :exporter "4d48871" :sdk "7878a2e" :json "96f3e47" :driver "3d766fa"
        :json-native-path :default-owned-compact-stream
        :rowbinary-native-path :ordinary-owned-query-with-explicit-16MiB-open-seam
        :rowbinary-kernel :experimental-native-fixed-schema
        :rowbinary-base64 :experimental-local-flat-string-kernel-no-global-rebinding
        :all-independent-recoveries-passed true :physical-recovered-rows 700000
        :json-result json :rowbinary-result binary
        :callback-throughput-ratio (/ (:callback-rows-per-second binary) (:callback-rows-per-second json))
        :notes ["JSON projection/encoding phase includes prefix and final-output framing."
                "RowBinary framing is separate; do not call its encoding bucket equivalent to JSON's."
                "Both native-and-confirm brackets contain the entire Durable execute-owned-and-flush! call, including native transfer."
                "Two process repeats per arm; percentiles are descriptive, not service-tail qualification."]}))
