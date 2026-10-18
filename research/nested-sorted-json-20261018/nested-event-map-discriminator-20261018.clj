(require '[clojure.data.json :as json]
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
      tracer (sdk/get-tracer provider {:name "nested-map-shape" :attributes input})
      _ (try (dotimes [i n]
               (trace/with-span [sp tracer (str "scenario-" (mod i 8)) {:attributes input}]
                 (trace/add-event! sp "scenario-event" input)))
             (finally (assert (sdk/shutdown! provider))))
      spans (memory/spans sink)
      ;; Representation is changed only in this discriminator, outside timing.
      ;; Explicit array-map retains sorted iteration order above eight entries.
      alternate (mapv #(update % :events
                               (fn [events]
                                 (mapv (fn [event]
                                         (update event :attributes
                                                 (fn [m] (apply array-map (mapcat identity m)))))
                                       events))) spans)
      inputs {:sorted spans :ordered-array alternate}
      _ (assert (= spans alternate))
      _ (assert (sorted? (get-in (first spans) [:events 0 :attributes])))
      _ (assert (not (sorted? (get-in (first alternate) [:events 0 :attributes]))))
      _ (assert (= (seq (get-in (first spans) [:events 0 :attributes]))
                    (seq (get-in (first alternate) [:events 0 :attributes]))))
      sample @#'bench/counter-sample delta @#'bench/counter-delta
      row @#'e/span-row
      phases [:nested-public :nested-guarded :full]
      arms [:sorted :ordered-array]]
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
    (let [operation (fn [arm phase]
                      (let [records (get inputs arm)]
                        (case phase
                          :nested-public (mapv #(json/write-str (:events %)) records)
                          :nested-guarded
                          (binding [json/*experimental-native-writer* (native/load-writer!)]
                            (mapv #(json/write-str (:events %)) records))
                          :full (native/write-prefixed-batch-bytes!
                                  "PREFIX " (map #(row % nil true) records) 67108864))))
          expected (into {} (map (fn [phase] [phase (operation :sorted phase)]) phases))
          text (fn [phase result] (if (= phase :full) (String. result "UTF-8") result))
          parity! (fn []
                    (doseq [phase phases]
                      (assert (= (text phase (get expected phase))
                                 (text phase (operation :ordered-array phase)))))
                    (assert (= (get expected :nested-public) (get expected :nested-guarded))))]
      (parity!)
      (dotimes [_ 2] (doseq [phase phases arm arms] (operation arm phase)))
      (let [samples (vec (for [round (range 12)
                              phase (if (even? round) phases (reverse phases))
                              arm (if (even? round) arms (reverse arms))]
                          (let [before (sample) start (System/nanoTime)
                                result (operation arm phase) nanos (- (System/nanoTime) start)
                                after (sample)]
                            (assert (= (if (= phase :full) (alength (get expected phase)) n)
                                       (if (= phase :full) (alength result) (count result))))
                            {:round round :phase phase :arm arm :nanos nanos
                             :counters (delta before after)})))
            report {:scope :same-byte-nested-event-map-representation-discriminator
                    :rows n :rounds 12 :tail-qualified false :persistence-tested false
                    :equal-records-and-event-iteration-order true :full-byte-parity true
                    :encoded-bytes (alength (get expected :full))
                    :shape (into {} (for [arm arms]
                                      [arm (str (type (get-in (first (get inputs arm))
                                                             [:events 0 :attributes])))]))
                    :samples samples
                    :summary (into {} (for [[label xs] (group-by (juxt :phase :arm) samples)]
                                        [label (summary xs n)]))}]
        (parity!)
        (spit (first *command-line-args*) (pr-str report))
        (prn (select-keys report [:rows :encoded-bytes :shape :summary]))))))
