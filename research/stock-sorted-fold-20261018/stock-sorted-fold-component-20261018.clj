(require '[clojure.data.json :as json] '[clojure.data.json.jolt-native :as native]
         '[clojure.string :as str] '[jolt.scheme :as scheme]
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

;; Diagnostic helper is loaded into this disposable process only. No compiler
;; source, default executable, library source, or installed backend changes.
(let [mode (or (second *command-line-args*) "bounded")
      _ (assert (contains? #{"bounded" "runtime"} mode))
      baseline-source (slurp "/home/chuck/ai-src/worktrees/data-json-sorted-map-byte-seq-20261018/src/main/clojure/clojure/data/json/jolt_byte_writer.ss")
      old "(emit-seq-map! x))"
      replacement "(let ((tree (task-stock-sorted-scalar-tree x)))
                       (if tree
                         (begin (byte! 123)
                           (task-stock-sorted-fold tree emit-map-entry! #f)
                           (byte! 125))
                         (emit-seq-map! x))))"
      _ (assert (= 2 (count (str/split baseline-source (re-pattern (java.util.regex.Pattern/quote old))))))
      candidate-source (if (= mode "runtime")
                         (slurp "/home/chuck/ai-src/worktrees/data-json-optional-stock-sorted-fold-20261018/src/main/clojure/clojure/data/json/jolt_byte_writer.ss")
                         (str/replace baseline-source old replacement))
      _ (native/load-payload-byte-buffer-writer!)
      _ (if (= mode "runtime")
          (let [source (slurp "/home/chuck/ai-src/worktrees/jolt-0823-stock-sorted-fold-20261018/host/chez/host-table.ss")
                start (.indexOf source ";; Internal native-consumer helpers,")
                end (.indexOf source ";; --- extend the collection dispatchers")]
            (assert (and (<= 0 start) (< start end)))
            (scheme/eval-string (subs source start end)))
          (scheme/eval-string (slurp "/home/chuck/ai-src/evidence/stock-sorted-fold-probe-20261018.ss")))
      ;; Refuse an unbound/private anchor: this probe is not allowed to turn
      ;; into a universal-tag fast path through a misspelled sm-ops Var.
      _ (assert (scheme/eval-string (if (= mode "runtime")
                                     "(pmap? (var-cell-root sc-stock-map-ops-cell))"
                                     "(pmap? task-stock-sorted-ops)")))
      choices {:baseline (delay (scheme/eval-string baseline-source))
               :candidate (delay (scheme/eval-string candidate-source))}
      _ (doseq [factory (vals choices)] @factory)
      n 2000
      input (into {"flag" false "count" 42 "service.name" "producer-shape"}
                  (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value-λ" 1 false i)]) (range 14)))
      sink (memory/exporter)
      provider (sdk/tracer-provider {:resource (resource/resource input)
                                     :processors [(export/simple-processor sink)]})
      tracer (sdk/get-tracer provider {:name "stock-sorted-fold-screen" :attributes input})
      _ (try (dotimes [i n]
               (trace/with-span [sp tracer (str "scenario-" (mod i 8)) {:attributes input}]
                 (trace/add-event! sp "scenario-event" input)))
             (finally (assert (sdk/shutdown! provider))))
      spans (memory/spans sink) row @#'e/span-row
      admitted? (scheme/proc (if (= mode "runtime") "stock-sorted-map?" "task-stock-sorted-scalar-tree"))
      _ (assert (admitted? (get-in (first spans) [:events 0 :attributes])))
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
            report {:scope :stock-sorted-fold-diagnostic-not-product :mode mode
                    :rows n :rounds 24 :tail-qualified false :persistence-tested false
                    :callback-contract-qualified false :geometry-qualified false
                    :sdk-sorted-scalar-enabled-both-arms true :full-byte-parity true
                    :samples samples
                    :summary (into {} (for [[arm xs] (group-by :arm samples)] [arm (summary xs n)]))}]
        (parity!)
        (spit (first *command-line-args*) (pr-str report))
        (prn (select-keys report [:rows :summary]))))))
