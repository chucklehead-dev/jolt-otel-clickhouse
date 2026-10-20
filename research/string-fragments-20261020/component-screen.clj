;; Short interleaved component screen after the sequential Durable run.
;; No database, queue, publication, typed-column projection or recovery claim.
(require '[otel.exporter.chdb :as exporter]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[jolt.codec.experimental :as codec]
         '[jolt.scheme :as scheme])
(load-file "/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019/research/library-nested-text-20261019/nested-sorted-sdk-fixture-20261018.clj")
(let [open codec/open-codec
      off (#'exporter/load-experimental-span-codec! true true true true)
      on (with-redefs [codec/open-codec (fn [options] (open (assoc options :string-fragment-cache? true)))]
           (#'exporter/load-experimental-span-codec! true true true true))
      rows (mapv #(-> % (update-in [:resource :attributes] (fn [m] (into (sorted-map) m)))
                        (update-in [:scope :attributes] (fn [m] (into (sorted-map) m)))
                        (update :events (fn [events] (mapv (fn [event] (update event :attributes (fn [m] (into (sorted-map) m)))) events))))
                 (nested-sorted-sdk-shape (with-redefs [fixture/batch-size 5000]
                                            (fixture/spans "component"))))
      _ (assert (= 5000 (count rows)))
      equal-bytes? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      encode (fn [c]
               (let [convert (#'exporter/payload-attrs-converter ((:payload-cache-factory c)))]
                 ((:encode c) "SQL\n" rows
                   (fn [span] (#'exporter/span-row span nil true (:nested c) convert)) 67108864)))
      expected (encode off)
      samples (atom [])]
  (assert (equal-bytes? expected (encode on)))
  (dotimes [round 8]
    (doseq [arm (if (even? round) [:off :on :on :off] [:on :off :off :on])]
      (let [started (System/nanoTime) bytes (encode (if (= arm :on) on off))
            elapsed (- (System/nanoTime) started)]
        (assert (equal-bytes? expected bytes))
        (swap! samples conj {:round round :arm arm :nanos elapsed :bytes (alength bytes)}))))
  (let [report {:scope :rich-untyped-project-and-encode-component
                :rows 5000 :samples @samples :identical-bytes true
                :durable-throughput-qualified false}]
    (spit (first *command-line-args*) (pr-str report))
    (doseq [[arm xs] (group-by :arm @samples)]
      (let [values (vec (sort (map :nanos xs)))
            q (fn [p] (/ (nth values (dec (long (Math/ceil (* p (count values)))))) 1e6))]
        (prn {:arm arm :samples (count xs) :p50-p90-p95-p99-ms (mapv q [0.5 0.9 0.95 0.99])})))))
