;; Native key-only cache screen: baseline JSON source, modified/off, weak
;; identity keys, and bounded content-equal keys. No compiler/global installs.
(require '[otel.exporter.chdb :as exporter]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jolt.codec.experimental :as codec]
         '[jolt.traversal.experimental :as traversal]
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

(let [baseline (backend (slurp "research/key-fragments-20261020/baseline-json.ss") false)
      needle "(unless key-table (set! key-table (make-weak-eq-hashtable)))"
      content-source (str/replace @#'codec/json-source needle
                       "(unless key-table (set! key-table (make-hashtable string-hash string=?)))")
      _ (assert (not= content-source @#'codec/json-source))
      content (backend content-source true)
      open codec/open-codec
      make-c (fn [backend enabled]
               (with-redefs-fn {#'codec/backend (delay backend)
                               #'codec/open-codec (fn [options] (open (assoc options :key-fragment-cache? enabled)))}
                 #(#'exporter/load-experimental-span-codec! true true true true)))
      c-original (make-c baseline false)
      c-off (#'exporter/load-experimental-span-codec! true true true true)
      c-eq (with-redefs [codec/open-codec (fn [options] (open (assoc options :key-fragment-cache? true)))]
             (#'exporter/load-experimental-span-codec! true true true true))
      c-content (make-c content true)
      choices {:original c-original :off c-off :identity c-eq :content c-content}
      raw (otel.research.trace-affinity/group-traces
            (mapv (fn [span]
                    (update (assoc-in span [:span-context :trace-flags] 1) :attributes into
                      (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value" 1 false i)]) (range 12))))
                  (with-redefs [fixture/batch-size 5000] (fixture/spans "typed-compact-prefix"))))
      rows (mapv #(-> % (update-in [:resource :attributes] (fn [m] (into (sorted-map) m)))
                        (update-in [:scope :attributes] (fn [m] (into (sorted-map) m)))
                        (update :events (fn [events] (mapv (fn [event] (update event :attributes (fn [m] (into (sorted-map) m)))) events))))
                 (nested-sorted-sdk-shape raw))
      equal-bytes? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      encode (fn [arm]
               (let [c (choices arm) convert (#'exporter/payload-attrs-converter ((:payload-cache-factory c)))]
                 ((:encode c) "SQL\n" rows
                   (fn [span] (#'exporter/span-row span nil true (:nested c) convert)) 67108864)))
      samples (atom []) expected (encode :original)]
  (doseq [arm (keys choices)] (assert (equal-bytes? expected (encode arm))))
  (dotimes [round 8]
    (doseq [arm (if (even? round) [:original :off :identity :content] [:content :identity :off :original])]
      (let [before (#'bench/counter-sample) start (System/nanoTime)
            bytes (encode arm) elapsed (- (System/nanoTime) start)
            after (#'bench/counter-sample)]
        (assert (equal-bytes? expected bytes))
        (swap! samples conj {:round round :arm arm :nanos elapsed
                             :counters (#'bench/counter-delta before after)}))))
  (spit (first *command-line-args*)
    (pr-str {:scope :rich-key-fragments-component :samples @samples :rows 5000
             :all-output-bytes-identical true :typed-projection-excluded true
             :durable-throughput-qualified false :tail-qualified false}))
  (doseq [[arm xs] (group-by :arm @samples)]
    (let [v (vec (sort (map :nanos xs)))
          q (fn [p] (/ (nth v (dec (long (Math/ceil (* p (count v)))))) 1e6))]
      (prn {:arm arm :p50-p90-p95-p99-ms (mapv q [0.5 0.9 0.95 0.99])
            :estimated-bytes-per-row (/ (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) xs))
                                        (* 5000.0 (count xs)))}))))
