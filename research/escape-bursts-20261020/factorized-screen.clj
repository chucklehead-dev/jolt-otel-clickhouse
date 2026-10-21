;; Cost ceilings, not production caching: normalization / nested encoding /
;; row projection / outer encoding with identical complete final output.
(require '[otel.exporter.chdb :as exporter]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jolt.codec.experimental :as codec] '[jolt.traversal.experimental :as traversal]
         '[jolt.scheme :as scheme] '[clojure.string :as str])
(def support "/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019/")
(load-file (str support "research/library-nested-text-20261019/nested-sorted-sdk-fixture-20261018.clj"))
(load-file (str support "research/library-nested-text-20261019/trace-affinity-scaling-helpers-20261019.clj"))
(defn backend [source keyed?]
  (let [walk (traversal/open-traversal)
        factory (scheme/eval-string
                  (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?) (let () " source
                       " (jolt-vector ffc-json-ascii-bytes ffc-json-project-prefixed-ascii-lines-bytes"
                       " ffc-json-nested-text ffc-json-cached-project-prefixed-ascii-lines-bytes"
                       " ffc-json-row-box ffc-json-row-append ffc-json-row-width ffc-json-nested-scope "
                       (if keyed? "ffc-json-key-project-prefixed-ascii-lines-bytes ffc-json-key-cached-project-prefixed-ascii-lines-bytes" "#f #f") ")))") )
        [scalar project nested cached box append width scope key-project key-cached]
        (apply factory (traversal/native-kernels walk))]
    [scalar project (traversal/native-pmap-folder walk) nested cached box append width scope key-project key-cached]))

(let [original (backend (slurp "research/escape-bursts-20261020/baseline-json.ss") false)
      c (with-redefs-fn {#'codec/backend (delay original)}
          #(#'exporter/load-experimental-span-codec! true true true true))
      raw (otel.research.trace-affinity/group-traces
            (mapv (fn [span]
                    (update (assoc-in span [:span-context :trace-flags] 1) :attributes into
                      (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value" 1 false i)]) (range 12))))
                  (with-redefs [fixture/batch-size 5000] (fixture/spans "typed-compact-prefix"))))
      rows (mapv #(-> % (update-in [:resource :attributes] (fn [m] (into (sorted-map) m)))
                        (update-in [:scope :attributes] (fn [m] (into (sorted-map) m)))
                        (update :events (fn [events] (mapv (fn [event] (update event :attributes (fn [m] (into (sorted-map) m)))) events))))
                 (nested-sorted-sdk-shape raw))

      table-maker (scheme/eval-string "(lambda () (let ((h (make-eq-hashtable))) (lambda (op k v) (if (= op 0) (hashtable-ref h k #f) (hashtable-set! h k v)))))")
      attrs-table (table-maker) nested-table (table-maker)
      preconvert ((:payload-cache-factory c))
      [prepare finish] ((nth original 8))
      _ (try
          (doseq [span rows]
            (doseq [m [(get-in span [:resource :attributes]) (:attributes span)
                       (get-in span [:events 0 :attributes])]]
              (let [converted (preconvert m)] (assert converted) (attrs-table 1 m converted)))
            (doseq [value [(:events span) (:links span)]]
              (nested-table 1 value (prepare value 67108864 128))))
          (finally (finish)))
      frozen-attrs (fn [m] (or (attrs-table 0 m nil) (throw (ex-info "Missing fixture attrs" {}))))
      frozen-nested (fn [value] (or (nested-table 0 value nil) (throw (ex-info "Missing fixture nested value" {}))))
      projected (mapv #(#'exporter/span-row % nil true frozen-nested frozen-attrs) rows)
      equal-bytes? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      encode (fn [arm]
               (let [attrs (if (contains? #{:attrs :both :projected} arm) frozen-attrs
                             (#'exporter/payload-attrs-converter ((:payload-cache-factory c))))
                     nested (if (contains? #{:nested :both :projected} arm) frozen-nested (:nested c))]
                 ((:encode c) "SQL\\n" (if (= arm :projected) projected rows)
                   (if (= arm :projected) identity
                     (fn [span] (#'exporter/span-row span nil true nested attrs))) 67108864)))
      expected (encode :normal)
      samples (atom [])]
  (doseq [arm [:attrs :nested :both :projected]] (assert (equal-bytes? expected (encode arm))))
  (dotimes [round 8]
    (doseq [arm (if (even? round) [:normal :attrs :nested :both :projected] [:projected :both :nested :attrs :normal])]
      (let [before (#'bench/counter-sample) start (System/nanoTime)
            out (encode arm) elapsed (- (System/nanoTime) start)
            after (#'bench/counter-sample)]
        (assert (equal-bytes? expected out))
        (swap! samples conj {:round round :arm arm :nanos elapsed
                             :counters (#'bench/counter-delta before after)}))))
  (spit (first *command-line-args*)
    (pr-str {:scope :rich-factorized-cost-ceilings :samples @samples :rows 5000
             :all-output-bytes-identical true :unbounded-fixture-retention true
             :not-a-production-cache-proposal true :typed-projection-excluded true
             :durable-throughput-qualified false :tail-qualified false}))
  (doseq [[arm xs] (group-by :arm @samples)]
    (let [v (vec (sort (map :nanos xs)))
          q (fn [p] (/ (nth v (dec (long (Math/ceil (* p (count v)))))) 1e6))]
      (prn {:arm arm :p50-p90-p95-p99-ms (mapv q [0.5 0.9 0.95 0.99])
            :estimated-bytes-per-row (/ (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) xs))
                                        (* 5000.0 (count xs)))}))))
