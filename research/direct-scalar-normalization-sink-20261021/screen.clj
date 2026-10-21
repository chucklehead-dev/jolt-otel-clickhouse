(load-file "research/direct-scalar-normalization-sink-20261021/prototype.clj")
(require '[jolt.host :as host])

(def policy (Object.))
(def original-policy policy)
(defn counters []
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes)
   :thread-cpu-nanos (host/cpu-nanos) :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)})
(defn q [xs p]
  (nth (vec (sort xs)) (dec (long (Math/ceil (* p (count xs)))))))
(defn rejects-with? [f message]
  (try (f) false
       (catch Throwable ex (str/includes? (or (.getMessage ex) "") message))))

(let [[output sdk-path] *command-line-args*
      sdk (slurp sdk-path)
      contexts (into {} (map (fn [kind] [kind (sink-context kind sdk)]) [:copy :sink :unquoted-mutant]))
      bindings [[#'policy original-policy]]
      rows (mapv (fn [row]
                   (into {} (map (fn [i] [(str "key-" i)
                                         (case (mod i 3) 0 "value-λ\"\\\n😀" 1 false (+ i row))]))
                     (range 17))) (range 5000))
      emit (fn [kind values limit depth]
             (sink-encode (get contexts kind) bindings 100 values limit depth))
      expected (emit :copy rows 67108864 128)
      _ (assert (bytes-equal? expected (emit :sink rows 67108864 128)))
      _ (assert (not (bytes-equal? expected (emit :unquoted-mutant rows 67108864 128))))
      _ (doseq [kind [:copy :sink]]
          (assert (not (bytes-equal? expected (emit kind (assoc-in rows [0 "key-2"] -999) 67108864 128))))
          (assert (bytes-equal? expected (emit kind rows (alength expected) 128)))
          (assert (rejects-with? #(emit kind rows (dec (alength expected)) 128) "byte limit exceeded"))
          (assert (rejects-with? #(emit kind rows 67108864 0) "depth limit exceeded")))
      _ (doseq [kind [:copy :sink]]
          (with-redefs [policy (Object.)]
            (assert (try (emit kind rows 67108864 128) false
                         (catch clojure.lang.ExceptionInfo ex (= ::declined (:type (ex-data ex))))))))
      _ (let [a (emit :sink rows 67108864 128) b (emit :sink rows 67108864 128)]
          (aset-byte b 0 120)
          (assert (bytes-equal? a expected))
          (assert (not (bytes-equal? a b))))
      _ (doseq [kind [:copy :sink :sink :copy]] (emit kind rows 67108864 128))
      observations
      (mapv (fn [ordinal]
              (let [kind (nth [:copy :sink :sink :copy] (mod ordinal 4))
                    before (counters) started (System/nanoTime)
                    actual (emit kind rows 67108864 128)
                    nanos (- (System/nanoTime) started) after (counters)]
                (assert (bytes-equal? expected actual))
                {:ordinal ordinal :kind kind :nanos nanos
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after))
                                     (+ (:heap before) (:gc-bytes before)))
                 :thread-cpu-nanos (- (:thread-cpu-nanos after) (:thread-cpu-nanos before))
                 :gc-count (- (:gc-count after) (:gc-count before))
                 :gc-nanos (- (:gc-nanos after) (:gc-nanos before))})) (range 32))
      summary (into {} (for [[kind xs] (group-by :kind observations)]
                         [kind {:p50-p90-p95-p99-ms (mapv #(/ (q (map :nanos xs) %) 1e6) [0.5 0.9 0.95 0.99])
                                :median-estimated-bytes-per-map (/ (q (map :estimated-bytes xs) 0.5) 5000.0)}]))]
  (spit output (pr-str {:scope :research-direct-scalar-normalization-sink
                       :rows 5000 :fields-per-map 17 :samples-per-arm 16
                       :original-sdk-source sdk-path :source-patch-prototype true
                       :full-output-bytes-equal true :unquoted-mutant-detected true
                       :changed-value-control true :fresh-output-control true
                       :policy-decline-control true :exact-and-one-under-byte-controls true
                       :depth-control true :no-schema-learning true :no-slot-view true
                       :full-sdk-owner-excluded true :rich-span-excluded true
                       :native-io false :durable-io false :production-adopted false
                       :population-tail-qualified false :output-bytes (alength expected)
                       :summary summary :observations observations}))
  (prn summary))
