;; Fixed before observing timings: 3 arms, original/disabled/prefix/prefix/disabled/original,
;; 16 samples/arm, identical rich 5000-span fixture and real SDK conversion.
(load-file "research/template-key-prefixes-20261021/prototype.clj")
(require '[perf.public-pipeline :as app]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[jolt.host :as host])

(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes)
   :thread-cpu (host/cpu-nanos) :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))

(let [order [:original :disabled :prefix :prefix :disabled :original]
      contexts (into {} (map (fn [arm] [arm (prefix-context arm)]) (distinct order)))
      rows (app/fixture 5000)
      target (Object.) installation (#'installed/installed (app/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true
                                               (:folder (:original contexts)))
      encode (fn [arm values]
               (let [context (get contexts arm)
                     convert (#'exporter/payload-attrs-converter ((:payload-cache-factory context)))
                     project (fn [span]
                               (let [row (into (#'exporter/span-row span nil true (:nested context) convert)
                                               (typed span))]
                                 (assert (= 28 (count row))) row))]
                 (with-bindings {#'exporter/*timestamp-wire* :raw-ticks
                                 #'exporter/*json-backend* :native-guarded-byte-batch
                                 #'exporter/*experimental-attribute-transform* (:attributes context)}
                   ((:encode context) "SQL\n" values project 67108864))))
      expected (encode :original rows)
      _ (doseq [arm (distinct order)]
          (assert (bytes-equal? expected (encode arm rows)))
          (doseq [changed [(assoc-in rows [0 :events 0 :name] "changed-event")
                           (assoc-in rows [0 :resource :attributes "flag"] true)]]
            (assert (not (bytes-equal? expected (encode arm changed))))
            (assert (bytes-equal? (encode :original changed) (encode arm changed)))))
      _ (doseq [arm order] (encode arm rows))
      observations
      (mapv (fn [ordinal]
              (let [arm (nth order (mod ordinal (count order)))
                    before (counters) start (System/nanoTime)
                    output (encode arm rows) elapsed (- (System/nanoTime) start)
                    after (counters)]
                (assert (bytes-equal? expected output))
                {:ordinal ordinal :arm arm :nanos elapsed
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after))
                                     (+ (:heap before) (:gc-bytes before)))
                 :thread-cpu-nanos (- (:thread-cpu after) (:thread-cpu before))
                 :gc-count (- (:gc-count after) (:gc-count before))
                 :gc-nanos (- (:gc-nanos after) (:gc-nanos before))})) (range 48))
      summary (into {} (for [[arm samples] (group-by :arm observations)]
                         [arm {:p50-p90-p95-p99-ms
                               (mapv #(/ (quantile (map :nanos samples) %) 1e6) [0.5 0.9 0.95 0.99])
                               :median-estimated-bytes-per-row
                               (/ (quantile (map :estimated-bytes samples) 0.5) 5000.0)}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :rich-scalar-template-prefix-component-screen
             :rows 5000 :samples-per-arm 16 :order order
             :full-output-bytes-equal true :changed-event-and-value-controls true
             :sdk-policy-and-per-payload-cache-included true :typed-projection-included true
             :private-var-component-probes true :release-optimization-qualified false
             :sdk-queue false :durable-io false :tail-qualified false
             :output-bytes (alength expected) :summary summary :observations observations}))
  (prn summary))
