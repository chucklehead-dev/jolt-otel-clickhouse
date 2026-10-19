(require '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[clojure.edn :as edn]
         '[jolt.scheme :as scheme])
;; Reuse the exact original observer, installed before native writer loading.
;; It calls through every production operation; gross readings overlap and
;; are accepted only when the complete measured pass has no collection.
(scheme/eval-string
  (slurp "/home/chuck/ai-src/evidence/nested-json-allocation-boundaries-20261018.ss"))
(native/load-writer!)
((scheme/proc "research-install-writer-publication-probe!"))
(require '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [events (mapv :events (nested-sorted-sdk-shape
                           (with-redefs [fixture/batch-size 64]
                             (fixture/spans "typed-compact-prefix"))))
      reference (binding [json/*experimental-native-writer* nil]
                  (mapv json/write-str events))
      sample #'bench/counter-sample
      delta #'bench/counter-delta
      toggle (scheme/proc "research-allocation-toggle!")
      report (scheme/proc "research-allocation-report")
      run! (fn [ordinal measured?]
             (let [observation (atom nil)
                   bytes (native/write-prefixed-batch-bytes!
                           ""
                           (lazy-seq
                             (toggle measured?)
                             (let [before (sample)
                                   values (try (mapv native/write-str events)
                                            (finally
                                              (scheme/eval-string
                                                "(set! research-allocation-enabled? #f)")))
                                   after (sample)
                                   counters (delta before after)
                                   boundaries
                                   (zipmap [:json-site :key-policy :omission-equality
                                            :sorted-fold :pmap-fold :publication]
                                     (mapv (fn [[calls bytes]]
                                             {:calls calls :gross-bytes bytes})
                                       (partition 2 (edn/read-string (report)))))]
                               (assert (= reference values))
                               (reset! observation
                                 {:ordinal ordinal :counters counters
                                  :allocation-valid? (zero? (:gc-count counters))
                                  :boundaries boundaries}))
                             nil)
                           0)]
               (assert (zero? (alength bytes)))
               @observation))]
  (dotimes [_ 3] (run! -1 false))
  (System/gc)
  (let [observations (mapv #(run! % true) (range 16))
        valid (filterv :allocation-valid? observations)]
    (assert (seq valid))
    ;; Default identity checks must no longer enter observed structural
    ;; equality. Positive observer controls are covered by the paired baseline
    ;; attribution and the library's focused red/green mechanism test.
    (assert (every? #(zero? (get-in % [:boundaries :omission-equality :calls]))
              valid))
    (spit (first *command-line-args*)
      (pr-str {:scope :instrumented-post-identity-nested-allocation-not-timing
               :runtime "7098a8d6" :exporter "4d48871" :json "2172061"
               :rows 64 :rounds 16 :all-output-reference-controls true
               :gross-nested-boundaries-not-additive true
               :gc-crossing-readings-rejected true :observations observations}))
    (println :completed :no-gc-rounds (count valid)
      :boundaries (:boundaries (first valid)))))
