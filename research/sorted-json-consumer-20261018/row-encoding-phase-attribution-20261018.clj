(require '[clojure.data.json :as json] '[clojure.data.json.jolt-native :as native]
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
      tracer (sdk/get-tracer provider {:name "phase-attribution" :attributes input})
      _ (try (dotimes [i n]
               (trace/with-span [sp tracer (str "scenario-" (mod i 8)) {:attributes input}]
                 (trace/add-event! sp "scenario-event" input)))
             (finally (assert (sdk/shutdown! provider))))
      spans (memory/spans sink) row @#'e/span-row
      sample @#'bench/counter-sample delta @#'bench/counter-delta
      prepare (fn []
                ;; Match the collector's producer-side hook and empty-link
                ;; companion, not an unbound/generic nested-JSON benchmark.
                (let [write (native/load-payload-byte-buffer-writer!)
                      output ((force @#'native/string-output-factory)
                               @#'json/native-write-str-original #'json/write-str
                               #'json/*experimental-native-writer* #'json/default-write-options
                               @#'json/native-writer-stock write (write))]
                  (binding [json/*experimental-native-writer* write
                            native/*batch-string-output-writer* output]
                    (mapv #(row % nil true) spans))))]
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
    (let [prepared (prepare)
          operation (fn [phase]
                      (case phase
                        :prepare-rows (prepare)
                        :encode-prepared (native/write-prefixed-batch-bytes! "PREFIX " prepared 67108864)
                        :full (native/write-prefixed-batch-bytes!
                                "PREFIX " (map #(row % nil true) spans) 67108864)))
          expected (operation :full)
          parity! (fn []
                    (assert (= prepared (prepare)))
                    (assert (= (String. expected "UTF-8")
                               (String. (operation :encode-prepared) "UTF-8"))))
          phases [:prepare-rows :encode-prepared :full]]
      (parity!)
      (dotimes [_ 2] (doseq [phase phases] (operation phase)))
      (let [samples (vec (for [round (range 16)
                              phase (let [offset (mod round 3)]
                                      (concat (drop offset phases) (take offset phases)))]
                          (let [before (sample) start (System/nanoTime)
                                result (operation phase) nanos (- (System/nanoTime) start)
                                after (sample)]
                            (assert (= (if (= phase :prepare-rows) n (alength expected))
                                       (if (= phase :prepare-rows) (count result) (alength result))))
                            {:round round :phase phase :nanos nanos :counters (delta before after)})))
            report {:scope :actual-sdk-projection-versus-outer-byte-encoding
                    :rows n :rounds 16 :tail-qualified false :persistence-tested false
                    :prebuilt-rows-retained-in-all-phases true :phase-times-not-additive true
                    :producer-hook-and-empty-link-companion-preserved true
                    :full-byte-parity true :encoded-bytes (alength expected)
                    :samples samples
                    :summary (into {} (for [[phase xs] (group-by :phase samples)]
                                        [phase (summary xs n)]))}]
        (parity!)
        (spit (first *command-line-args*) (pr-str report))
        (prn (select-keys report [:rows :encoded-bytes :summary]))))))
