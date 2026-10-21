(load-file "/home/chuck/ai-src/worktrees/exporter-pipeline-phase-costs-20261021/research/key-fragment-cache-20261021/prototype.clj")
(require '[perf.public-pipeline :as app]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[jolt.host :as host])

(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes) :cpu (host/cpu-nanos)
   :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))

(let [contexts {:original (key-cache-context :original) :key-cache (key-cache-context :key-cache)
                :published (#'exporter/load-experimental-span-codec! true true true true true)}
      rows (app/fixture 5000)
      target (Object.) installation (#'installed/installed (app/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true
                                               (:folder (:original contexts)))
      encode (fn [mode values]
               (let [context (get contexts mode)
                     convert (#'exporter/payload-attrs-converter ((:payload-cache-factory context)))
                     project (fn [span]
                               (let [row (into (#'exporter/span-row span nil true (:nested context) convert)
                                               (typed span))]
                                 (assert (= 28 (count row))) row))]
                 (with-bindings {#'exporter/*timestamp-wire* :raw-ticks
                                 #'exporter/*json-backend* :native-guarded-byte-batch
                                 #'exporter/*experimental-attribute-transform* (:attributes context)}
                   ((:encode context) "SQL\n" values project 67108864))))
      equal? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      expected (encode :published rows)
      _ (doseq [mode [:original :key-cache]]
          (assert (equal? expected (encode mode rows)))
          (assert (not (equal? expected (encode mode (assoc-in rows [0 :events 0 :name] "changed-event")))))
          (assert (not (equal? expected (encode mode (assoc-in rows [0 :attributes "count"] 999999))))))
      _ (doseq [mode [:original :key-cache :key-cache :original]]
          (assert (equal? expected (encode mode rows))))
      observations
      (mapv (fn [ordinal]
              (let [mode (nth [:original :key-cache :key-cache :original] (mod ordinal 4))
                    before (counters) started (System/nanoTime)
                    output (encode mode rows) elapsed (- (System/nanoTime) started) after (counters)]
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
    (pr-str {:scope :research-bounded-key-fragment-cache
             :rows 5000 :samples-per-arm 16 :unchanged-original-kernel-control true
             :matches-published-codec-bytes true :full-output-bytes-equal true
             :changed-event-control true :changed-attribute-control true
             :sdk-policy-and-per-payload-cache-included true :typed-projection-included true
             :private-var-component-probes true :source-patch-prototype true
             :source-mode true :release-optimization-qualified false :sdk-queue false
             :durable-io false :production-adopted false :tail-qualified false
             :output-bytes (alength expected) :summary summary :observations observations}))
  (prn summary))
