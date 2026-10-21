;; Compare deployed nested-cache strategy, uncached old strategy and fused
;; escaped sink. All use the same current scalar-view SDK/policy/projector.
(require '[perf.public-pipeline :as app] '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[jolt.codec.experimental :as codec]
         '[jolt.host :as host] '[jolt.scheme :as scheme])
(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes) :cpu (host/cpu-nanos)
   :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))

(let [c (codec/open-codec)
      uncached (#'e/load-experimental-span-codec! true true false true true)
      contexts {:cached (#'e/load-experimental-span-codec! true true true true true)
                :uncached uncached
                :fused (assoc uncached :nested #(codec/prepare-nested-literal c %))
                :fused-cached uncached}
      rows (app/fixture 5000) target (Object.)
      installation (#'installed/installed (app/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true
                                               (:folder (:cached contexts)))
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
                   (if (= mode :fused-cached)
                     (codec/with-nested-literal-writer c
                       (fn [nested]
                         (let [project (fn [span]
                                         (into (#'e/span-row span nil true nested convert) (typed span)))]
                           ((:encode context) "SQL\n" values project 67108864))))
                     ((:encode context) "SQL\n" values project 67108864)))))
      equal? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      expected (encode :cached rows)
      _ (doseq [mode [:cached :uncached :fused :fused-cached]]
          (assert (equal? expected (encode mode rows)))
          (assert (not (equal? expected (encode mode (assoc-in rows [0 :events 0 :name] "changed-event"))))))
      order [:cached :uncached :fused :fused-cached :fused-cached :fused :uncached :cached]
      _ (doseq [mode order] (encode mode rows))
      observations
      (mapv (fn [ordinal]
              (let [mode (nth order (mod ordinal 8)) before (counters)
                    start (System/nanoTime) output (encode mode rows)
                    elapsed (- (System/nanoTime) start) after (counters)]
                (assert (equal? expected output))
                {:ordinal ordinal :mode mode :nanos elapsed
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after))
                                     (+ (:heap before) (:gc-bytes before)))
                 :cpu-nanos (- (:cpu after) (:cpu before))
                 :gc-count (- (:gc-count after) (:gc-count before))
                 :gc-nanos (- (:gc-nanos after) (:gc-nanos before))})) (range 64))
      summary (into {} (for [[mode samples] (group-by :mode observations)]
                         [mode {:p50-p90-p95-p99-ms
                                (mapv #(/ (quantile (map :nanos samples) %) 1e6) [0.5 0.9 0.95 0.99])
                                :median-estimated-bytes-per-row
                                (/ (quantile (map :estimated-bytes samples) 0.5) 5000.0)}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :rich-cumulative-fused-nested-literal-component
             :rows 5000 :samples-per-arm 16 :full-output-bytes-equal true
             :changed-event-controls true :sdk-policy-and-payload-cache-included true
             :typed-projection-included true :private-var-component-probes true
             :fused-cached-uses-separate-bounded-cache true :sdk-queue false :native-io false
             :durable-io false :tail-qualified false :output-bytes (alength expected)
             :summary summary :observations observations}))
  (prn summary))
