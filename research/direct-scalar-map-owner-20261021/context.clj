(require '[otel.any-value :as any] '[otel.exporter.chdb :as exporter]
         '[jolt.codec.experimental :as codec] '[jolt.codec.scalar-text.experimental :as shapes]
         '[jolt.scheme :as scheme] '[jolt.host :as host]
         '[perf.public-pipeline :as app]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed])

(defn owner-context [direct?]
  (let [context (#'exporter/load-experimental-span-codec! true true true true true)
        options (cond-> {:shape-plan-factory
                         (shapes/native-view-plan-factory (codec/native-scalar-text-view-box))}
                  direct? (assoc :ordinary-map-view-box (codec/native-direct-scalar-map-box)))
        factory #(any/scalar-text-payload-projector (:folder context) options)]
    (assert (ifn? (factory)))
    (assoc context :payload-cache-factory factory)))

(defn rich-encoder [contexts]
  (let [target (Object.) installation (#'installed/installed (app/approved) target)
        typed (projection/trace-vector-projector (:descriptor-set installation) target true
                                                 (:folder (:baseline contexts)))]
    (fn [arm values]
      (let [context (get contexts arm)
            convert (#'exporter/payload-attrs-converter ((:payload-cache-factory context)))
            project (fn [span]
                      (let [row (into (#'exporter/span-row span nil true (:nested context) convert)
                                      (typed span))]
                        (assert (= 28 (count row))) row))]
        (with-bindings {#'exporter/*timestamp-wire* :raw-ticks
                        #'exporter/*json-backend* :native-guarded-byte-batch
                        #'exporter/*experimental-attribute-transform* (:attributes context)}
          ((:encode context) "SQL\n" values project 67108864))))))

(def bytes-equal? (scheme/eval-string
                    "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))"))
(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes)
   :thread-cpu (host/cpu-nanos) :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn quantile [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))
