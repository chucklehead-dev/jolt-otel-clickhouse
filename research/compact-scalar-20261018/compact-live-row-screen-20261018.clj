(require '[jolt.scheme :as scheme]
         '[clojure.data.json.jolt-native :as json-native]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as p]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench])

(def helper-vars [#'e/timestamp #'e/trace-state-string #'e/otel-enum-string
                  #'e/service-name #'e/attrs #'e/nested-json-string
                  #'e/event-columns #'e/link-columns])
(def candidate
  ((scheme/eval-string (slurp "/home/chuck/ai-src/evidence/compact-live-row-native-20261018.ss"))
   [:span-context :scope :resource :events :links :start-time-unix-nano
    :trace-id :span-id :parent-span-id :trace-state :name :kind :internal
    :attributes :version :status :code :unset :description]
   helper-vars
   (fn [span] (max 0 (- (:end-time-unix-nano span) (:start-time-unix-nano span))))))
(def base-row @#'e/span-row)
(def baseline (fn [span] (base-row span nil true)))

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
      controls [(first spans)
                (assoc (first spans) :events [{:timestamp-unix-nano 42 :name "event" :attributes {"x" 1}}]
                       :links [{:span-context {:trace-id "other" :span-id "child" :trace-state [["a" "b"]]}
                                :attributes {"ok" false}}])
                (assoc (first spans) :scope nil :resource nil :span-context nil
                       :events nil :links nil :status nil :parent-span-id false)
                (assoc (first spans) :start-time-unix-nano 7 :end-time-unix-nano 3
                       :status {:code :error :description "unicode-λ\n\""})]
      fields (:fields (fixture/approved))
      projector (@#'p/indexed-vector-projector
                  [:span-attributes] [(set (map :key fields))]
                  (mapv (fn [{:keys [key type]}] [0 key type]) fields))]
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
    (doseq [span controls] (assert (= (baseline span) (candidate span))))
    ;; Observe every live helper call, including a later root replacement.
    ;; Both arms retain real helper bodies; compare call order and full output.
    (let [observe (fn [row]
                    (let [calls (atom [])
                          replacements (into {} (map-indexed
                            (fn [i v] [v (let [body @v]
                                          (fn [& args]
                                            (swap! calls conj [i args])
                                            (apply body args)))]) helper-vars))
                          value (with-redefs-fn replacements #(row (second controls)))]
                      [value @calls]))]
      (assert (= (observe baseline) (observe candidate))))
    (let [expected-rows (mapv baseline spans)
          encode #(json-native/write-prefixed-batch-bytes! "PREFIX " % 8388608)
          expected-bytes (encode (mapv #(into %1 (projector %2)) expected-rows spans))
          operations (fn [arm phase]
                       (let [row (if (= arm :baseline) baseline candidate)]
                         (case phase
                           :base (mapv row spans)
                           :full (encode (map (fn [span] (into (row span) (projector span))) spans)))))
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
            report {:scope :native-base-row-component-not-persistence :rows n
                    :tail-qualified false :full-row-and-utf8-parity true
                    :live-helper-call-order-controls true
                    :schema-confirmation-bypassed-for-component true
                    :arbitrary-core-redefinition-qualified false
                    :samples samples
                    :summary (into {} (for [[label xs] (group-by (juxt :phase :arm) samples)]
                                       [label (summary xs n)]))}]
        (parity!)
        (spit (first *command-line-args*) (pr-str report))
        (prn (:summary report))))))
