(require '[clojure.edn :as edn])
(def prefix "/home/chuck/ai-src/evidence/current-rich-sdk-worker-phase-20261019")
(def p (edn/read-string (slurp (str prefix ".worker-profile.edn"))))
(def w (edn/read-string (slurp (str prefix ".writer.edn"))))
(def r (edn/read-string (slurp (str prefix ".reader.edn"))))
(defn latencies [xs]
  (let [v (vec (sort xs)) n (count v)]
    (assert (= 32 n))
    (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
               [label (/ (double (nth v (dec (long (Math/ceil (* q n)))))) 1e6)]))))
(assert (= [32 35 32] ((juxt :samples :owner-calls :ignored-wrapper-calls) p)))
(assert (and (:public-json-writer-roots-unchanged p) (:default-native-executor-preserved p)
             (:full-callback-and-outer-boundaries p)))
(assert (= [175000 5000 625 35] ((juxt :rows :groups :trace-count :per-record-multiplicity) r)))
(assert (and (:passed r) (:full-physical-rows-equal r) (:typed-values-status r)))
(assert (= [160000 32 35] ((juxt :rows :rounds :stream-calls) w)))
(def by-phase (group-by :phase (:events p)))
(assert (= #{:callback :projection-and-encoding :native-and-confirm
             :native-classify :native-stream :wal-prepare :wal-append :wal-join
             :wal-publish :head-commit :file-digest :wal-immutable-put
             :wal-immutable-verify :wal-head-cas} (set (keys by-phase))))
(doseq [[_ events] by-phase]
  (assert (= 32 (count events)))
  (assert (= (vec (range 32)) (mapv :ordinal events)))
  (assert (every? #(= :complete (:status %)) events)))
(def correlated
  (mapv (fn [i]
          (let [callback (:nanos (nth (:callback by-phase) i))
                encoding (:nanos (nth (:projection-and-encoding by-phase) i))
                native (:nanos (nth (:native-and-confirm by-phase) i))
                remainder (- callback encoding native)]
            ;; These two outer API calls are sequential within this callback.
            ;; Never subtract separately computed phase medians.
            (assert (not (neg? remainder)))
            {:ordinal i :callback-nanos callback :encoding-nanos encoding
             :native-confirm-nanos native :other-callback-nanos remainder}))
    (range 32)))
(def totals (into {} (for [k [:callback-nanos :encoding-nanos :native-confirm-nanos :other-callback-nanos]]
                      [k (reduce + (map k correlated))])))
(prn {:scope :instrumented-real-sdk-worker-correlated-phases-not-throughput-qualification
      :runtime "7098a8d6" :exporter "2c54b98" :sdk "7878a2e" :json "2172061"
      :driver "3d766fa" :owner-calls 35 :ignored-wrapper-calls 32 :samples 32
      :independent-old-stack-recovery-passed true :physical-rows 175000
      :outer-shares-of-measured-callback
      (into {} (for [k [:encoding-nanos :native-confirm-nanos :other-callback-nanos]]
                 [k (/ (double (get totals k)) (:callback-nanos totals))]))
      :correlated-callbacks correlated
      :phases (into {} (for [[phase events] by-phase]
                        [phase {:latency-ms (latencies (map :nanos events))
                                :threads (frequencies (map :thread events))}]))
      :tail-qualified false :source-overlay false
      :notes ["Observer overhead and host conditions perturb timings; these are not service rates or architectural limits."
              "Nested WAL/file-digest phases overlap their parents; do not add their medians."
              "Raw host counter snapshots are non-atomic; thread names identify execution locations, not complete native CPU accounting."
              "The initial double-counted observer failure is retained separately and is not qualification evidence."]})
