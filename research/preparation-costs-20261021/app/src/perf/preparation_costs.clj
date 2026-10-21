(ns perf.preparation-costs
  "Instrumented preparation-only attribution, NOT throughput qualification.
  Research-only in-place markers survive the release direct-linking boundary.
  Inputs/outputs/errors delegate unchanged. Nothing is submitted to the writer."
  (:require [jdbc.core :as jdbc] [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local] [jdbc.chdb.native :as native]
            [jdbc.chdb.owned-statement :as owned] [jolt.codec.experimental :as codec]
            [otel.exporter.chdb :as exporter] [otel.sdk.export :as sdk]
            [perf.public-pipeline :as fixture]))

(def phases #{:projection-json :snapshot :cached-wal :prepare-callback})

(defn delta [before after]
  {:wall-nanos (- (:nanos after) (:nanos before))
   :cpu-nanos (- (:cpu-nanos after) (:cpu-nanos before))
   :gc-count (- (:gc-count after) (:gc-count before))
   :gc-real-nanos (- (:gc-nanos after) (:gc-nanos before))
   :estimated-allocated-bytes (- (+ (:heap after) (:gc-bytes after))
                                (+ (:heap before) (:gc-bytes before)))})

(defn observer [samples round rows phase counters f]
  (fn [& args]
    (let [before (counters)]
      (try (apply f args)
           (finally
             (let [after (counters)]
               (swap! samples conj (merge {:phase phase :round @round :rows rows}
                                          (delta before after)))))))))

(defn require-coverage! [samples rounds]
  (fixture/ensure! (= (* 4 rounds) (count samples)) :phase-count)
  (doseq [phase phases]
    (let [xs (filter #(= phase (:phase %)) samples)]
      (fixture/ensure! (= (set (range rounds)) (set (map :round xs))) :phase-rounds)
      (fixture/ensure! (= rounds (count xs)) :one-phase-per-batch)))
  true)

(defn quantiles [xs]
  (let [v (vec (sort xs)) n (count v)]
    (fixture/ensure! (pos? n) :nonempty-samples)
    (mapv #(nth v (dec (long (Math/ceil (* % n))))) [0.5 0.9 0.95 0.99])))

(defn summary [samples]
  (into {} (for [phase phases
                 :let [xs (filter #(= phase (:phase %)) samples)]]
             [phase {:samples (count xs)
                     :p50-p90-p95-p99-ms (mapv #(/ % 1e6) (quantiles (map :wall-nanos xs)))
                     :cpu-total-ms (/ (reduce + (map :cpu-nanos xs)) 1e6)
                     :gc-real-total-ms (/ (reduce + (map :gc-real-nanos xs)) 1e6)
                     :estimated-bytes-per-row (/ (reduce + (map :estimated-allocated-bytes xs))
                                                  (double (reduce + (map :rows xs))))}])))

(defn -main [root mode & [rows-text rounds-text]]
  (let [n (parse-long (or rows-text "5000")) rounds (parse-long (or rounds-text "16"))
        warmups 2 samples (atom []) round (atom -1)]
    (fixture/ensure! (and root (.isAbsolute (java.io.File. root))
                         (not (.exists (java.io.File. (str root "/objects"))))) :fresh-root)
    (fixture/ensure! (contains? #{"source-diagnostic" "release-diagnostic"} mode) :mode)
    (fixture/ensure! (and (<= 1 n 10000) (<= 2 rounds 32)) :bounded-geometry)
    (native/ensure-loaded!)
    (fixture/ensure! (= "26.9.0" (native/chdb-version)) :native-version)
    (.mkdirs (java.io.File. (str root "/scratch-writer")))
    (with-open [connection
                (jdbc/connection
                  (durable/writer-dbspec
                    {:namespace-backend (local/local-backend (str root "/objects"))
                     :object-id "preparation-costs" :scratch-parent (str root "/scratch-writer")
                     :owner "diagnostic" :instance "writer" :database "otel"
                     :owned-compact-stream? true :lease-ttl-ms 900000
                     :heartbeat-interval-ms 300000}))]
      (let [capability (fixture/setup! connection)
            rows (fixture/fixture n)
            wrap #(observer samples round n %1 fixture/counters %2)]
        (with-bindings
          {#'exporter/*preparation-stage-observer*
           (fn [phase f] ((wrap phase f)))}
          (do
            (let [target (exporter/exporter
                           {:connection connection :durable? true :create-schema? false
                            :datetime64-wire :raw-ticks :max-insert-bytes (* 16 1024 1024)
                            :signals #{:spans} :typed-span-descriptors capability
                            :insert-format :json-compact-each-row :json-backend :native-guarded-byte-batch
                            :owned-statement-output? true :experimental-span-codec? true
                            :experimental-span-payload-cache? true :experimental-span-flat-map-cache? true
                            :experimental-span-nested-fragment-cache? true :experimental-span-shape-plans? true
                            :experimental-span-scalar-views? true})
                  adapter (:experimental-span-preparation
                            (exporter/experimental-span-batch-options target
                              {:max-export-batch-size n :max-queue-size (* 2 n)}))
                  prepare (wrap :prepare-callback (:prepare! adapter))]
              (try
                (reset! samples [])
                (System/gc)
                (dotimes [i (+ warmups rounds)]
                  (reset! round (- i warmups))
                  (let [cap ((:reserve! adapter) n)]
                    (fixture/ensure! cap :reserved)
                    (fixture/ensure! (true? (prepare cap rows)) :prepared)
                    (fixture/ensure! (true? ((:abort! adapter) cap)) :aborted-unsubmitted)
                    (fixture/ensure! (true? ((:release! adapter) cap)) :released)
                    (fixture/ensure! (true? ((:retire! adapter) cap)) :retired)))
                (let [measured (vec (filter #(<= 0 (:round %)) @samples))]
                  ;; Preserve only bounded scalar observations even when the
                  ;; compiler bypasses a wrapper. Missing coverage is failure,
                  ;; never a zero-cost phase or a qualified timing result.
                  (spit (str root "/raw-samples.edn")
                    (pr-str {:scope :raw-unqualified-observations :samples measured}))
                  (println :phase-inventory (frequencies (map :phase measured)))
                  (require-coverage! measured rounds)
                  (let [report {:scope :instrumented-preparation-only :mode mode :batch-size n
                              :rounds rounds :warmups warmups :percentiles [0.5 0.9 0.95 0.99]
                              :tail-qualified false :nested-phases-not-additive true
                              :cpu-scope :current-os-thread :memory-scope :process-wide-nonatomic
                              :submitted-batches 0 :samples measured :summary (summary measured)}]
                  (fixture/ensure! (zero? (:n (jdbc/fetch-one connection "SELECT count() AS n FROM otel_traces")))
                                   :no-telemetry-published)
                  (spit (str root "/preparation-costs.edn") (pr-str report))
                    (println :preparation-costs (:summary report))))
                (finally (sdk/shutdown-exporter! target))))))))))
