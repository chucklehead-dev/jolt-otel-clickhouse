(require '[clojure.java.io :as io]
         '[clojure.data.json.jolt-native :as json-native]
         '[otel.any-value :as any]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as p]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench])

(defn summarize [samples n]
  (let [times (vec (sort (map :nanos samples))) m (count samples)]
    {:samples m
     :ms (into {} (for [[q v] [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]]
                    [q (/ (double (nth times (dec (long (Math/ceil (* v m)))))) 1e6)]))
     :managed-B-per-row
     (/ (double (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) samples)))
        (* n m))}))

(let [n 5000
      spans (mapv (fn [span]
                    (update span :attributes into
                            (map (fn [i] [(str "extra-" i)
                                          (case (mod i 3) 0 "value" 1 false i)]))
                            (range 12)))
                  (with-redefs [fixture/batch-size n] (fixture/spans "typed-compact-prefix")))
      fields (:fields (fixture/approved))
      projector (@#'p/indexed-vector-projector
                  [:span-attributes] [(set (map :key fields))]
                  (mapv (fn [{:keys [key type]}] [0 key type]) fields))
      base-row @#'e/span-row
      sdk-var #'any/try-scalar-string-map
      available (delay sdk-var) absent (delay nil)
      sample @#'bench/counter-sample delta @#'bench/counter-delta]
  (assert (some? (any/try-scalar-string-map (:attributes (first spans)))))
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
    (let [operations (fn [arm phase]
                       (with-redefs-fn {#'e/sdk-scalar-map-converter (if (= arm :baseline) absent available)}
                         #(case phase
                            :base (mapv (fn [s] (base-row s nil true)) spans)
                            :full (json-native/write-prefixed-batch-bytes! "PREFIX "
                                    (map (fn [s] (into (base-row s nil true) (projector s))) spans)
                                    8388608))))
          expected-rows (operations :baseline :base)
          expected-bytes (operations :baseline :full)
          calls (atom 0) original @sdk-var
          _ (with-redefs-fn {sdk-var (fn [m] (swap! calls inc) (original m))}
              #(assert (= expected-rows (operations :candidate :base))))
          _ (assert (= n @calls))
          parity! (fn []
                    (assert (= expected-rows (operations :candidate :base)))
                    (assert (= (String. expected-bytes "UTF-8")
                               (String. (operations :candidate :full) "UTF-8"))))]
      (parity!)
      (dotimes [_ 3] (doseq [phase [:base :full] arm [:baseline :candidate]] (operations arm phase)))
      (let [samples (vec (for [round (range 24)
                              phase (if (even? round) [:base :full] [:full :base])
                              arm (if (even? round) [:baseline :candidate] [:candidate :baseline])]
                          (let [before (sample) start (System/nanoTime)
                                result (operations arm phase) elapsed (- (System/nanoTime) start)
                                after (sample)]
                            (if (= phase :base) (assert (= n (count result)))
                              (assert (= (alength expected-bytes) (alength result))))
                            {:round round :phase phase :arm arm :nanos elapsed
                             :counters (delta before after)})))
            report {:scope :sdk-owned-scalar-map-component-not-persistence :rows n
                    :tail-qualified false :full-row-and-utf8-parity true
                    :observed-sdk-map-calls @calls
                    :baseline :same-production-code-with-optional-sdk-helper-disabled
                    :schema-confirmation-bypassed-for-component true
                    :sdk-source (str (io/resource "otel/any_value.clj"))
                    :exporter-source (str (io/resource "otel/exporter/chdb.clj"))
                    :samples samples
                    :summary (into {} (for [[label xs] (group-by (juxt :phase :arm) samples)]
                                       [label (summarize xs n)]))}]
        (parity!)
        (spit (first *command-line-args*) (pr-str report))
        (prn (:summary report))))))
