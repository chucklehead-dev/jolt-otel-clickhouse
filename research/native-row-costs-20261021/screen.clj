(require '[clojure.java.io :as io] '[jolt.scheme :as scheme] '[jolt.host :as host]
         '[perf.public-pipeline :as app] '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed])

(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))
(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes)})

(let [[wrap reset! observations]
      ((scheme/eval-string (slurp "research/native-row-costs-20261021/probe.ss")))
      phases [:attribute-conversion :nested-text :typed-projection :row-inclusive :noop]
      context (#'e/load-experimental-span-codec! true true true true true)
      rows (app/fixture 5000)
      target (Object.) installation (#'installed/installed (app/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true (:folder context))
      encode (fn [mode values]
               (let [observe? (= mode :observed)
                     convert (#'e/payload-attrs-converter ((:payload-cache-factory context)))
                     convert (if observe? (wrap 0 convert) convert)
                     nested (if observe? (wrap 1 (:nested context)) (:nested context))
                     typed (if observe? (wrap 2 typed) typed)
                     project (fn [span]
                               (let [row (into (#'e/span-row span nil true nested convert) (typed span))]
                                 (assert (= 28 (count row))) row))
                     project (if observe? (wrap 3 project) project)]
                 (with-bindings {#'e/*timestamp-wire* :raw-ticks
                                 #'e/*json-backend* :native-guarded-byte-batch
                                 #'e/*experimental-attribute-transform* (:attributes context)}
                   ((:encode context) "SQL\n" values project 67108864))))
      equal? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      expected (encode :plain rows)
      _ (assert (equal? expected (encode :observed rows)))
      _ (assert (not (equal? expected (encode :observed (assoc-in rows [0 :events 0 :name] "changed-event")))))
      _ (assert (not (equal? expected (encode :observed (assoc-in rows [0 :attributes "count"] 999999)))))
      _ (doseq [mode [:plain :observed :observed :plain]] (assert (equal? expected (encode mode rows))))
      samples
      (mapv (fn [ordinal]
              (let [mode (nth [:plain :observed :observed :plain] (mod ordinal 4))
                    _ (reset!) before (counters) started (System/nanoTime)
                    output (encode mode rows) elapsed (- (System/nanoTime) started) after (counters)
                    recorded (observations)
                    counts (mapv first recorded)]
                (assert (equal? expected output))
                (assert (= (if (= mode :observed) [15000 10000 5000 5000 0] [0 0 0 0 0]) counts))
                {:ordinal ordinal :mode mode :nanos elapsed
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after))
                                     (+ (:heap before) (:gc-bytes before)))
                 :phases (zipmap phases recorded)})) (range 32))
      _ (reset!) noop (wrap 4 (fn [_] nil))
      _ (dotimes [_ 35000] (noop nil))
      calibration (last (observations))
      summary (into {} (for [[mode values] (group-by :mode samples)]
                         [mode {:p50-p90-p95-p99-ms (mapv #(/ (quantile (map :nanos values) %) 1e6) [0.5 0.9 0.95 0.99])
                                :median-estimated-bytes-per-row (/ (quantile (map :estimated-bytes values) 0.5) 5000.0)}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :research-native-operation-allocation-diagnostic
             :rows 5000 :samples-per-arm 16 :full-output-bytes-equal true
             :changed-event-control true :changed-attribute-control true
             :phase-vector-fields [:count :inclusive-real-nanos :allocated-bytes :thread-cpu-nanos :gc-real-nanos]
             :phase-snapshots :chez-statistics :phase-cost-includes-observer true
             :allocation-is-not-heap-retention true :inclusive-phases-do-not-sum true
             :noop-calibration calibration :observer-overhead-subtracted false
             :source-mode true :private-component-probes true :durable-io false
             :production-adopted false :tail-qualified false :output-bytes (alength expected)
             :summary summary :observations samples}))
  (prn {:summary summary :noop-calibration calibration}))
