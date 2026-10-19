;; Existing typed fixture, exact same prepared values in both arms. Excludes
;; projection; nested EventsJSON/LinksJSON are prepared by the selected backend
;; once before measurement. Does not claim stock-fork removal for that adapter.
(require '[clojure.data.json :as json] '[clojure.data.json.jolt-native :as native]
         '[jolt.scheme :as scheme]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.durable.digest :as digest])
(def here (first *command-line-args*))
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(load-file "/home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj")
(scheme/eval-string (slurp (str here "/traversal.ss")))
(scheme/eval-string (slurp (str here "/json.ss")))
(native/load-payload-byte-buffer-writer!)
(def direct (scheme/proc "ffc-json-ascii-lines-bytes"))
(def naive (scheme/eval-string
             (str "(let () " (slurp (str here "/rich-initial/json.ss"))
                  "\n ffc-json-ascii-lines-bytes)")))
(def sample #'bench/counter-sample)
(def delta #'bench/counter-delta)
(defn quantile [xs q]
  (nth (vec (sort xs)) (max 0 (dec (long (Math/ceil (* q (count xs))))))))
(let [target (Object.) installation (#'installed/installed (fixture/approved) target)
      descriptors (:descriptor-set installation)
      fields (projection/confirmed-span-fields descriptors target)
      projector (projection/trace-vector-projector descriptors target true)
      columns (fixture/columns fields)
      _ (assert (= #{:boolean :int64} (set (map :type fields))))
      spans (otel.research.trace-affinity/group-traces
              (mapv (fn [span]
                      (update span :attributes into
                        (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value" 1 false i)]))
                        (range 12)))
                (nested-sorted-sdk-shape
                  (with-redefs [fixture/batch-size 5000]
                    (fixture/spans "typed-compact-prefix")))))
      rows (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
             (with-bindings {#'e/*timestamp-wire* :raw-ticks
                             #'e/*json-backend* :native-guarded-byte-batch}
               (mapv (fn [span] (into (#'e/span-row span nil true) (projector span))) spans)))
      _ (assert (= 5000 (count rows)))
      _ (assert (every? #(= 28 (count %)) rows))
      _ (assert (sorted? (get-in (first spans) [:resource :attributes])))
      _ (assert (= (digest/sha256-bytes
                     (native/write-prefixed-batch-bytes! "" [(get-in (first spans) [:resource :attributes])] 67108864))
                    (digest/sha256-bytes
                     (direct [(get-in (first spans) [:resource :attributes])] 67108864 128))))
      reference (native/write-prefixed-batch-bytes! "" rows 67108864)
      sha (digest/sha256-bytes reference) size (alength reference)
      _ (assert (= sha (digest/sha256-bytes (direct rows 67108864 128))))
      _ (assert (not= sha (digest/sha256-bytes
                            (direct (assoc-in rows [0 5] "changed-input-control") 67108864 128))))
      emit (fn [mode]
             (case mode :selected (native/write-prefixed-batch-bytes! "" rows 67108864)
                        :naive (naive rows 67108864 128)
                        :explicit (direct rows 67108864 128)))
      _ (doseq [mode [:selected :naive :explicit :explicit :naive :selected]] (emit mode))
      _ (System/gc)
      observations
      (mapv (fn [i]
              (let [mode (nth [:selected :naive :explicit :explicit :naive :selected] (mod i 6))
                    before (sample) start (System/nanoTime)
                    bytes (emit mode)
                    elapsed (- (System/nanoTime) start) after (sample)]
                (assert (= size (alength bytes)))
                (assert (= sha (digest/sha256-bytes bytes)))
                (let [out {:ordinal i :mode mode :nanos elapsed :counters (delta before after)}]
                  (println (pr-str out)) (flush) out))) (range 48))
      summary
      (into {} (for [mode [:selected :naive :explicit]
                     :let [samples (filter #(= mode (:mode %)) observations)]]
                 [mode {:samples (count samples)
                        :ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                        [label (/ (quantile (map :nanos samples) q) 1e6)]))
                        :median-estimated-bytes-per-row
                        (/ (quantile (map #(get-in % [:counters :scheme-heap-bytes-allocated]) samples) 0.5)
                           5000.0)}]))]
  (spit (second *command-line-args*)
    (pr-str {:scope :rich-typed-prepared-selected-versus-explicit-codec
             :runtime "7098a8d6" :exporter "2c54b98" :sdk "7878a2e" :json "2172061"
             :rows 5000 :columns columns :payload-bytes size :payload-sha256 sha
             :samples 48 :summary summary :observations observations
             :same-prepared-row-values true :all-full-output-digests-equal true
             :raw-input-nested-sorted true :raw-sorted-map-byte-equivalence-control true
             :changed-input-control true :projection-in-timed-interval false
             :nested-json-produced-by-selected-backend true
             :stock-runtime-full-consumer-qualified false
             :durable-execution false :tail-qualified false}))
  (println :summary (pr-str summary)))
