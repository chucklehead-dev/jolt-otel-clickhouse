(ns phase-probe
  "Research-only coarse profiling, one serial SDK writer, no payload retention."
  (:require [otel.exporter.chdb :as exporter]
            [jdbc.chdb.owned-statement :as owned]
            [jdbc.chdb.native :as native]
            [otel.exporter.chdb-benchmark :as bench]))

(def events (atom []))
(def diagnostic-error? (atom false))
(def active (atom nil))
(def index (atom -1))

(defn- sample []
  (try (#'bench/counter-sample)
       (catch Throwable _ (reset! diagnostic-error? true) nil)))
(defn- now []
  (try (System/nanoTime)
       (catch Throwable _ (reset! diagnostic-error? true) nil)))
(defn- record! [id phase status started ended before after]
  ;; Observers must not replace an execution result/exception. Bounds and
  ;; capture failures are validated after the actual writer finishes.
  (try
    (if (and (< (count @events) 1024) started ended before after)
      (swap! events conj {:batch id :phase phase :status status}
                         :start-nanos started :end-nanos ended :nanos (- ended started)
                         :counters (#'bench/counter-delta before after))
      (reset! diagnostic-error? true))
    (catch Throwable _ nil)))
(defn- measured [id phase f]
  (let [before (sample) started (now)]
    (try
      (let [value (f) ended (now) after (sample)]
        (record! id phase :complete started ended before after)
        value)
      (catch Throwable primary
        (record! id phase :failed started (now) before (sample))
        (throw primary)))))
(defn- traced [phase f]
  (fn [& args]
    (if-some [id @active]
      (measured id phase #(apply f args))
      (apply f args))))

(defn with-probes [f]
  (let [load-codec @#'exporter/load-experimental-span-codec!
        compact @#'exporter/execute-closed-compact-rows!]
    (reset! events []) (reset! active nil) (reset! index -1)
    (reset! diagnostic-error? false)
    (with-redefs-fn
      {#'exporter/load-experimental-span-codec!
       (fn [& args]
         (let [c (apply load-codec args)]
           (-> c (update :encode #(traced :project-and-encode %))
                 (update :prepare-statement #(traced :wal-precompute %)))))
       #'exporter/execute-closed-compact-rows!
       (fn [& args]
         (if (some? @active)
           (do (reset! diagnostic-error? true) (apply compact args))
           (let [id (swap! index inc)]
             (reset! active id)
             (try
               (let [value (measured id :compact-total #(apply compact args))]
                 ;; Incremental scalar progress, outside recorded phase clocks
                 ;; but INCLUDED in callback/window timing. No SQL/SDK values.
                 (println :phase-observation
                   {:batch id :phases
                    (mapv #(select-keys % [:phase :status :nanos :counters])
                          (filter #(= id (:batch %)) @events))})
                 (flush)
                 value)
               (finally (reset! active nil))))))
       #'owned/try-snapshot (traced :snapshot-validation-copy owned/try-snapshot)
       #'owned/code-placeholder? (traced :placeholder-scan owned/code-placeholder?)
       #'exporter/execute-durable-sql! (traced :execute-and-confirm @#'exporter/execute-durable-sql!)
       #'native/classify-query-buffer! (traced :native-classify native/classify-query-buffer!)
       ;; stream-insert-n creates the stream, NOT the complete ingestion.
       #'native/chdb-stream-insert-n (traced :stream-create native/chdb-stream-insert-n)
       #'native/chdb-stream-append (traced :stream-append native/chdb-stream-append)
       #'native/chdb-stream-done (traced :stream-finish native/chdb-stream-done)
       #'native/chdb-destroy-insert-stream (traced :stream-destroy native/chdb-destroy-insert-stream)}
      f)))

(defn report []
  (let [xs @events measured-xs (filter #(<= 3 (:batch %) 34) xs)
        by-batch (group-by :batch xs)
        counts (into {} (for [[id es] by-batch] [id (frequencies (map :phase es))]))
        one [:compact-total :project-and-encode :snapshot-validation-copy :wal-precompute
             :execute-and-confirm :stream-create :stream-append :stream-finish :stream-destroy]
        _ (assert (false? @diagnostic-error?))
        _ (assert (= (set (range 35)) (set (keys by-batch))))
        _ (assert (every? #(= :complete (:status %)) xs))
        _ (doseq [[id frequency] counts]
            (doseq [phase one] (assert (= 1 (get frequency phase)) (str id " " phase)))
            (assert (pos? (get frequency :placeholder-scan 0)))
            (assert (pos? (get frequency :native-classify 0))))
        q (fn [values p] (nth (vec (sort values)) (dec (long (Math/ceil (* p (count values)))))))]
    {:scope :matched-current-durable-phase-diagnosis :warmup-batches 3 :measured-batches 32
     :schema-inference-enabled true :rows-per-batch 5000 :observations xs :counts counts
     :counter-snapshots-non-atomic true :heap-counters-process-wide true
     :native-phases-nested-in-parent true :diagnostic-printing-in-window true
     :tail-qualified false :causal-version-comparison false
     :summary (into {} (for [[phase es] (group-by :phase measured-xs)]
                        [phase {:calls (count es)
                                :p50-ms (/ (q (map :nanos es) 0.5) 1e6)
                                :p90-ms (/ (q (map :nanos es) 0.9) 1e6)
                                :p95-ms (/ (q (map :nanos es) 0.95) 1e6)
                                :p99-ms (/ (q (map :nanos es) 0.99) 1e6)
                                :total-ms (/ (reduce + (map :nanos es)) 1e6)
                                :estimated-bytes-per-measured-row
                                (/ (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) es)) 160000.0)}]))}))
