;; Synchronous source component diagnostic, not native Durable qualification.
;; Cost centers are inclusive executing-thread CPU. Nested regions cannot sum.
(require '[perf.public-pipeline :as app]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[jolt.host :as host] '[jolt.scheme :as scheme])

(def labels [:batch :row :attributes :typed :nested])
(def observer-factory
  (scheme/eval-string
    "(lambda (mode)
       (let ((centers (vector (make-cost-center 'batch) (make-cost-center 'row)
                              (make-cost-center 'attributes) (make-cost-center 'typed)
                              (make-cost-center 'nested)))
             (calls (make-vector 5 0)))
         (jolt-vector
           (lambda (index f)
             (cond
               ((fx=? mode 0) f)
               ((fx=? mode 1)
                (lambda (x)
                  (vector-set! calls index (fx+ (vector-ref calls index) 1))
                  (f x)))
               (else
                (let ((cc (vector-ref centers index)))
                  (lambda (x)
                    (vector-set! calls index (fx+ (vector-ref calls index) 1))
                    (with-cost-center #t cc (lambda () (f x))))))))
           (lambda ()
             (apply jolt-vector
               (let loop ((i 0) (out '()))
                 (if (fx=? i 5) (reverse out)
                   (let ((t (cost-center-time (vector-ref centers i))))
                     (loop (fx+ i 1)
                       (cons (vector-ref calls i)
                         (cons (+ (* 1000000000 (time-second t)) (time-nanosecond t)) out)))))))))))"))

(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes) :cpu (host/cpu-nanos)
   :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))
(def bytes-equal?
  (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))"))

(let [context (#'e/load-experimental-span-codec! true true true true true)
      target (Object.) installation (#'installed/installed (app/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true (:folder context))
      rows (app/fixture 5000)
      encode
      (fn [mode values]
        (let [[wrap snapshot] (observer-factory ({:plain 0 :shape 1 :timed 2} mode))
              batch
              (wrap 0
                (fn [values]
                  (let [convert (wrap 2 (#'e/payload-attrs-converter ((:payload-cache-factory context))))
                        typed (wrap 3 typed) nested (wrap 4 (:nested context))
                        project (wrap 1
                                  (fn [span]
                                    (let [row (into (#'e/span-row span nil true nested convert) (typed span))]
                                      (assert (= 28 (count row))) row)))]
                    (with-bindings {#'e/*timestamp-wire* :raw-ticks
                                    #'e/*json-backend* :native-guarded-byte-batch
                                    #'e/*experimental-attribute-transform* (:attributes context)}
                      ((:encode context) "SQL\n" values project 67108864)))))
              before (counters) start (System/nanoTime)
              output (batch values) elapsed (- (System/nanoTime) start) after (counters)
              regions (into {} (map (fn [label [cpu calls]] [label {:cpu-nanos cpu :calls calls}])
                                    labels (partition 2 (snapshot))))]
          (when-not (= :plain mode)
            (assert (= [1 (count values) (* 3 (count values)) (count values) (* 2 (count values))]
                       (mapv #(get-in regions [% :calls]) labels))))
          {:output output :sample
           {:mode mode :nanos elapsed :regions regions
            :estimated-bytes (- (+ (:heap after) (:gc-bytes after)) (+ (:heap before) (:gc-bytes before)))
            :cpu-nanos (- (:cpu after) (:cpu before))
            :gc-count (- (:gc-count after) (:gc-count before))
            :gc-nanos (- (:gc-nanos after) (:gc-nanos before))}}))
      expected (:output (encode :plain rows))
      ;; Full bytes, not only size/count; changed input must remain observable.
      _ (doseq [mode [:plain :shape :timed]]
          (assert (bytes-equal? expected (:output (encode mode rows))))
          (assert (not (bytes-equal? expected
                         (:output (encode mode (assoc-in rows [0 :events 0 :name] "changed-event")))))))
      ;; Check each observer forwards the exact thrown value, not another error.
      _ (doseq [mode [0 1 2]]
          (let [[wrap _] (observer-factory mode) failure (ex-info "expected" {:probe true})
                f (wrap 1 (fn [_] (throw failure)))]
            (assert (try (f nil) false (catch Exception error (identical? failure error))))))
      order [:plain :shape :timed :timed :shape :plain]
      _ (doseq [mode order] (encode mode rows))
      samples (mapv
                (fn [ordinal]
                  (let [result (encode (nth order (mod ordinal 6)) rows)]
                    (assert (bytes-equal? expected (:output result)))
                    (assoc (:sample result) :ordinal ordinal))) (range 48))
      summary
      (into {} (for [[mode observations] (group-by :mode samples)]
                 [mode {:p50-p90-p95-p99-ms
                        (mapv #(/ (quantile (map :nanos observations) %) 1e6) [0.5 0.9 0.95 0.99])
                        :median-estimated-bytes-per-row
                        (/ (quantile (map :estimated-bytes observations) 0.5) 5000.0)
                        :median-region-cpu-ms
                        (into {} (map (fn [label]
                                        [label (/ (quantile (map #(get-in % [:regions label :cpu-nanos]) observations) 0.5) 1e6)])
                                      labels))}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :rich-synchronous-source-cost-centers :rows 5000 :samples-per-arm 16
             :runtime :official-jolt-0.8.23 :sdk-policy-and-typed-projection true
             :full-output-bytes-equal true :changed-event-controls true :exact-error-forwarded true
             :callback-counts-checked true :output-bytes (alength expected)
             :regions-inclusive true :cpu-scope :executing-thread
             :allocation-estimate :non-atomic-live-heap-plus-reclaimed
             :durable-io false :native-worker-cpu false :tail-qualified false
             :summary summary :samples samples}))
  (prn summary))
