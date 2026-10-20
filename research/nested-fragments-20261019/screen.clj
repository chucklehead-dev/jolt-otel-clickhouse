;; Encoding component only. Both arms retain SDK, outer wire-cache and row-view work.
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.walk :as walk]
         '[jolt.scheme :as scheme]
         '[jolt.codec.experimental :as codec]
         '[otel.exporter.chdb :as exporter]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.durable.digest :as digest])

(defn quantile [xs q]
  (nth (vec (sort xs)) (max 0 (dec (long (Math/ceil (* q (count xs))))))))
(defn summarize [samples]
  {:samples (count samples)
   :ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                  [label (/ (quantile (map :nanos samples) q) 1e6)]))
   :median-estimated-bytes-per-row
   (/ (quantile (map #(get-in % [:counters :scheme-heap-bytes-allocated]) samples) 0.5) 5000.0)})

(defn row-factory []
  (let [text (slurp (io/resource "otel/exporter/chdb.clj"))
        start (str/index-of text "(defn- span-row") end (str/index-of text "(defn- log-row")
        definition (binding [*ns* (find-ns 'otel.exporter.chdb)] (read-string (subs text start end)))
        [parameters body] (last definition) replacements (atom 0)
        body (walk/postwalk
               (fn [form]
                 (if (and (vector? form) (= 24 (count form))
                          (= ['time 'trace-id 'span-id 'parent-id] (subvec form 0 4)))
                   (do (swap! replacements inc) (list* 'row-builder form)) form)) body)]
    (assert (= 1 @replacements))
    {:factory (binding [*ns* (find-ns 'otel.exporter.chdb)]
                (eval (list 'fn ['row-builder] (list 'fn parameters body))))
     :source-sha256 (digest/sha256-bytes (.getBytes text "UTF-8"))}))

(let [c (codec/open-codec {:flat-map-cache? true})
      {:keys [factory source-sha256]} (row-factory)
      row-view (factory (codec/row-builder c 24))
      context (#'exporter/load-experimental-span-codec! true true)
      target (Object.) installation (#'installed/installed (fixture/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true (:folder context))
      attrs (into (sorted-map "service.name" "producer-shape" "flag" false "count" 42)
                  (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value-λ" 1 false i)]) (range 14)))
      shared (mapv (fn [span]
                     (-> span (assoc-in [:resource :attributes] attrs)
                         (assoc-in [:scope :attributes] attrs)
                         (update :attributes into
                           (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value" 1 false i)]) (range 12)))
                         (assoc :events [{:name "scenario-event"
                                          :timestamp-unix-nano (:start-time-unix-nano span)
                                          :attributes attrs}])))
                   (with-redefs [fixture/batch-size 5000] (fixture/spans "typed-compact-prefix")))
      unshared (mapv (fn [span]
                       (-> span (update-in [:resource :attributes] #(into (sorted-map) %))
                           (update-in [:scope :attributes] #(into (sorted-map) %))
                           (update :events
                             (fn [events] (mapv #(update % :attributes (fn [m] (into (sorted-map) m))) events))))) shared)
      same-root? (scheme/eval-string "(lambda (a b) (eq? (jolt-ref-get a kw-tree) (jolt-ref-get b kw-tree)))")
      _ (assert (= shared unshared))
      _ (assert (same-root? (get-in shared [0 :events 0 :attributes])
                           (get-in shared [1 :events 0 :attributes])))
      _ (assert (not (same-root? (get-in unshared [0 :events 0 :attributes])
                                (get-in unshared [1 :events 0 :attributes]))))
      records {:shared shared :unshared unshared}
      emit (fn [enabled? records]
             (let [run (fn [nested]
                         (let [convert (#'exporter/payload-attrs-converter ((:payload-cache-factory context)))
                               project (fn [span]
                                         (let [row (codec/append-row-fields c
                                                     (row-view span nil true nested convert) (typed span))]
                                           (assert (= 28 (codec/row-width c row))) row))]
                           (with-bindings {#'exporter/*timestamp-wire* :raw-ticks
                                           #'exporter/*json-backend* :native-guarded-byte-batch
                                           #'exporter/*experimental-attribute-transform* (:attributes context)}
                             (codec/encode-prefixed-records c "" records project 67108864))))]
               (if enabled? (codec/with-nested-text-writer c run)
                   (run #(codec/prepare-nested-text c %)))))
      reference (emit false shared) size (alength reference) sha (digest/sha256-bytes reference)
      _ (doseq [shape [:shared :unshared] enabled? [false true]]
          (assert (= sha (digest/sha256-bytes (emit enabled? (records shape)))))
          (assert (not= sha (digest/sha256-bytes
                             (emit enabled? (assoc-in (records shape) [0 :events 0 :name] "changed-event"))))))
      _ (doseq [shape [:shared :unshared] enabled? [false true true false]] (emit enabled? (records shape)))
      schedule (vec (for [block (range 32) slot (range 4)]
                      {:shape (nth [:shared :unshared :unshared :shared] slot)
                       :enabled? (contains? #{1 2} (mod block 4))}))
      observations (mapv (fn [ordinal]
                           (let [{:keys [shape enabled?]} (nth schedule ordinal)
                                 before (#'bench/counter-sample) start (System/nanoTime)
                                 output (emit enabled? (records shape)) nanos (- (System/nanoTime) start)
                                 after (#'bench/counter-sample)]
                             (assert (= size (alength output)))
                             (assert (= sha (digest/sha256-bytes output)))
                             (let [sample {:ordinal ordinal :shape shape :enabled? enabled? :nanos nanos
                                           :counters (#'bench/counter-delta before after)}]
                               (println (pr-str sample)) (flush) sample))) (range (count schedule)))
      result {:scope :payload-scoped-nested-fragments-encoding-component
              :runtime :official-stock-0.8.23 :rows 5000 :output-bytes size :output-sha256 sha
              :exporter-source-sha256 source-sha256 :producer-code-unchanged true
              :sdk-cache-in-both-arms true :outer-wire-cache-in-both-arms true :row-view-in-both-arms true
              :shared-root-positive-and-unshared-root-negative true
              :full-output-equality true :changed-input-controls true
              :native-io false :durable-io false :tail-qualified false
              :summary (into {} (for [shape [:shared :unshared] enabled? [false true]]
                                  [[shape enabled?] (summarize (filter #(and (= shape (:shape %))
                                                                            (= enabled? (:enabled? %))) observations))]))
              :observations observations}]
  (spit (first *command-line-args*) (pr-str result))
  (println :summary (pr-str (:summary result))))
