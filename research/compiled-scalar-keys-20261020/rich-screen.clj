;; Three arms, same encoder: ordinary normalized maps, value-slot views, compiled
;; key views. Component experiment, NOT public/native/Durable throughput proof.
(require '[perf.public-pipeline :as app]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[jolt.codec.experimental :as codec]
         '[jolt.codec.scalar-text.experimental :as shapes]
         '[jolt.host :as host] '[jolt.scheme :as scheme])

(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes) :cpu (host/cpu-nanos)
   :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))

(def baseline-json (second *command-line-args*))
(when baseline-json
  (assert (not (realized? @#'codec/backend)))
  ;; Isolate the original encoder while holding SDK/plans/callers fixed. The
  ;; additive factory is unused in this control but the current loader names it.
  (alter-var-root #'codec/json-source
    (constantly (str (slurp baseline-json) "\n(define ffc-json-compile-scalar-schema #f)"))))

(let [contexts (cond-> {:map (#'e/load-experimental-span-codec! true true true true)
                        :view (#'e/load-experimental-span-codec! true true true true true)}
                (not baseline-json)
                (assoc :compiled (with-redefs [shapes/native-view-plan-factory
                                        (fn [_]
                                          (shapes/native-compiled-view-plan-factory
                                            (codec/native-compiled-scalar-text-view-factory)))]
                            (#'e/load-experimental-span-codec! true true true true true))))
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
      modes (if baseline-json [:map :view] [:map :view :compiled])
      _ (doseq [mode modes]
          (assert (equal? expected (encode mode rows)))
          (assert (not (equal? expected (encode mode (assoc-in rows [0 :events 0 :name] "changed-event"))))))
      order (vec (concat modes (reverse modes)))
      _ (doseq [mode order] (encode mode rows))
      observations
      (mapv (fn [ordinal]
              (let [mode (nth order (mod ordinal (count order))) before (counters)
                    start (System/nanoTime) output (encode mode rows)
                    elapsed (- (System/nanoTime) start) after (counters)]
                (assert (equal? expected output))
                {:ordinal ordinal :mode mode :nanos elapsed
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after))
                                     (+ (:heap before) (:gc-bytes before)))
                 :cpu-nanos (- (:cpu after) (:cpu before))
                 :gc-count (- (:gc-count after) (:gc-count before))
                 :gc-nanos (- (:gc-nanos after) (:gc-nanos before))})) (range (* 16 (count modes))))
      summary (into {} (for [[mode samples] (group-by :mode observations)]
                         [mode {:p50-p90-p95-p99-ms
                                (mapv #(/ (quantile (map :nanos samples) %) 1e6) [0.5 0.9 0.95 0.99])
                                :median-estimated-bytes-per-row
                                (/ (quantile (map :estimated-bytes samples) 0.5) 5000.0)}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :rich-cumulative-compiled-key-component
             :rows 5000 :samples-per-arm 16 :full-output-bytes-equal true
             :changed-event-controls true :schema-compilation-in-every-sample true
             :sdk-policy-and-payload-cache-included true :typed-projection-included true
             :test-only-context-injection true :sdk-queue false :native-io false
             :baseline-json-source baseline-json :encoder-only-base-control (boolean baseline-json)
             :durable-io false :tail-qualified false
             :output-bytes (alength expected) :summary summary :observations observations}))
  (prn summary))
