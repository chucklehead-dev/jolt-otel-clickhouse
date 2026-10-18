(require '[jolt.scheme :as scheme]
         '[clojure.data.json.jolt-native :as json-native]
         '[otel.any-value :as any]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.native-attributes :as attrs]
         '[otel.exporter.chdb.attribute-projection :as p]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench])

(def scalar-value
  ((scheme/eval-string (slurp "/home/chuck/ai-src/evidence/scalar-text-kernel-20261018.ss"))
   #'e/value-string @#'e/original-attribute-value-string
   #'any/default-string-value-max-chars any/default-string-value-max-chars))
(def baseline-transform @#'e/small-attribute-transform)
(def candidate-transform
  (delay (attrs/load-transform! #'e/key-string #'scalar-value @#'e/original-attribute-key-string)))

(defn summary [samples n]
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
      base-row @#'e/span-row]
  ;; Exact scalar parity, full fallback on changed exporter/SDK helpers, and
  ;; structured/boundary cases. These are controls, not full API qualification.
  (doseq [v [nil false true "" "λ\n\"" -1 0 42 -9223372036854775808
             9223372036854775807 1.25 {:x [1 false]} :kw ["a" 3]]]
    (assert (= (@#'e/value-string v) (scalar-value v))))
  (let [seen (atom [])]
    (with-redefs [e/value-string (fn [v] (swap! seen conj v) "changed")]
      (assert (= "changed" (scalar-value 42)))
      (assert (= [42] @seen))))
  (with-redefs [any/try-scalar-string (fn [_] "sdk-change")]
    (assert (= "sdk-change" (scalar-value 42))))
  (with-redefs [any/default-string-value-max-chars (fn [] (throw (ex-info "must-not-call" {})))]
    (assert (= "42" (scalar-value 42))))
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
    (let [operations (fn [arm phase]
                       (with-redefs-fn {#'e/small-attribute-transform
                                        (if (= arm :baseline) baseline-transform candidate-transform)}
                         #(case phase
                            :base (mapv (fn [s] (base-row s nil true)) spans)
                            :full (json-native/write-prefixed-batch-bytes! "PREFIX "
                                    (map (fn [s] (into (base-row s nil true) (projector s))) spans)
                                    8388608))))
          expected-rows (operations :baseline :base)
          expected-bytes (operations :baseline :full)
          parity! (fn []
                    (assert (= expected-rows (operations :candidate :base)))
                    (assert (= (String. expected-bytes "UTF-8")
                               (String. (operations :candidate :full) "UTF-8"))))
          sample @#'bench/counter-sample delta @#'bench/counter-delta]
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
            report {:scope :scalar-text-component-not-persistence :rows n
                    :tail-qualified false :full-row-and-utf8-parity true
                    :changed-helper-fallback-controls true
                    :schema-confirmation-bypassed-for-component true
                    :arbitrary-core-redefinition-qualified false
                    :samples samples
                    :summary (into {} (for [[label xs] (group-by (juxt :phase :arm) samples)]
                                       [label (summary xs n)]))}]
        (parity!)
        (spit (first *command-line-args*) (pr-str report))
        (prn (:summary report))))))
