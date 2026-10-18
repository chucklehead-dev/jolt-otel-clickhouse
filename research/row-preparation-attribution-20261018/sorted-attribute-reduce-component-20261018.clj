(require '[jolt.scheme :as scheme] '[clojure.data.json.jolt-native :as native]
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
      tracer (sdk/get-tracer provider {:name "reduce-comparison" :attributes input})
      _ (try (dotimes [i n]
               (trace/with-span [sp tracer (str "scenario-" (mod i 8)) {:attributes input}]
                 (trace/add-event! sp "scenario-event" input)))
             (finally (assert (sdk/shutdown! provider))))
      spans (memory/spans sink)
      [supported? native-convert]
      ((scheme/eval-string (slurp "/home/chuck/ai-src/evidence/sorted-attribute-reduce-kernel-20261018.ss"))
       #'e/key-string #'e/value-string)
      portable (fn [m]
                 (persistent!
                   (reduce (fn [out [k v]]
                             (assoc! out (@#'e/key-string k) (@#'e/value-string v)))
                           (transient {}) m)))
      original @#'e/small-attribute-transform
      choices {:baseline original
               :portable (delay (fn [m] (if (supported? m) (portable m) (@original m))))
               :native-fold (delay (fn [m] (or (native-convert m) (@original m))))}
      sample @#'bench/counter-sample delta @#'bench/counter-delta
      attrs @#'e/attrs row @#'e/span-row
      phases [:attribute-shaping :full] arms [:baseline :portable :native-fold]]
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
    (let [operation (fn [arm phase]
                      (with-redefs-fn {#'e/small-attribute-transform (get choices arm)}
                        #(case phase
                           :attribute-shaping
                           (mapv (fn [span]
                                   [(attrs (:attributes span))
                                    (attrs (get-in span [:resource :attributes]))
                                    (attrs (get-in span [:events 0 :attributes]))]) spans)
                           :full (native/write-prefixed-batch-bytes!
                                   "PREFIX " (map (fn [span] (row span nil true)) spans) 67108864))))
          expected (into {} (map (fn [phase] [phase (operation :baseline phase)]) phases))
          text (fn [phase result] (if (= phase :full) (String. result "UTF-8") result))
          parity! (fn []
                    (doseq [phase phases arm (rest arms)]
                      (assert (= (text phase (get expected phase)) (text phase (operation arm phase))))))]
      (parity!)
      (dotimes [_ 2] (doseq [phase phases arm arms] (operation arm phase)))
      (let [samples (vec (for [round (range 16)
                              phase (if (even? round) phases (reverse phases))
                              arm (let [offset (mod round 3)]
                                    (concat (drop offset arms) (take offset arms)))]
                          (let [before (sample) start (System/nanoTime)
                                result (operation arm phase) nanos (- (System/nanoTime) start)
                                after (sample)]
                            (assert (= (if (= phase :full) (alength (get expected phase)) n)
                                       (if (= phase :full) (alength result) (count result))))
                            {:round round :phase phase :arm arm :nanos nanos :counters (delta before after)})))
            report {:scope :actual-sdk-sorted-attribute-reduction-comparison
                    :rows n :rounds 16 :tail-qualified false :persistence-tested false
                    :callback-contract-qualified false :full-byte-parity true
                    :private-tree-not-read true :core-runtime-not-modified true
                    :encoded-bytes (alength (get expected :full)) :samples samples
                    :summary (into {} (for [[label xs] (group-by (juxt :phase :arm) samples)]
                                        [label (summary xs n)]))}]
        (parity!)
        (spit (first *command-line-args*) (pr-str report))
        (prn (select-keys report [:rows :encoded-bytes :summary]))))))
