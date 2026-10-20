;; Full row projection + nested JSON + outer bytes. No native/Durable I/O.
(require '[jolt.codec.experimental :as codec]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.durable.digest :as digest])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(defn quantile [xs q]
  (nth (vec (sort xs)) (max 0 (dec (long (Math/ceil (* q (count xs))))))))
(let [c (codec/open-codec) context (#'e/load-experimental-span-codec!)
      target (Object.) installation (#'installed/installed (fixture/approved) target)
      projector (projection/trace-vector-projector (:descriptor-set installation) target true (:folder context))
      spans (mapv (fn [span]
                    (update span :attributes into
                      (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value" 1 false i)]) (range 12))))
                  (nested-sorted-sdk-shape
                    (with-redefs [fixture/batch-size 5000] (fixture/spans "typed-compact-prefix"))))
      emit (fn [mode records]
             (with-bindings {#'e/*timestamp-wire* :raw-ticks
                             #'e/*json-backend* :native-guarded-byte-batch
                             #'e/*experimental-attribute-transform* (:attributes context)}
               (codec/encode-prefixed-records c "" records
                 (fn [span]
                   (into (#'e/span-row span nil true
                           (fn [value]
                             (if (= mode :string)
                               (codec/write-nested c value)
                               (codec/prepare-nested-text c value))))
                         (projector span))) 67108864)))
      reference (emit :string spans) size (alength reference) sha (digest/sha256-bytes reference)
      _ (assert (= sha (digest/sha256-bytes (emit :bytes spans))))
      _ (doseq [mode [:string :bytes]]
          (assert (not= sha (digest/sha256-bytes
                             (emit mode (assoc-in spans [0 :events 0 :name] "changed-event-control"))))))
      _ (doseq [mode [:string :bytes :bytes :string]] (emit mode spans))
      observations
      (mapv (fn [i]
              (let [mode (nth [:string :bytes :bytes :string] (mod i 4))
                    before (#'bench/counter-sample) start (System/nanoTime)
                    output (emit mode spans) elapsed (- (System/nanoTime) start)
                    after (#'bench/counter-sample)]
                (assert (= size (alength output)))
                (assert (= sha (digest/sha256-bytes output)))
                (let [sample {:ordinal i :mode mode :nanos elapsed
                              :counters (#'bench/counter-delta before after)}]
                  (println (pr-str sample)) (flush) sample))) (range 32))
      summary (into {} (for [mode [:string :bytes]
                            :let [samples (filter #(= mode (:mode %)) observations)]]
                        [mode {:samples (count samples)
                               :ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                                               [label (/ (quantile (map :nanos samples) q) 1e6)]))
                               :median-estimated-bytes-per-row
                               (/ (quantile (map #(get-in % [:counters :scheme-heap-bytes-allocated]) samples) 0.5) 5000.0)}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :full-rich-projection-nested-outer-component :rows 5000
             :runtime :official-stock-0.8.23 :output-bytes size :output-sha256 sha
             :all-full-output-digests-equal true :changed-input-control true
             :native-io false :durable-io false :tail-qualified false
             :summary summary :observations observations}))
  (println :summary (pr-str summary)))
