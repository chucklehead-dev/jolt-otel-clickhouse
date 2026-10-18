(require '[clojure.string :as str] '[jolt.scheme :as scheme]
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

;; Diagnostic only: actual SDK-created builtin TreeMaps. Does not qualify
;; arbitrary/custom htable operations, private mutation, or a production ABI.
(def source @#'native/byte-source)
(def predicate "((and (pmap? x) (eq? impl (vector-ref stock-slots 7)))")
(def fold "(pmap-fold-seq-order x emit-map-entry! #f)")
(assert (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote predicate)) source))))
(assert (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote fold)) source))))
(def candidate-source
  (str "(define (djn-research-sorted-map? x) (or (pmap? x) (htable-sorted-map? x)))\n"
       "(define (djn-research-sorted-fold x f initial)\n"
       " (if (pmap? x) (pmap-fold-seq-order x f initial)\n"
       "  (sc-tree-fold (jolt-ref-get x kw-tree)\n"
       "   (lambda (node acc) (f (sc-nd-key node) (sc-nd-val node) acc)) initial)))\n"
       (-> source
           (str/replace predicate "((and (djn-research-sorted-map? x) (eq? impl (vector-ref stock-slots 7)))")
           (str/replace fold "(djn-research-sorted-fold x emit-map-entry! #f)"))))

(def selected-source
  (if-let [writer-path (second *command-line-args*)]
    (str (slurp "/home/chuck/ai-src/worktrees/data-json-qualified-8d557cc-20261017/src/main/clojure/clojure/data/json/jolt_byte_batch.ss")
         "\n" (slurp writer-path))
    candidate-source))

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
      original @#'native/byte-factory
      candidate (delay (scheme/eval-string selected-source))
      factories {:baseline original :direct-sorted candidate}
      sample @#'bench/counter-sample delta @#'bench/counter-delta
      row @#'e/span-row
      arms [:baseline :direct-sorted]]
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
    (let [operation (fn [arm]
                      (with-redefs-fn {#'native/byte-factory (get factories arm)}
                        #(native/write-prefixed-batch-bytes!
                           "PREFIX " (map (fn [span] (row span nil true)) spans) 67108864)))
          expected (operation :baseline)
          parity! #(assert (= (String. expected "UTF-8")
                              (String. (operation :direct-sorted) "UTF-8")))]
      (parity!)
      (dotimes [_ 2] (doseq [arm arms] (operation arm)))
      (let [samples (vec (for [round (range 16)
                              arm (if (even? round) arms (reverse arms))]
                          (let [before (sample) start (System/nanoTime)
                                result (operation arm) nanos (- (System/nanoTime) start)
                                after (sample)]
                            (assert (= (alength expected) (alength result)))
                            {:round round :arm arm :nanos nanos :counters (delta before after)})))
            report {:scope (if (second *command-line-args*) :public-seq-sorted-event-byte-walker
                               :original-sdk-sorted-event-byte-walker-diagnostic)
                    :rows n :rounds 16 :tail-qualified false :persistence-tested false
                    :custom-host-table-contract-qualified false
                    :unchanged-sdk-records true :full-byte-parity true
                    :encoded-bytes (alength expected) :samples samples
                    :summary (into {} (for [[arm xs] (group-by :arm samples)]
                                        [arm (summary xs n)]))}]
        (parity!)
        (spit (first *command-line-args*) (pr-str report))
        (prn (select-keys report [:rows :encoded-bytes :summary]))))))
