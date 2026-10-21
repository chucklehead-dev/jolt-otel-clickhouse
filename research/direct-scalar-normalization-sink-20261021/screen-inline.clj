(load-file "research/direct-scalar-normalization-sink-20261021/inline.clj")
(require '[jolt.host :as host])
(def policy (Object.))
(def original-policy policy)
(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes)
   :thread-cpu-nanos (host/cpu-nanos) :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn q [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))

(let [[output sdk-path] *command-line-args* sdk (slurp sdk-path)
      contexts {:copy (sink-context :copy sdk) :prevalidated (sink-context :sink sdk)
                :inline (inline-context sdk)}
      bindings [[#'policy original-policy]]
      rows (mapv (fn [row]
                   (into {} (map (fn [i] [(str "key-" i)
                                         (case (mod i 3) 0 "value-λ\"\\\n😀" 1 false (+ i row))]))
                     (range 17))) (range 5000))
      emit (fn [kind values]
             (sink-encode (contexts kind) bindings 100 values 67108864 128))
      expected (emit :copy rows)
      order [:copy :prevalidated :inline :inline :prevalidated :copy]
      _ (doseq [kind order]
          (assert (bytes-equal? expected (emit kind rows)))
          (assert (not (bytes-equal? expected (emit kind (assoc-in rows [0 "key-2"] -999))))))
      observations
      (mapv (fn [ordinal]
              (let [kind (nth order (mod ordinal 6))
                    before (counters) started (System/nanoTime)
                    actual (emit kind rows) nanos (- (System/nanoTime) started) after (counters)]
                (assert (bytes-equal? expected actual))
                {:ordinal ordinal :kind kind :nanos nanos
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after))
                                     (+ (:heap before) (:gc-bytes before)))
                 :thread-cpu-nanos (- (:thread-cpu-nanos after) (:thread-cpu-nanos before))
                 :gc-count (- (:gc-count after) (:gc-count before))
                 :gc-nanos (- (:gc-nanos after) (:gc-nanos before))})) (range 48))
      summary (into {} (for [[kind xs] (group-by :kind observations)]
                         [kind {:p50-p90-p95-p99-ms (mapv #(/ (q (map :nanos xs) %) 1e6) [0.5 0.9 0.95 0.99])
                                :median-estimated-bytes-per-map (/ (q (map :estimated-bytes xs) 0.5) 5000.0)}]))]
  (spit output (pr-str {:scope :research-inline-scalar-validation-encoding-floor
                       :rows 5000 :fields-per-map 17 :samples-per-arm 16 :order order
                       :original-sdk-source sdk-path :source-patch-prototype true
                       :full-output-bytes-equal true :changed-value-control true
                       :fixed-maximum-string-characters 100 :no-schema-learning true :no-slot-view true
                       :full-sdk-owner-excluded true :rich-span-excluded true
                       :sdk-fallback-replay-not-implemented true :collision-admission-not-equivalent true
                       :native-io false :durable-io false :production-adopted false
                       :population-tail-qualified false :output-bytes (alength expected)
                       :summary summary :observations observations}))
  (prn summary))
