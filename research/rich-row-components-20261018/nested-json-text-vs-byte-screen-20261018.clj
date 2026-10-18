;; Diagnostic only: retain the real byte-batch context, but vary the existing
;; public JSON hook during nested event serialization. No product selection.
(require '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.durable.digest :as digest])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [spans (nested-sorted-sdk-shape
              (with-redefs [fixture/batch-size 5000]
                (fixture/spans "typed-compact-prefix")))
      events (mapv :events spans)
      reference (binding [json/*experimental-native-writer* nil]
                  (mapv json/write-str events))
      hash-values (fn [values]
                    (digest/sha256-bytes (.getBytes (clojure.string/join "\n" values) "UTF-8")))
      reference-hash (hash-values reference)
      sample #'bench/counter-sample delta #'bench/counter-delta
      run! (fn [arm ordinal]
             (let [result (atom nil)
                   bytes (native/write-prefixed-batch-bytes!
                           ""
                           (lazy-seq
                             (let [hook (case arm
                                          :byte json/*experimental-native-writer*
                                          :text (native/load-payload-writer!))]
                               (binding [json/*experimental-native-writer* hook]
                                 (let [before (sample) start (System/nanoTime)
                                       values (mapv #'e/nested-json-string events)
                                       end (System/nanoTime) after (sample)]
                                   (assert (= reference values))
                                   (assert (= reference-hash (hash-values values)))
                                   (reset! result {:arm arm :ordinal ordinal
                                                   :nanos (- end start)
                                                   :counters (delta before after)}))))
                             nil) 0)]
               (assert (zero? (alength bytes)))
               @result))]
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch}
    (doseq [arm [:byte :text :text :byte]] (run! arm -1))
    (System/gc)
    (let [observations (mapv (fn [i]
                               (let [arm (nth [:byte :text :text :byte] (mod i 4))
                                     result (run! arm i)]
                                 (println :nested-json :arm arm :ordinal i :nanos (:nanos result))
                                 (flush)
                                 result)) (range 32))]
      (spit (first *command-line-args*)
            (pr-str {:scope :nested-event-json-component-not-durable
                     :runtime "7098a8d6" :exporter "4d48871" :json "96f3e47"
                     :rows 5000 :samples 32 :order [:byte :text :text :byte]
                     :ordinary-all-value-reference true :all-values-equal true
                     :reference-hash reference-hash :tail-qualified false
                     :observations observations})))))
