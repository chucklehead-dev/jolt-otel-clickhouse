;; Full projection + nested JSON + outer encoding, but NOT native/Durable I/O.
;; Uses the isolated four-arity span-row adapter; existing arities are unchanged.
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
(def project-lines (scheme/proc "ffc-json-project-ascii-lines-bytes"))
(def encode-value (scheme/proc "ffc-json-ascii-bytes"))
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
      write-nested (fn [x]
                     (if (and (vector? x) (empty? x)) "[]"
                       (String. (encode-value x 67108864 128) "UTF-8")))
      ordinary-row (fn [span] (into (#'e/span-row span nil true) (projector span)))
      explicit-row (fn [span] (into (#'e/span-row span nil true write-nested) (projector span)))
      emit (fn [mode records]
             (with-bindings {#'e/*timestamp-wire* :raw-ticks
                             #'e/*json-backend* :native-guarded-byte-batch}
               (case mode
                 :selected (native/write-prefixed-batch-bytes! "" (map ordinary-row records) 67108864)
                 :outer-only
                 (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
                   (project-lines records ordinary-row 67108864 128))
                 :explicit (project-lines records explicit-row 67108864 128))))
      reference (emit :selected spans) sha (digest/sha256-bytes reference) size (alength reference)
      _ (doseq [mode [:outer-only :explicit]]
          (assert (= sha (digest/sha256-bytes (emit mode spans)))))
      _ (doseq [mode [:selected :outer-only :explicit]]
          (assert (not= sha (digest/sha256-bytes
                             (emit mode (assoc spans 0 (assoc (first spans) :name "changed-input-control")))))))
      _ (doseq [mode [:selected :outer-only :explicit :explicit :outer-only :selected]] (emit mode spans))
      _ (System/gc)
      observations
      (mapv (fn [i]
              (let [mode (nth [:selected :outer-only :explicit :explicit :outer-only :selected] (mod i 6))
                    before (sample) start (System/nanoTime)
                    bytes (emit mode spans)
                    elapsed (- (System/nanoTime) start) after (sample)]
                (assert (= size (alength bytes)))
                (assert (= sha (digest/sha256-bytes bytes)))
                (let [out {:ordinal i :mode mode :nanos elapsed :counters (delta before after)}]
                  (println (pr-str out)) (flush) out))) (range 48))
      summary
      (into {} (for [mode [:selected :outer-only :explicit]
                     :let [samples (filter #(= mode (:mode %)) observations)]]
                 [mode {:samples (count samples)
                        :ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                        [label (/ (quantile (map :nanos samples) q) 1e6)]))
                        :median-estimated-bytes-per-row
                        (/ (quantile (map #(get-in % [:counters :scheme-heap-bytes-allocated]) samples) 0.5)
                           5000.0)}]))]
  (spit (second *command-line-args*)
    (pr-str {:scope :rich-typed-projection-nested-outer-component
             :runtime "7098a8d6" :exporter-base "2c54b98" :sdk "7878a2e" :json "2172061"
             :rows 5000 :columns columns :payload-bytes size :payload-sha256 sha
             :samples 48 :summary summary :observations observations
             :all-full-output-digests-equal true :changed-input-control true
             :projection-in-timed-interval true :nested-json-in-timed-interval true
             :outer-only-nested-binding-is-fresh-but-without-collector-string-companion true
             :explicit-nested-and-outer-use-library-codec true
             :stock-runtime-full-consumer-qualified false
             :owned-statement-snapshot-included false :durable-execution false :tail-qualified false}))
  (println :summary (pr-str summary)))
