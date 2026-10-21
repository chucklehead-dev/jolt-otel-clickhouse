;; Component-only floor: what remains if the SAME physical rows already exist?
;; No native I/O or policy bypass in the actual exporter. Private helpers make
;; this source-mode diagnostic, not release throughput or an adoption feature.
(require '[perf.public-pipeline :as app]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[jolt.host :as host] '[jolt.scheme :as scheme])

(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes) :cpu (host/cpu-nanos)
   :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))

(let [context (#'e/load-experimental-span-codec! true true true true true)
      rows (app/fixture 5000)
      target (Object.) installation (#'installed/installed (app/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true (:folder context))
      prepared (volatile! [])
      encode-full
      (fn [values capture?]
        (let [convert (#'e/payload-attrs-converter ((:payload-cache-factory context)))
              project (fn [span]
                        (let [row (into (#'e/span-row span nil true (:nested context) convert) (typed span))]
                          (assert (= 28 (count row)))
                          (when capture? (vswap! prepared conj row))
                          row))]
          (with-bindings {#'e/*timestamp-wire* :raw-ticks
                          #'e/*json-backend* :native-guarded-byte-batch
                          #'e/*experimental-attribute-transform* (:attributes context)}
            ((:encode context) "SQL\n" values project 67108864))))
      encode-wire (fn [physical-rows]
                    ((:encode context) "SQL\n" physical-rows identity 67108864))
      equal? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      ;; Capture only once, OUTSIDE timed observations. These are independently
      ;; owned physical values, not deferred access to the finished SDK cache.
      expected (encode-full rows true)
      physical-rows @prepared
      _ (vreset! prepared [])
      _ (assert (= 5000 (count physical-rows)))
      _ (assert (equal? expected (encode-wire physical-rows)))
      _ (assert (not (equal? expected (encode-full (assoc-in rows [0 :events 0 :name] "changed-event") false))))
      _ (assert (not (equal? expected (encode-full (assoc-in rows [0 :attributes "count"] 999999) false))))
      _ (assert (not (equal? expected (encode-wire (assoc-in physical-rows [0 5] "changed-name")))))
      encode (fn [mode] (if (= mode :full) (encode-full rows false) (encode-wire physical-rows)))
      _ (doseq [mode [:full :wire :wire :full]] (assert (equal? expected (encode mode))))
      observations
      (mapv (fn [ordinal]
              (let [mode (nth [:full :wire :wire :full] (mod ordinal 4))
                    before (counters) started (System/nanoTime)
                    output (encode mode) elapsed (- (System/nanoTime) started) after (counters)]
                (assert (equal? expected output))
                {:ordinal ordinal :mode mode :nanos elapsed
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after))
                                     (+ (:heap before) (:gc-bytes before)))
                 :cpu-nanos (- (:cpu after) (:cpu before))
                 :gc-count (- (:gc-count after) (:gc-count before))
                 :gc-nanos (- (:gc-nanos after) (:gc-nanos before))})) (range 32))
      summary (into {} (for [[mode samples] (group-by :mode observations)]
                         [mode {:p50-p90-p95-p99-ms
                                (mapv #(/ (quantile (map :nanos samples) %) 1e6) [0.5 0.9 0.95 0.99])
                                :median-estimated-bytes-per-row
                                (/ (quantile (map :estimated-bytes samples) 0.5) 5000.0)}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :rich-component-preprojected-row-floor
             :rows 5000 :samples-per-arm 16 :full-output-bytes-equal true
             :changed-event-control true :changed-typed-value-control true :changed-physical-row-control true
             :preprojection-in-timed-wire-arm false :prepared-rows-retained true
             :preprojection-retention-accounted false :wire-arm-is-not-a-production-optimization true
             :private-var-component-probes true :release-optimization-qualified false
             :source-mode true :sdk-queue false :durable-io false :tail-qualified false
             :output-bytes (alength expected) :summary summary :observations observations}))
  (prn summary))
