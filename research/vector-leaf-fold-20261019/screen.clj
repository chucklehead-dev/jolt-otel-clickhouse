;; Stock-Jolt component experiment, not Durable or SDK throughput qualification.
;; Run with -A:test:experimental-span-codec-test and an output EDN path.
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[jolt.scheme :as scheme]
         '[jolt.traversal.experimental :as traversal]
         '[otel.exporter.chdb :as exporter]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.durable.digest :as digest])

(defn quantile [xs q]
  (nth (vec (sort xs)) (max 0 (dec (long (Math/ceil (* q (count xs))))))))
(defn summary [samples rows]
  {:samples (count samples)
   :ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                  [label (/ (quantile (map :nanos samples) q) 1e6)]))
   :median-estimated-bytes-per-row
   (/ (quantile (map #(get-in % [:counters :scheme-heap-bytes-allocated]) samples) 0.5)
      (double rows))})
(defn timed [f]
  (let [before (#'bench/counter-sample) start (System/nanoTime)
        value (f) nanos (- (System/nanoTime) start)
        after (#'bench/counter-sample)]
    [value {:nanos nanos :counters (#'bench/counter-delta before after)}]))

(let [source (slurp (io/resource "jolt/traversal/kernel.ss"))
      start (str/index-of source "(define (ffc-vector-fold")
      end (str/index-of source ";; Vector SDK batches")
      old-fold "(define (ffc-vector-fold v visit acc)
                   (unless (pvec? v) (error 'ffc-vector-fold \"expected a persistent vector\"))
                   (let ((n (pvec-count v)))
                     (let loop ((i 0) (a acc))
                       (if (fx=? i n) a
                           (loop (fx+ i 1) (visit (pvec-nth! v i) a))))))\n"
      _ (assert (and start end (< start end)))
      old-source (str (subs source 0 start) old-fold (subs source end))
      kernels (into {} (for [[mode text] [[:indexed old-source] [:leaf source]]]
                        [mode (scheme/eval-string
                                (str "(let () " text
                                     " (jolt-vector ffc-map-fold ffc-vector-fold"
                                     " ffc-records-fold ffc-stock-sorted-map?))"))]))
      make-codec (scheme/eval-string
                   (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map?)"
                        " (let () " (slurp (io/resource "jolt/codec/json.ss"))
                        " (jolt-vector ffc-json-project-prefixed-ascii-lines-bytes ffc-json-nested-text)))"))
      codecs (into {} (for [[mode functions] kernels] [mode (apply make-codec functions)]))
      native-screen (scheme/eval-string
                      "(lambda (fold) (lambda (v rounds)
                         (let loop ((left rounds) (result 0))
                           (if (= left 0) result
                               (loop (- left 1) (fold v (lambda (x a) (+ x a)) 0))))))")
      micro (vec (for [n [24 1057 5000 32769]
                       :let [v (vec (range n)) rounds (max 1 (quot 1000000 n))
                             programs (into {} (for [[mode functions] kernels]
                                                 [mode (native-screen (nth functions 1))]))
                             expected (quot (* n (dec n)) 2)
                             _ (doseq [mode [:indexed :leaf]]
                                 (assert (= expected ((programs mode) v rounds))))]]
                   (let [samples (mapv (fn [ordinal]
                                         (let [mode (nth [:indexed :leaf :leaf :indexed] (mod ordinal 4))
                                               [result sample] (timed #((programs mode) v rounds))]
                                           (assert (= expected result))
                                           (assoc sample :mode mode :ordinal ordinal))) (range 32))]
                     {:elements n :rounds rounds :observations samples
                      :summary (into {} (for [mode [:indexed :leaf]]
                                          [mode (summary (filter #(= mode (:mode %)) samples)
                                                         (* n rounds))]))})))
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
             (let [[encode nested] (codecs mode)
                   convert (#'exporter/payload-attrs-converter ((:payload-cache-factory context)))
                   project (fn [span]
                             (into (#'exporter/span-row span nil true #(nested % 67108864 128) convert)
                                   (typed span)))]
               (with-bindings {#'exporter/*timestamp-wire* :raw-ticks
                               #'exporter/*json-backend* :native-guarded-byte-batch
                               #'exporter/*experimental-attribute-transform* (:attributes context)}
                 (encode "" records project 67108864 128))))
      reference (emit :indexed spans) size (alength reference)
      sha (digest/sha256-bytes reference)
      _ (assert (= sha (digest/sha256-bytes (emit :leaf spans))))
      _ (doseq [mode [:indexed :leaf]]
          (assert (not= sha (digest/sha256-bytes
                             (emit mode (assoc-in spans [0 :events 0 :name] "changed-event"))))))
      _ (doseq [mode [:indexed :leaf :leaf :indexed]] (emit mode spans))
      observations (mapv (fn [ordinal]
                           (let [mode (nth [:indexed :leaf :leaf :indexed] (mod ordinal 4))
                                 [output sample] (timed #(emit mode spans))]
                             (assert (= size (alength output)))
                             (assert (= sha (digest/sha256-bytes output)))
                             (let [sample (assoc sample :ordinal ordinal :mode mode)]
                               (println (pr-str sample)) (flush) sample))) (range 64))
      result {:scope :stock-vector-leaf-fold-component :runtime :official-stock-0.8.23
              :rows 5000 :output-bytes size :output-sha256 sha
              :baseline-kernel-sha256 (digest/sha256-bytes (.getBytes old-source "UTF-8"))
              :candidate-kernel-sha256 (digest/sha256-bytes (.getBytes source "UTF-8"))
              :json-kernel-identical true :sdk-normalization-cache-in-both-arms true
              :native-io false :durable-io false :tail-qualified false
              :all-output-digests-equal true :changed-input-controls true
              :micro micro :observations observations
              :summary (into {} (for [mode [:indexed :leaf]]
                                  [mode (summary (filter #(= mode (:mode %)) observations) 5000)]))}]
  (spit (first *command-line-args*) (pr-str result))
  (println :summary (pr-str (:summary result))))
