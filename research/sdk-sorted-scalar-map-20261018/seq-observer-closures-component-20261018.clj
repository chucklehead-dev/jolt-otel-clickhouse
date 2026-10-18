(require '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[jolt.scheme :as scheme]
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
     (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) xs))) (* n m))
     :cpu-nanos (reduce + (map #(get-in % [:counters :calling-thread-cpu-nanos]) xs))}))

;; Both byte factories are loaded once. Only the local source-only factory
;; selection changes per arm; SDK scalar normalization remains enabled in both.
;; This is a disposable process, not a runtime/default backend installation.
(let [baseline-source (slurp "/home/chuck/ai-src/worktrees/data-json-sorted-map-byte-seq-20261018/src/main/clojure/clojure/data/json/jolt_byte_writer.ss")
      candidate-source (slurp "/home/chuck/ai-src/evidence/seq-observer-closures-negative-source-20261018.ss")
      _ (native/load-payload-byte-buffer-writer!)
      choices {:baseline (delay (scheme/eval-string baseline-source))
               :candidate (delay (scheme/eval-string candidate-source))}
      _ (doseq [factory (vals choices)] @factory)
      n 2000
      input (into {"flag" false "count" 42 "service.name" "producer-shape"}
                  (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value-λ" 1 false i)]) (range 14)))
      sink (memory/exporter)
      provider (sdk/tracer-provider {:resource (resource/resource input)
                                     :processors [(export/simple-processor sink)]})
      tracer (sdk/get-tracer provider {:name "observer-closure-screen" :attributes input})
      _ (try (dotimes [i n]
               (trace/with-span [sp tracer (str "scenario-" (mod i 8)) {:attributes input}]
                 (trace/add-event! sp "scenario-event" input)))
             (finally (assert (sdk/shutdown! provider))))
      spans (memory/spans sink) row @#'e/span-row
      sample @#'bench/counter-sample delta @#'bench/counter-delta
      operation (fn [arm]
                  (with-redefs-fn {#'native/byte-factory (get choices arm)}
                    #(native/write-prefixed-batch-bytes!
                       "PREFIX " (map (fn [span] (row span nil true)) spans) 67108864)))]
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
    (let [expected (String. (operation :baseline) "UTF-8")
          parity! (fn [] (assert (= expected (String. (operation :candidate) "UTF-8"))))]
      (parity!)
      (dotimes [_ 3] (doseq [arm [:baseline :candidate]] (operation arm)))
      (let [samples (vec (for [round (range 24)
                              arm (if (even? round) [:baseline :candidate] [:candidate :baseline])]
                          (let [before (sample) start (System/nanoTime)
                                result (operation arm) nanos (- (System/nanoTime) start)
                                after (sample)]
                            (assert (= (count expected) (alength result)))
                            {:round round :arm arm :nanos nanos :counters (delta before after)})))
            report {:scope :same-sdk-event-rich-encoding-observer-closure-removal
                    :rows n :rounds 24 :tail-qualified false :persistence-tested false
                    :sdk-sorted-scalar-enabled-both-arms true :full-byte-parity true
                    :sequence-operations-and-publication-boundaries-unchanged true
                    :samples samples
                    :summary (into {} (for [[arm xs] (group-by :arm samples)] [arm (summary xs n)]))}]
        (parity!)
        (spit (first *command-line-args*) (pr-str report))
        (prn (select-keys report [:rows :summary]))))))
