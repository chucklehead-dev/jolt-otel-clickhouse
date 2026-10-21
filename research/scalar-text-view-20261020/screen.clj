;; Matched learned-shape normalization + encoding, not SDK/Durable throughput.
(require '[jolt.codec.experimental :as codec]
         '[jolt.codec.scalar-text.experimental :as shapes]
         '[jolt.host :as host] '[jolt.scheme :as scheme])

(def policy (Object.))
(def original-policy policy)
(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes)
   :cpu (host/cpu-nanos) :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn quantile [values p]
  (nth (vec (sort values)) (dec (long (Math/ceil (* p (count values)))))))

(let [rows (mapv (fn [row]
                   (into (sorted-map)
                     (map (fn [i] [(str "key-" i)
                                   (case (mod i 3) 0 "value-λ\"\\\n😀" 1 false (+ i row))]))
                     (range 17))) (range 5000))
      args [[[#'policy original-policy]] 100 (sorted-map) 8 8192]
      baseline-root (second *command-line-args*)
      original (apply (scheme/eval-string
                        (str "(let ((compile-one " (slurp (str baseline-root "/plan.ss")) ")) "
                             (slurp (str baseline-root "/planner.ss")) ")")) args)
      plain (apply (shapes/native-plan-factory) args)
      view (apply (shapes/native-view-plan-factory (codec/native-scalar-text-view-box)) args)
      c (codec/open-codec)
      _ (assert (original 1 (first rows)))
      _ (assert (plain 1 (first rows))) _ (assert (view 1 (first rows)))
      emit (fn [mode values]
             (let [plan (case mode :original original :map plain :view view)]
               (codec/encode-prefixed-records c "" values
                 (fn [m] (or (plan 0 m) (throw (ex-info "Unexpected decline" {})))) 67108864)))
      equal? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")
      expected (emit :original rows)
      _ (assert (equal? expected (emit :map rows)))
      _ (assert (equal? expected (emit :view rows)))
      _ (doseq [mode [:original :map :view]]
          (assert (not (equal? expected (emit mode (assoc-in rows [0 "key-2"] -999))))))
      _ (doseq [mode [:original :map :view :view :map :original]] (emit mode rows))
      observations
      (mapv (fn [ordinal]
              (let [mode (nth [:original :map :view :view :map :original] (mod ordinal 6))
                    before (counters) start (System/nanoTime)
                    output (emit mode rows) nanos (- (System/nanoTime) start)
                    after (counters)]
                (assert (equal? expected output))
                {:ordinal ordinal :mode mode :nanos nanos
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after))
                                     (+ (:heap before) (:gc-bytes before)))
                 :cpu-nanos (- (:cpu after) (:cpu before))
                 :gc-count (- (:gc-count after) (:gc-count before))
                 :gc-nanos (- (:gc-nanos after) (:gc-nanos before))})) (range 48))
      summary (into {} (for [[mode samples] (group-by :mode observations)]
                         [mode {:p50-p90-p95-p99-ms
                                (mapv #(/ (quantile (map :nanos samples) %) 1e6) [0.5 0.9 0.95 0.99])
                                :median-estimated-bytes-per-map
                                (/ (quantile (map :estimated-bytes samples) 0.5) 5000.0)}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :learned-scalar-plan-plus-byte-encoding
             :rows 5000 :fields-per-map 17 :independent-sorted-roots true
             :full-output-bytes-equal true :changed-input-controls true
             :sdk-cache-admission-excluded true :sdk-integration false
             :native-io false :durable-io false :tail-qualified false
             :output-bytes (alength expected) :summary summary :observations observations}))
  (prn summary))
