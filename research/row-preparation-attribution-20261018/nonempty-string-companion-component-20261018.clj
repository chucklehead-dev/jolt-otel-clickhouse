(require '[jolt.scheme :as scheme] '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[otel.sdk.tracer :as sdk] '[otel.sdk.export :as export]
         '[otel.exporter.memory :as memory] '[otel.resource :as resource]
         '[otel.trace :as trace] '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb-benchmark :as bench])

(defn summary [xs n]
  (let [times (vec (sort (map :nanos xs))) m (count xs)]
    {:samples m
     :ms (into {} (for [[label q] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                    [label (/ (double (nth times (dec (long (Math/ceil (* q m)))))) 1e6)]))
     :managed-B-per-row
     (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) xs))) (* n m))}))

(let [n 2000
      input (into {"flag" false "count" 42 "service.name" "producer-shape"}
                  (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value-λ" 1 false i)]) (range 14)))
      sink (memory/exporter)
      provider (sdk/tracer-provider {:resource (resource/resource input)
                                     :processors [(export/simple-processor sink)]})
      tracer (sdk/get-tracer provider {:name "nested-bridge" :attributes input})
      _ (try (dotimes [i n]
               (trace/with-span [sp tracer (str "scenario-" (mod i 8)) {:attributes input}]
                 (trace/add-event! sp "scenario-event" input)))
             (finally (assert (sdk/shutdown! provider))))
      spans (memory/spans sink) row @#'e/span-row
      sample @#'bench/counter-sample delta @#'bench/counter-delta
      original-factory @#'native/string-output-factory
      candidate-factory (delay (scheme/eval-string
                                (slurp "/home/chuck/ai-src/worktrees/data-json-nonempty-string-companion-20261018/src/main/clojure/clojure/data/json/jolt_string_writer.ss")))
      choices {:baseline original-factory :direct-hook candidate-factory}
      producer (fn [f]
                 (let [write (native/load-payload-byte-buffer-writer!)
                       output ((force @#'native/string-output-factory)
                               @#'json/native-write-str-original #'json/write-str
                               #'json/*experimental-native-writer* #'json/default-write-options
                               @#'json/native-writer-stock write (write))]
                   (binding [json/*experimental-native-writer* write native/*batch-string-output-writer* output]
                     (f))))
      phases [:nested-event-json :full] arms [:baseline :direct-hook]]
  (try
    (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
      (let [operation (fn [arm phase]
                        (with-redefs-fn {#'native/string-output-factory (get choices arm)}
                          (fn [] (case phase
                          :nested-event-json
                          (producer #(mapv (fn [span] (@#'e/nested-json-string (:events span))) spans))
                          :full (native/write-prefixed-batch-bytes!
                                  "PREFIX " (map #(row % nil true) spans) 67108864)))))
            expected (into {} (map (fn [phase] [phase (operation :baseline phase)]) phases))
            text (fn [phase result] (if (= phase :full) (String. result "UTF-8") result))
            parity! (fn [] (doseq [phase phases]
                            (assert (= (text phase (get expected phase))
                                       (text phase (operation :direct-hook phase))))))]
        (parity!)
        (dotimes [_ 2] (doseq [phase phases arm arms] (operation arm phase)))
        (let [samples (vec (for [round (range 16)
                                phase (if (even? round) phases (reverse phases))
                                arm (if (even? round) arms (reverse arms))]
                            (let [before (sample) start (System/nanoTime)
                                  result (operation arm phase) nanos (- (System/nanoTime) start)
                                  after (sample)]
                              (assert (= (if (= phase :full) (alength (get expected phase)) n)
                                         (if (= phase :full) (alength result) (count result))))
                              {:round round :phase phase :arm arm :nanos nanos :counters (delta before after)})))
              report {:scope :native-nested-string-companion-direct-hook
                      :rows n :rounds 16 :tail-qualified false :persistence-tested false
                      :callback-contract-qualified false
                      :full-byte-parity-for-default-fixture true :samples samples
                      :summary (into {} (for [[label xs] (group-by (juxt :phase :arm) samples)]
                                          [label (summary xs n)]))}]
          (parity!)
          (spit (first *command-line-args*) (pr-str report))
          (prn (:summary report)))))
    (finally nil)))

