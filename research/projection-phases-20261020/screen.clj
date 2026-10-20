(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[jolt.codec.experimental :as codec]
         '[jolt.scheme :as scheme]
         '[otel.exporter.chdb :as exporter]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.durable.digest :as digest])

(defn sha [bytes] (digest/sha256-bytes bytes))
(defn quantile [samples q]
  (nth (vec (sort samples)) (dec (long (Math/ceil (* q (count samples)))))))

(let [c (codec/open-codec {:flat-map-cache? true})
      context (#'exporter/load-experimental-span-codec! true true true)
      target (Object.)
      installation (#'installed/installed (fixture/approved) target)
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
      _ (assert (same-root? (get-in shared [0 :events 0 :attributes]) (get-in shared [1 :events 0 :attributes])))
      _ (assert (not (same-root? (get-in unshared [0 :events 0 :attributes]) (get-in unshared [1 :events 0 :attributes]))))
      records {:shared shared :unshared unshared}
      with-projector
      (fn [run]
        (codec/with-nested-text-writer c
          (fn [nested]
            (let [convert (#'exporter/payload-attrs-converter ((:payload-cache-factory context)))
                  project (fn [span] (into (#'exporter/span-row span nil true nested convert) (typed span)))]
              (with-bindings {#'exporter/*timestamp-wire* :raw-ticks
                              #'exporter/*json-backend* :native-guarded-byte-batch
                              #'exporter/*experimental-attribute-transform* (:attributes context)}
                (run project))))))
      project-rows (fn [spans] (with-projector #(mapv % spans)))
      encode-rows (fn [rows] (codec/encode-prefixed-records c "" rows identity 67108864))
      total (fn [spans] (with-projector #(codec/encode-prefixed-records c "" spans % 67108864)))
      prepared (into {} (for [[shape spans] records] [shape (project-rows spans)]))
      reference (total shared) size (alength reference) reference-sha (sha reference)
      _ (doseq [[shape spans] records]
          (assert (= reference-sha (sha (total spans))))
          (assert (= reference-sha (sha (encode-rows (prepared shape)))))
          (assert (every? #(and (vector? %) (= 28 (count %))) (prepared shape)))
          (let [changed (assoc-in spans [0 :events 0 :name] "changed-event")]
            (assert (not= reference-sha (sha (total changed))))
            (assert (not= reference-sha (sha (encode-rows (project-rows changed)))))))
      run (fn [shape mode]
            (case mode :projection (project-rows (records shape))
                       :encoding (encode-rows (prepared shape))
                       :total (total (records shape))))
      cells (vec (for [shape [:shared :unshared] mode [:projection :encoding :total]] [shape mode]))
      _ (doseq [_ (range 2) [shape mode] cells] (run shape mode))
      observations
      (vec (for [round (range 12)
                 [shape mode] (if (even? round) cells (reverse cells))]
             (let [before (#'bench/counter-sample) start (System/nanoTime)
                   output (run shape mode) nanos (- (System/nanoTime) start)
                   after (#'bench/counter-sample)
                   encoded (if (= mode :projection) (encode-rows output) output)]
               (assert (= size (alength encoded)))
               (assert (= reference-sha (sha encoded)))
               {:round round :shape shape :mode mode :nanos nanos
                :counters (#'bench/counter-delta before after)})))
      result {:scope :projection-and-encoding-component-diagnosis
              :rows 5000 :samples-per-cell 12 :runtime :official-stock-0.8.23
              :exporter-source (str (io/resource "otel/exporter/chdb.clj"))
              :sdk-source (str (io/resource "otel/any_value.clj"))
              :codec-source (str (io/resource "jolt/codec/experimental.clj"))
              :fixture-source (str (io/resource "otel/exporter/chdb_ordinary_transport_benchmark.clj"))
              :counter-source (str (io/resource "otel/exporter/chdb_benchmark.clj"))
              :output-bytes size :output-sha256 reference-sha
              :full-output-equality true :changed-input-controls true
              :producer-unchanged true :native-io false :durable-io false
              :additive-phase-attribution false :tail-qualified false
              :projection-includes-result-vector true
              :prepared-encoding-excludes-projection-and-nested-preparation true
              :observations observations
              :summary
              (into {} (for [[shape mode] cells]
                         (let [xs (filter #(and (= shape (:shape %)) (= mode (:mode %))) observations)]
                           [[shape mode]
                            {:p50-ms (/ (quantile (map :nanos xs) 0.5) 1e6)
                             :p90-ms (/ (quantile (map :nanos xs) 0.9) 1e6)
                             :p95-ms (/ (quantile (map :nanos xs) 0.95) 1e6)
                             :p99-ms (/ (quantile (map :nanos xs) 0.99) 1e6)
                             :median-estimated-bytes-per-row
                             (/ (quantile (map #(get-in % [:counters :scheme-heap-bytes-allocated]) xs) 0.5) 5000.0)}])))}]
  (assert (str/includes? (:sdk-source result) "3366a3322c3ea4024057f6278e50c999864171e8"))
  (assert (str/includes? (:codec-source result) "7ab2bedcb2e1845efdf6ed5c55ae9d2164338a8d"))
  (spit (first *command-line-args*) (pr-str result))
  (prn (:summary result)))
