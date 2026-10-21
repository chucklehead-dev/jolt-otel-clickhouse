;; Cumulative rich projection/normalization/nested+outer encoding, no native I/O.
(require '[perf.public-pipeline :as app]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[jolt.host :as host] '[jolt.scheme :as scheme])

(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes) :cpu (host/cpu-nanos)
   :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn quantile [xs p] (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))

(let [contexts {:map (#'e/load-experimental-span-codec! true true true true)
                :view (#'e/load-experimental-span-codec! true true true true true)}
      rows (app/fixture 5000)
      target (Object.) installation (#'installed/installed (app/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true
                                               (:folder (:map contexts)))
      encode (fn [mode values]
               (let [context (get contexts mode)
                     convert (#'e/payload-attrs-converter ((:payload-cache-factory context)))
                     project (fn [span]
                               (let [row (into (#'e/span-row span nil true (:nested context) convert)
                                               (typed span))]
                                 (assert (= 28 (count row))) row))]
                 (with-bindings {#'e/*timestamp-wire* :raw-ticks
                                 #'e/*json-backend* :native-guarded-byte-batch
                                 #'e/*experimental-attribute-transform* (:attributes context)}
                   ((:encode context) "SQL\n" values project 67108864))))
      equal? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      expected (encode :map rows)
      _ (assert (equal? expected (encode :view rows)))
      _ (doseq [mode [:map :view]]
          (assert (not (equal? expected (encode mode (assoc-in rows [0 :events 0 :name] "changed-event"))))))
      _ (doseq [mode [:map :view :view :map]] (encode mode rows))
      observations (mapv (fn [ordinal]
                           (let [mode (nth [:map :view :view :map] (mod ordinal 4))
                                 before (counters) start (System/nanoTime)
                                 output (encode mode rows) elapsed (- (System/nanoTime) start)
                                 after (counters)]
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
    (pr-str {:scope :rich-cumulative-scalar-view-projection-and-encoding
             :rows 5000 :samples-per-arm 16 :equal-distinct-sorted-roots true
             :full-output-bytes-equal true :changed-event-controls true
             :sdk-policy-and-per-payload-cache-included true :typed-projection-included true
             :private-var-component-probes true :release-optimization-qualified false
             :sdk-queue false :durable-io false :tail-qualified false
             :output-bytes (alength expected) :summary summary :observations observations}))
  (prn summary))
