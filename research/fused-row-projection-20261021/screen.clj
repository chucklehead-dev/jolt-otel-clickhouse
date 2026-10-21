;; Fixed ABC-CBA diagnostic; no per-row observers in measured regions.
(require '[perf.row-probe :as probe] '[perf.public-pipeline :as app] '[jolt.host :as host])
(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))
(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes) :cpu (host/cpu-nanos)
   :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(let [rows (app/fixture 5000) expected (probe/encode :baseline rows)
      order [:baseline :row-view :candidate :candidate :row-view :baseline]
      _ (doseq [mode (distinct order)]
          (assert (probe/bytes-equal? expected (probe/encode mode rows)))
          (assert (not (probe/bytes-equal? expected
                         (probe/encode mode (assoc-in rows [0 :events 0 :name] "changed-event"))))))
      _ (doseq [mode order] (probe/encode mode rows))
      samples
      (mapv (fn [ordinal]
              (let [mode (nth order (mod ordinal 6)) before (counters) start (System/nanoTime)
                    output (probe/encode mode rows) elapsed (- (System/nanoTime) start) after (counters)]
                (assert (probe/bytes-equal? expected output))
                {:ordinal ordinal :mode mode :nanos elapsed
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after)) (+ (:heap before) (:gc-bytes before)))
                 :cpu-nanos (- (:cpu after) (:cpu before))
                 :gc-count (- (:gc-count after) (:gc-count before))
                 :gc-nanos (- (:gc-nanos after) (:gc-nanos before))})) (range 48))
      summary
      (into {} (for [[mode observations] (group-by :mode samples)]
                 [mode {:p50-p90-p95-p99-ms
                        (mapv #(/ (quantile (map :nanos observations) %) 1e6) [0.5 0.9 0.95 0.99])
                        :median-estimated-bytes-per-row
                        (/ (quantile (map :estimated-bytes observations) 0.5) 5000.0)}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :rich-source-fused-row-preparation :rows 5000 :samples-per-arm 16
             :output-bytes (alength expected) :full-output-bytes-equal true
             :changed-event-controls true :sdk-policy-and-typed-projection true
             :native-row-and-small-column-wrapper-removal true
             :private-source-probes true :durable-io false :tail-qualified false
             :summary summary :samples samples}))
  (prn summary))
