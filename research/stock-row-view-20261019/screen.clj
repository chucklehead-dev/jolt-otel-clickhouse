;; Stock-Jolt row-construction experiment; no exporter production change.
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.walk :as walk]
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

;; Reuse the live projection body exactly, replacing only the final compact
;; vector constructor with a supplied fixed-arity builder. No reimplementation
;; of conversion logic, global Var replacement, or exporter source mutation.
(defn row-factory []
  (let [text (slurp (io/resource "otel/exporter/chdb.clj"))
        start (str/index-of text "(defn- span-row")
        end (str/index-of text "(defn- log-row")
        definition (binding [*ns* (find-ns 'otel.exporter.chdb)]
                     (read-string (subs text start end)))
        [parameters body] (last definition)
        replaced (atom 0)
        body (walk/postwalk
               (fn [form]
                 (if (and (vector? form) (= 24 (count form))
                          (= ['time 'trace-id 'span-id 'parent-id] (subvec form 0 4)))
                   (do (swap! replaced inc) (list* 'row-builder form)) form)) body)
        _ (assert (= 1 @replaced))
        factory (binding [*ns* (find-ns 'otel.exporter.chdb)]
                  (eval (list 'fn ['row-builder] (list 'fn parameters body))))]
    {:factory factory :source-sha256 (digest/sha256-bytes (.getBytes text "UTF-8"))}))

(let [c (codec/open-codec)
      {:keys [factory source-sha256]} (row-factory)
      row-view (factory (codec/row-builder c 24))
      context (#'exporter/load-experimental-span-codec! true false)
      target (Object.) installation (#'installed/installed (fixture/approved) target)
      typed (projection/trace-vector-projector (:descriptor-set installation) target true
                                               (:folder context))
      attrs (into (sorted-map "service.name" "producer-shape" "flag" false "count" 42)
                  (map (fn [i] [(str "extra-" i)
                                (case (mod i 3) 0 "value-λ" 1 false i)]) (range 14)))
      spans (mapv (fn [span]
                    (-> span (assoc-in [:resource :attributes] attrs)
                        (assoc-in [:scope :attributes] attrs)
                        (update :attributes into
                          (map (fn [i] [(str "extra-" i)
                                        (case (mod i 3) 0 "value" 1 false i)]) (range 12)))
                        (assoc :events [{:name "scenario-event"
                                         :timestamp-unix-nano (:start-time-unix-nano span)
                                         :attributes attrs}])))
                  (with-redefs [fixture/batch-size 5000] (fixture/spans "typed-compact-prefix")))
      emit (fn [mode records]
             (let [convert (#'exporter/payload-attrs-converter ((:payload-cache-factory context)))
                   nested #(codec/prepare-nested-text c %)
                   project (if (= mode :vector)
                             (fn [span]
                               (let [row (into (#'exporter/span-row span nil true nested convert) (typed span))]
                                 (assert (and (vector? row) (= 28 (count row)))) row))
                             (fn [span]
                               (let [row (codec/append-row-fields c
                                           (row-view span nil true nested convert) (typed span))]
                                 (assert (= 28 (codec/row-width c row))) row)))]
               (with-bindings {#'exporter/*timestamp-wire* :raw-ticks
                               #'exporter/*json-backend* :native-guarded-byte-batch
                               #'exporter/*experimental-attribute-transform* (:attributes context)}
                 (codec/encode-prefixed-records c "" records project 67108864))))
      reference (emit :vector spans) size (alength reference) sha (digest/sha256-bytes reference)
      _ (assert (= sha (digest/sha256-bytes (emit :row spans))))
      _ (doseq [mode [:vector :row]]
          (assert (not= sha (digest/sha256-bytes
                             (emit mode (assoc-in spans [0 :events 0 :name] "changed-event"))))))
      _ (doseq [mode [:vector :row :row :vector]] (emit mode spans))
      observations (mapv (fn [ordinal]
                           (let [mode (nth [:vector :row :row :vector] (mod ordinal 4))
                                 before (#'bench/counter-sample) start (System/nanoTime)
                                 output (emit mode spans) nanos (- (System/nanoTime) start)
                                 after (#'bench/counter-sample)]
                             (assert (= size (alength output)))
                             (assert (= sha (digest/sha256-bytes output)))
                             (let [sample {:ordinal ordinal :mode mode :nanos nanos
                                           :counters (#'bench/counter-delta before after)}]
                               (println (pr-str sample)) (flush) sample))) (range 64))
      result {:scope :stock-row-view-projection-nested-outer-component
              :rows 5000 :runtime :official-stock-0.8.23 :output-bytes size :output-sha256 sha
              :exporter-source-sha256 source-sha256 :exporter-production-source-unchanged true
              :sdk-cache-in-both-arms true :full-output-equality true :changed-input-controls true
              :native-io false :durable-io false :tail-qualified false
              :summary (into {} (for [mode [:vector :row]]
                                  [mode (summarize (filter #(= mode (:mode %)) observations))]))
              :observations observations}]
  (spit (first *command-line-args*) (pr-str result))
  (println :summary (pr-str (:summary result))))
