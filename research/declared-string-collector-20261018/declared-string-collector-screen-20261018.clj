(require '[clojure.data.json.jolt-native :as json-native] '[jolt.scheme :as scheme]
         '[otel.exporter.chdb :as e] '[otel.exporter.chdb.attribute-projection :as p]
         '[otel.exporter.chdb.native-projection :as native]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench])

(defn summarize [samples n]
  (let [times (vec (sort (map :nanos samples))) m (count times)]
    {:samples m
     :ms (into {} (for [[q v] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                    [q (/ (double (nth times (dec (long (Math/ceil (* v m)))))) 1e6)]))
     :managed-B-per-row (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) samples)))
                          (* n m))}))

(let [n 5000
      spans (mapv (fn [span]
                    (update span :attributes into
                            (map (fn [i] [(str "extra-" i)
                                          (case (mod i 3) 0 "value" 1 false i)]))
                            (range 12)))
                  (with-redefs [fixture/batch-size n] (fixture/spans "typed-compact-prefix")))
      key-var #'p/key-string contains-var #'clojure.core/contains?
      original @#'p/original-key-string
      old (scheme/eval-string @#'native/declared)
      baseline (fn [attrs wanted] (old attrs wanted key-var contains-var))
      candidate (native/load-declared-collector! key-var contains-var original)
      fields (:fields (fixture/approved))
      _ (assert (= #{"flag" "count"} (set (map :key fields))))
      projector (@#'p/indexed-vector-projector
                  [:span-attributes] [(set (map :key fields))]
                  (mapv (fn [{:keys [key type]}] [0 key type]) fields))
      base-row @#'e/span-row
      operations
      (fn [arm phase]
        (with-redefs [p/small-declared-collector (delay (if (= arm :baseline) baseline candidate))]
          (case phase
            :typed (mapv projector spans)
            :full (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
                    (json-native/write-prefixed-batch-bytes!
                     "PREFIX " (map (fn [span] (into (base-row span nil true) (projector span))) spans) 8388608)))))
      expected-typed (operations :baseline :typed)
      expected-bytes (operations :baseline :full)
      parity! (fn []
                (assert (= expected-typed (operations :candidate :typed)))
                (assert (= (String. expected-bytes "UTF-8")
                           (String. (operations :candidate :full) "UTF-8"))))
      sample @#'bench/counter-sample delta @#'bench/counter-delta]
  (parity!)
  (dotimes [_ 3] (doseq [phase [:typed :full] arm [:baseline :candidate]] (operations arm phase)))
  (let [samples
        (vec (for [round (range 24)
                   phase (if (even? round) [:typed :full] [:full :typed])
                   arm (if (even? round) [:baseline :candidate] [:candidate :baseline])]
               (let [before (sample) start (System/nanoTime)
                     result (operations arm phase) elapsed (- (System/nanoTime) start)
                     after (sample)]
                 (if (= phase :typed) (assert (= n (count result)))
                     (assert (= (alength expected-bytes) (alength result))))
                 {:round round :phase phase :arm arm :nanos elapsed :counters (delta before after)})))
        report {:scope :typed-projection-and-fresh-encoding-not-persistence
                :rows n :attributes 16 :tail-qualified false
                :descriptor-confirmation-bypassed-for-private-compiler-component true
                :full-typed-and-wire-parity true :samples samples
                :summary (into {} (for [[label xs] (group-by (juxt :phase :arm) samples)]
                                    [label (summarize xs n)]))}]
    (parity!)
    (spit (first *command-line-args*) (pr-str report)) (prn (:summary report))))
