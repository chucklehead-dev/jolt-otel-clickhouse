;; Reuses the rich projection/nested/outer screen and prior shared/unshared
;; fixtures. Encoding only: no native I/O, WAL preparation, or persistence.
(require '[jolt.codec.experimental :as codec]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.durable.digest :as digest])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(load-file "/home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj")
(defn quantile [xs q]
  (nth (vec (sort xs)) (max 0 (dec (long (Math/ceil (* q (count xs))))))))
(let [c (codec/open-codec)
      contexts {false (#'e/load-experimental-span-codec! false)
                true (#'e/load-experimental-span-codec! true)}
      target (Object.) installation (#'installed/installed (fixture/approved) target)
      projector (projection/trace-vector-projector (:descriptor-set installation) target true
                                                  (:folder (contexts false)))
      shared (otel.research.trace-affinity/group-traces
               (mapv (fn [span]
                       (update span :attributes into
                         (map (fn [i] [(str "extra-" i)
                                      (case (mod i 3) 0 "value" 1 false i)]) (range 12))))
                     (nested-sorted-sdk-shape
                       (with-redefs [fixture/batch-size 5000]
                         (fixture/spans "typed-compact-prefix")))))
      unshared (mapv (fn [span]
                       (-> span
                           (update-in [:resource :attributes] #(into (sorted-map) %))
                           (update-in [:scope :attributes] #(into (sorted-map) %))
                           (update :events
                             (fn [events]
                               (mapv #(update % :attributes (fn [m] (into (sorted-map) m))) events)))))
                     shared)
      records {:shared shared :unshared unshared}
      _ (assert (= shared unshared))
      _ (assert (not (identical? (get-in (first unshared) [:resource :attributes])
                                  (get-in (second unshared) [:resource :attributes]))))
      emit (fn [enabled? rows]
             (let [context (contexts enabled?)
                   convert (when-let [factory (:payload-cache-factory context)]
                             (#'e/payload-attrs-converter (factory)))
                   nested (fn [value] (codec/prepare-nested-text c value))]
               (with-bindings {#'e/*timestamp-wire* :raw-ticks
                               #'e/*json-backend* :native-guarded-byte-batch
                               #'e/*experimental-attribute-transform* (:attributes context)}
                 (codec/encode-prefixed-records c "" rows
                   (fn [span]
                     (into (if convert (#'e/span-row span nil true nested convert)
                                       (#'e/span-row span nil true nested))
                           (projector span))) 67108864))))
      reference (emit false shared) size (alength reference)
      sha (digest/sha256-bytes reference)
      _ (doseq [shape [:shared :unshared] enabled? [false true]]
          (assert (= sha (digest/sha256-bytes (emit enabled? (records shape)))))
          (assert (not= sha (digest/sha256-bytes
                             (emit enabled? (assoc-in (records shape) [0 :events 0 :name]
                                                      "changed-event-control"))))))
      _ (doseq [shape [:shared :unshared] enabled? [false true true false]]
          (emit enabled? (records shape)))
      observations
      (mapv (fn [ordinal]
              (let [shape (nth [:shared :unshared :unshared :shared] (mod ordinal 4))
                    enabled? (contains? #{1 2} (mod (quot ordinal 4) 4))
                    before (#'bench/counter-sample) start (System/nanoTime)
                    output (emit enabled? (records shape)) elapsed (- (System/nanoTime) start)
                    after (#'bench/counter-sample)]
                (assert (= size (alength output)))
                (assert (= sha (digest/sha256-bytes output)))
                (let [sample {:ordinal ordinal :shape shape :cache? enabled? :nanos elapsed
                              :counters (#'bench/counter-delta before after)}]
                  (println (pr-str sample)) (flush) sample))) (range 64))
      summary (into {} (for [shape [:shared :unshared] enabled? [false true]
                            :let [samples (filter #(and (= shape (:shape %))
                                                        (= enabled? (:cache? %))) observations)]]
                        [[shape enabled?]
                         {:samples (count samples)
                          :ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                         [label (/ (quantile (map :nanos samples) q) 1e6)]))
                          :median-estimated-bytes-per-row
                          (/ (quantile (map #(get-in % [:counters :scheme-heap-bytes-allocated]) samples)
                                       0.5) 5000.0)}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :stock-library-full-rich-projection-nested-outer-component
             :rows 5000 :runtime :official-stock-0.8.23 :output-bytes size :output-sha256 sha
             :all-full-output-digests-equal true :changed-input-controls true
             :native-io false :durable-io false :tail-qualified false
             :summary summary :observations observations}))
  (println :summary (pr-str summary)))
