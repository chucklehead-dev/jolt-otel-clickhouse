(require '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[jolt.scheme :as scheme])
;; Install before the optional native writer is compiled, not after it may
;; have captured/inlined the uninstrumented helpers.
(scheme/eval-string
  (slurp "/home/chuck/ai-src/evidence/nested-json-allocation-boundaries-20261018.ss"))
(native/load-writer!)
((scheme/proc "research-install-writer-publication-probe!"))
(require '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.durable.digest :as digest])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [events (mapv :events (nested-sorted-sdk-shape
                            (with-redefs [fixture/batch-size 64]
                              (fixture/spans "typed-compact-prefix"))))
      reference (binding [json/*experimental-native-writer* nil] (mapv json/write-str events))
      sample #'bench/counter-sample delta #'bench/counter-delta
      toggle (scheme/proc "research-allocation-toggle!")
      report (scheme/proc "research-allocation-report")
      run! (fn [ordinal measured?]
             (let [observation (atom nil)
                   bytes (native/write-prefixed-batch-bytes! ""
                           (lazy-seq
                             (toggle measured?)
                             (let [before (sample)
                                   values (try (mapv native/write-str events)
                                               (finally
                                                 ;; Freeze readings before any assertions/reporting.
                                                 (scheme/eval-string "(set! research-allocation-enabled? #f)")))
                                   after (sample)
                                   counters (delta before after)
                                   boundaries (zipmap [:json-site :key-policy :omission-equality
                                                       :sorted-fold :pmap-fold :publication]
                                                     (mapv (fn [[calls bytes]] {:calls calls :gross-bytes bytes})
                                                           (partition 2 (edn/read-string (report)))))]
                               (assert (= reference values))
                               (reset! observation {:ordinal ordinal :counters counters
                                                    :allocation-valid? (zero? (:gc-count counters))
                                                    :boundaries boundaries}))
                             nil) 0)]
               (assert (zero? (alength bytes)))
               @observation))]
  (dotimes [_ 3] (run! -1 false))
  (System/gc)
  (let [observations (mapv (fn [i]
                             (let [r (run! i true)]
                               (println :allocation-boundaries i :no-gc (:allocation-valid? r))
                               (flush) r)) (range 16))]
    (assert (some :allocation-valid? observations))
    (spit (first *command-line-args*)
          (pr-str {:scope :instrumented-nested-allocation-not-timing-or-durable
                   :runtime "7098a8d6" :exporter "4d48871" :json "96f3e47"
                   :rows 64 :rounds 16 :all-output-reference-controls true
                   :gross-nested-boundaries-not-additive true
                   :gc-crossing-readings-rejected true :observations observations}))))
