;; Original published byte kernel vs candidate classic/fused, same raw adapters.
;; This isolates hot byte kernels, not frontend startup or full native I/O.
(require '[perf.public-pipeline :as app] '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.any-value :as av] '[jolt.codec.experimental :as codec]
         '[jolt.codec.scalar-text.experimental :as shapes]
         '[jolt.traversal.experimental :as traversal]
         '[jolt.host :as host] '[jolt.scheme :as scheme])
(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes) :cpu (host/cpu-nanos)
   :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))

(let [[output original-source-path] *command-line-args*
      walk (traversal/open-traversal) folder (traversal/native-pmap-folder walk)
      build (fn [source fused?]
              (apply (scheme/eval-string
                       (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?)"
                            " (let () " source
                            " (jolt-vector ffc-json-cached-project-prefixed-ascii-lines-bytes"
                            " ffc-json-nested-scope " (if fused? "ffc-json-nested-literal-scope" "#f")
                            " ffc-json-scalar-map-box)))"))
                     (traversal/native-kernels walk)))
      old (build (slurp original-source-path) false)
      new (build @#'codec/json-source true)
      contexts {:original {:encode (nth old 0) :scope (nth old 1) :box (nth old 3)}
                :classic {:encode (nth new 0) :scope (nth new 1) :box (nth new 3)}
                :fused {:encode (nth new 0) :scope (nth new 2) :box (nth new 3)}}
      attributes (:attributes (#'e/load-experimental-span-codec! true true true true true))
      rows (app/fixture 5000) target (Object.)
      installation (#'installed/installed (app/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true folder)
      encode (fn [mode values]
               (let [{:keys [encode scope box]} (get contexts mode)
                     factory (shapes/native-view-plan-factory box)
                     converter (av/scalar-text-payload-projector folder {:shape-plan-factory factory})
                     _ (assert converter)
                     convert (#'e/payload-attrs-converter converter)
                     [prepare finish] (scope)
                     nested (fn [value] (prepare value 67108864 128))
                     project (fn [span]
                               (let [row (into (#'e/span-row span nil true nested convert) (typed span))]
                                 (assert (= 28 (count row))) row))]
                 (try
                   (with-bindings {#'e/*timestamp-wire* :raw-ticks
                                   #'e/*json-backend* :native-guarded-byte-batch
                                   #'e/*experimental-attribute-transform* attributes}
                     (encode "SQL\n" values project 67108864 128))
                   (finally (finish)))))
      equal? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      expected (encode :original rows)
      order [:original :classic :fused :fused :classic :original]
      _ (doseq [mode [:original :classic :fused]]
          (assert (equal? expected (encode mode rows)))
          (assert (not (equal? expected (encode mode (assoc-in rows [0 :events 0 :name] "changed-event"))))))
      _ (doseq [mode order] (encode mode rows))
      observations
      (mapv (fn [ordinal]
              (let [mode (nth order (mod ordinal 6)) before (counters)
                    start (System/nanoTime) actual (encode mode rows)
                    elapsed (- (System/nanoTime) start) after (counters)]
                (assert (equal? expected actual))
                {:ordinal ordinal :mode mode :nanos elapsed
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after))
                                     (+ (:heap before) (:gc-bytes before)))
                 :cpu-nanos (- (:cpu after) (:cpu before))
                 :gc-count (- (:gc-count after) (:gc-count before))
                 :gc-nanos (- (:gc-nanos after) (:gc-nanos before))})) (range 48))
      summary (into {} (for [[mode samples] (group-by :mode observations)]
                         [mode {:p50-p90-p95-p99-ms
                                (mapv #(/ (quantile (map :nanos samples) %) 1.0e6) [0.5 0.9 0.95 0.99])
                                :median-estimated-bytes-per-row
                                (/ (quantile (map :estimated-bytes samples) 0.5) 5000.0)}]))]
  (spit output
    (pr-str {:scope :same-process-original-byte-kernel-regression-screen
             :original-source original-source-path :rows 5000 :samples-per-arm 16
             :same-raw-adapters true :frontend-and-loader-overhead-excluded true
             :sdk-policy-cache-shape-learning-and-typed-projection-included true
             :full-output-bytes-equal true :changed-event-controls true
             :original-and-candidate-values-never-cross-backends true
             :output-bytes (alength expected) :summary summary :observations observations
             :native-io false :durable-io false :tail-qualified false}))
  (prn summary))
