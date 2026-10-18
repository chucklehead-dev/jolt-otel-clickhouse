(require '[clojure.edn :as edn])
(def prefix "/home/chuck/ai-src/evidence/json-rich-worker-profile-fixed-20261018")
(def p (edn/read-string (slurp (str prefix ".worker-profile.edn"))))
(def w (edn/read-string (slurp (str prefix ".writer.edn"))))
(def r (edn/read-string (slurp (str prefix ".reader.edn"))))
(defn pct [xs q]
  (let [v (vec (sort xs))]
    (assert (seq v))
    (/ (double (nth v (dec (long (Math/ceil (* q (count v))))))) 1e6)))
(defn latency [events]
  (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
             [label (pct (map :nanos events) q)])))
(assert (and (:public-json-writer-roots-unchanged p) (:default-native-executor-preserved p)))
(assert (= 32 (:samples p) (count (:observations w))))
(assert (and (:passed r) (:full-physical-rows-equal r) (:typed-values-status r)))
(assert (= [175000 5000 625 35] (mapv r [:rows :groups :trace-count :per-record-multiplicity])))
(def by-phase (group-by :phase (:events p)))
(assert (= #{:file-digest :head-commit :native-classify :native-stream
             :wal-append :wal-head-cas :wal-immutable-put :wal-immutable-verify
             :wal-join :wal-prepare :wal-publish}
           (set (keys by-phase))))
(doseq [[_ events] by-phase]
  (assert (= 32 (count events)))
  (assert (= (vec (range 32)) (mapv :ordinal events)))
  (assert (every? #(= :complete (:status %)) events)))
(prn {:scope :instrumented-json-worker-phase-attribution-not-throughput-qualification
      :runtime "631984d6" :exporter "4d48871" :sdk "7878a2e" :json "96f3e47" :driver "3d766fa"
      :independent-recovery-passed true :physical-rows 175000
      :samples 32 :callback-ms (latency (:observations w))
      :outer-phases (into {} (for [[phase events] (group-by :phase (:phases w))]
                              [phase {:latency-ms (latency events)
                                      :estimated-allocated-bytes-per-row
                                      (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) events)))
                                         (* 5000 (count events)))}]))
      :worker-phases (into {} (for [[phase events] by-phase]
                               [phase {:calls (count events) :latency-ms (latency events)
                                       :threads (frequencies (map :thread events))
                                       :calling-thread-cpu-ms
                                       (when (every? :counters events)
                                         (pct (map #(get-in % [:counters :calling-thread-cpu-nanos]) events) 0.5))}]))
      :nonoverlapping-worker-phases #{:native-classify :native-stream :wal-prepare
                                     :wal-append :wal-join :wal-publish :head-commit}
      :nested-phases #{:file-digest :wal-immutable-put :wal-immutable-verify :wal-head-cas}
      :notes ["Do not sum phase medians or add nested phases to parent totals."
              "Calling-thread CPU excludes ClickHouse's other native worker threads."
              "WAL hook events are timing-only; coarse wrapper phases also take counter snapshots."
              "Diagnostic instrumentation perturbs performance; use non-instrumented matched runs for rates."]})
