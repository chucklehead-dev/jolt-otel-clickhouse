;; Research-only cost ceiling, NOT a proposed cache or production fast path.
;; Frozen normalized outputs deliberately move admission/conversion out of the
;; timed region. They retain all fixture values, beyond production cache bounds.
(require '[otel.exporter.chdb :as exporter]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[otel.any-value :as any]
         '[jolt.scheme :as scheme]
         '[clojure.java.io :as io])
(def support "/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019/")
(load-file (str support "research/library-nested-text-20261019/nested-sorted-sdk-fixture-20261018.clj"))
(load-file (str support "research/library-nested-text-20261019/trace-affinity-scaling-helpers-20261019.clj"))
(let [c (#'exporter/load-experimental-span-codec! true true true true)
      shape-factory ((ns-resolve 'jolt.codec.scalar-text.experimental 'native-plan-factory))
      raw (otel.research.trace-affinity/group-traces
            (mapv (fn [span]
                    (update (assoc-in span [:span-context :trace-flags] 1) :attributes into
                      (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value" 1 false i)]) (range 12))))
                  (with-redefs [fixture/batch-size 5000] (fixture/spans "typed-compact-prefix"))))
      rows (mapv #(-> % (update-in [:resource :attributes] (fn [m] (into (sorted-map) m)))
                        (update-in [:scope :attributes] (fn [m] (into (sorted-map) m)))
                        (update :events (fn [events] (mapv (fn [event] (update event :attributes (fn [m] (into (sorted-map) m)))) events))))
                 (nested-sorted-sdk-shape raw))
      maps (vec (mapcat (fn [span] [(get-in span [:resource :attributes])
                                  (:attributes span) (get-in span [:events 0 :attributes])]) rows))
      ;; span-row currently uses resource, span and event attributes, not scope
      ;; attributes, for trace rows. Nested EventsJSON still sees original data.
      table ((scheme/eval-string
                "(lambda () (let ((h (make-eq-hashtable))) (lambda (op m v) (if (= op 0) (hashtable-ref h m #f) (hashtable-set! h m v)))))"))
      populate ((:payload-cache-factory c))
      _ (doseq [m maps] (let [converted (populate m)] (assert converted) (table 1 m converted)))
      equal-bytes? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      no-store (fn [] (any/scalar-string-map-payload-converter* 0 0 (:folder c)
                        {:shape-plan-factory shape-factory}))
      frozen (fn [m] (or (table 0 m nil) (throw (ex-info "Unprepared fixture map" {}))))
      encode (fn [arm]
               (let [convert (#'exporter/payload-attrs-converter
                               (case arm :normal ((:payload-cache-factory c))
                                         :no-store (no-store) :frozen frozen))]
                 ((:encode c) "SQL\n" rows
                   (fn [span] (#'exporter/span-row span nil true (:nested c) convert)) 67108864)))
      _ (assert (clojure.string/includes? (str (io/resource "otel/any_value.clj")) "14f58e5a"))
      expected (encode :normal)
      _ (doseq [arm [:no-store :frozen]] (assert (equal-bytes? expected (encode arm))))
      samples (atom [])]
  (dotimes [round 6]
    (doseq [arm (if (even? round) [:normal :no-store :frozen] [:frozen :no-store :normal])]
      (let [before (#'bench/counter-sample) start (System/nanoTime)
            out (encode arm) elapsed (- (System/nanoTime) start)
            after (#'bench/counter-sample)]
        (assert (equal-bytes? expected out))
        (swap! samples conj {:round round :arm arm :nanos elapsed
                             :counters (#'bench/counter-delta before after)}))))
  (spit (first *command-line-args*)
    (pr-str {:scope :scalar-normalization-cost-ceiling :samples @samples :rows 5000
             :source-maps (count maps) :all-output-bytes-identical true
             :frozen-is-not-a-production-proposal true
             :frozen-retention-unbounded-by-production-policy true
             :typed-projection-excluded true :durable-throughput-qualified false
             :codec-source (str (io/resource "jolt/codec/experimental.clj"))}))
  (doseq [[arm xs] (group-by :arm @samples)]
    (let [ordered (vec (sort (map :nanos xs)))
          q (fn [p] (/ (nth ordered (dec (long (Math/ceil (* p (count ordered)))))) 1e6))]
      (prn {:arm arm :p50-p90-p95-p99-ms (mapv q [0.5 0.9 0.95 0.99])
            :estimated-bytes-per-row (/ (reduce + (map #(get-in % [:counters :scheme-heap-bytes-allocated]) xs))
                                        (* 5000.0 (count xs)))}))))
