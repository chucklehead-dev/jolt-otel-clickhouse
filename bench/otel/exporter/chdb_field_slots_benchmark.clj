(ns otel.exporter.chdb-field-slots-benchmark
  "Paired current indexed/fused projection and rich encoding. No native I/O."
  (:require [perf.public-pipeline :as app]
            [otel.exporter.chdb :as e]
            [otel.exporter.chdb.attribute-projection :as p]
            [otel.exporter.chdb.native-projection :as native]
            [otel.exporter.chdb-attribute-projection-test :as installed]
            [jolt.scheme :as scheme] [jolt.host :as host]))

(defn counters [] {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes)})
(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))

(defn -main [output]
  (let [context (#'e/load-experimental-span-codec! true true true true true)
        rows (app/fixture 5000)
        target (Object.) installation (#'installed/installed (app/approved) target)
        descriptor (:descriptor-set installation)
        baseline (with-redefs [native/load-trace-field-slots! (constantly nil)]
                   (p/trace-vector-projector descriptor target true (:folder context)))
        candidate (p/trace-vector-projector descriptor target true (:folder context))
        projectors {:indexed baseline :fused candidate}
        expected-typed (mapv (p/trace-vector-projector descriptor target) rows)
        equal-bytes? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
        encode (fn [arm values]
                 (let [convert (#'e/payload-attrs-converter ((:payload-cache-factory context)))
                       typed (get projectors arm)
                       project (fn [span]
                                 (let [row (into (#'e/span-row span nil true (:nested context) convert) (typed span))]
                                   (assert (= 28 (count row))) row))]
                   (with-bindings {#'e/*timestamp-wire* :raw-ticks
                                   #'e/*json-backend* :native-guarded-byte-batch
                                   #'e/*experimental-attribute-transform* (:attributes context)}
                     ((:encode context) "SQL\n" values project 67108864))))
        expected-bytes (encode :indexed rows)
        _ (doseq [arm [:indexed :fused]]
            (assert (= expected-typed (mapv (get projectors arm) rows)))
            (assert (equal-bytes? expected-bytes (encode arm rows)))
            (assert (not (equal-bytes? expected-bytes (encode arm (assoc-in rows [0 :attributes "count"] 999999))))))
        screens
        (into {}
          (for [kind [:typed :rich]]
            (let [operation (if (= kind :typed)
                              (fn [arm] (mapv (get projectors arm) rows))
                              (fn [arm] (encode arm rows)))
                  valid? (if (= kind :typed) #(= expected-typed %) #(equal-bytes? expected-bytes %))
                  _ (doseq [arm [:indexed :fused :fused :indexed]] (assert (valid? (operation arm))))
                  samples (mapv (fn [ordinal]
                                  (let [arm (nth [:indexed :fused :fused :indexed] (mod ordinal 4))
                                        before (counters) start (System/nanoTime)
                                        result (operation arm) elapsed (- (System/nanoTime) start) after (counters)]
                                    (assert (valid? result))
                                    {:ordinal ordinal :arm arm :nanos elapsed
                                     :estimated-bytes (- (+ (:heap after) (:gc-bytes after))
                                                         (+ (:heap before) (:gc-bytes before)))})) (range 32))
                  summary (into {} (for [[arm observations] (group-by :arm samples)]
                                     [arm {:p50-p90-p95-p99-ms
                                           (mapv #(/ (quantile (map :nanos observations) %) 1e6) [0.5 0.9 0.95 0.99])
                                           :median-estimated-bytes-per-row
                                           (/ (quantile (map :estimated-bytes observations) 0.5) 5000.0)}]))]
              [kind {:summary summary :observations samples}])))]
    (spit output
      (pr-str {:scope :paired-fused-field-slot-component-screen
               :rows 5000 :fields 2 :samples-per-arm-per-screen 16
               :baseline-original-indexed-function true :policy-helpers-unchanged true
               :exact-portable-vectors-and-full-rich-bytes true :changed-attribute-control true
               :output-bytes (alength expected-bytes) :source-mode true
               :release-optimization-qualified false :durable-io false :tail-qualified false
               :screens screens}))
    (prn (into {} (map (fn [[kind result]] [kind (:summary result)]) screens)))))
