;; Counterfactual component bound ONLY. Reuses immutable fixture conversions
;; without claiming that arbitrary public callbacks may safely be cached.
(require '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[jolt.scheme :as scheme]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.durable.digest :as digest])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(load-file "/home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj")

(let [target (Object.) installation (#'installed/installed (fixture/approved) target)
      descriptors (:descriptor-set installation)
      projector (projection/trace-vector-projector descriptors target true)
      columns (fixture/columns (projection/confirmed-span-fields descriptors target))
      spans (otel.research.trace-affinity/group-traces
              (mapv (fn [span]
                      (update span :attributes into
                              (map (fn [i] [(str "extra-" i)
                                            (case (mod i 3) 0 "value" 1 false i)]) (range 12))))
                    (nested-sorted-sdk-shape
                      (with-redefs [fixture/batch-size 5000]
                        (fixture/spans "typed-compact-prefix")))))
      shared (get-in (first spans) [:resource :attributes])
      _ (assert (every? #(and (identical? shared (get-in % [:resource :attributes]))
                             (identical? shared (get-in % [:events 0 :attributes]))) spans))
      attrs-original @#'e/attrs nested-original @#'e/nested-json-string
      row (fn [span] (into (#'e/span-row span nil true) (projector span)))
      prefix (with-bindings {#'e/*timestamp-wire* :raw-ticks}
               (str (#'e/compact-insert-query "otel_traces" columns) " FORMAT JSONCompactEachRow\n"))
      new-cache (scheme/eval-string
                  "(lambda () (let ((t (make-eq-hashtable)))
                     (case-lambda ((k) (hashtable-ref t k #f))
                                  ((k v) (hashtable-set! t k v) v))))")
      sample #'bench/counter-sample delta #'bench/counter-delta
      ;; Build counterfactual reusable results INSIDE the real native binding.
      ;; Its default option/dispatch guards are not a different reference writer.
      cache (new-cache) converted (atom nil)
      _ (with-bindings {#'e/*timestamp-wire* :raw-ticks #'e/*json-backend* :native-guarded-byte-batch}
          (native/write-prefixed-batch-bytes! ""
            (lazy-seq
              (reset! converted (attrs-original shared))
              (doseq [span spans]
                (let [events (:events span)] (cache events (nested-original events))))
              nil) 0))
      run! (fn [mode records calibration?]
             (let [attr-hits (atom 0) nested-hits (atom 0)
                   reuse-attrs? (contains? #{:attrs-reuse :both-reuse} mode)
                   reuse-events? (contains? #{:events-json-reuse :both-reuse} mode)
                   attrs-fn (fn [m]
                              (if (and reuse-attrs? (identical? m shared))
                                (do (when calibration? (swap! attr-hits inc)) @converted)
                                (attrs-original m)))
                   nested-fn (fn [v]
                               (if-let [cached (when reuse-events? (cache v))]
                                 (do (when calibration? (swap! nested-hits inc)) cached)
                                 (nested-original v)))
                   before (sample) start (System/nanoTime)
                   bytes (with-redefs-fn {#'e/attrs attrs-fn #'e/nested-json-string nested-fn}
                           #(native/write-prefixed-batch-bytes! prefix (map row records) 67108864))
                   end (System/nanoTime) after (sample)]
               {:bytes bytes :nanos (- end start) :counters (delta before after)
                :attr-hits @attr-hits :nested-hits @nested-hits}))]
  (with-bindings {#'e/*timestamp-wire* :raw-ticks #'e/*json-backend* :native-guarded-byte-batch}
    (let [reference (:bytes (run! :baseline spans false))
          size (alength reference) sha (digest/sha256-bytes reference)
          modes [:baseline :attrs-reuse :events-json-reuse :both-reuse]
          _ (assert (= 8648398 size))
          _ (assert (= "862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39" sha))
          calibration
          (mapv (fn [mode]
                  (let [result (run! mode spans true)]
                    (assert (= sha (digest/sha256-bytes (:bytes result))))
                    (assert (= (if (contains? #{:attrs-reuse :both-reuse} mode) 10000 0)
                               (:attr-hits result)))
                    (assert (= (if (contains? #{:events-json-reuse :both-reuse} mode) 5000 0)
                               (:nested-hits result)))
                    (select-keys (assoc result :mode mode) [:mode :attr-hits :nested-hits]))) modes)
          ;; A changed event identity must MISS the precomputed event cache.
          changed (assoc-in spans [0 :events 0 :name] "changed-event-causal-control")
          changed-sha (digest/sha256-bytes (:bytes (run! :baseline changed false)))
          _ (assert (not= sha changed-sha))
          _ (doseq [mode modes]
              (assert (= changed-sha (digest/sha256-bytes (:bytes (run! mode changed false))))))
          _ (dotimes [_ 2] (doseq [mode modes] (run! mode spans false)))
          _ (System/gc)
          observations
          (mapv (fn [ordinal]
                  ;; Balanced rotating/reversing order, 12 observations per mode.
                  (let [round (quot ordinal 4) slot (mod ordinal 4)
                        index (mod (+ round (if (even? round) slot (- 3 slot))) 4)
                        mode (nth modes index)
                        result (run! mode spans false)]
                    (assert (= size (alength (:bytes result))))
                    (assert (= sha (digest/sha256-bytes (:bytes result))))
                    (println :conversion-bound ordinal mode (:nanos result)) (flush)
                    (assoc (dissoc result :bytes) :ordinal ordinal :mode mode))) (range 48))]
      (assert (= (zipmap modes (repeat 12)) (frequencies (map :mode observations))))
      (spit (first *command-line-args*)
            (pr-str {:scope :counterfactual-repeated-conversion-component-bound
                     :runtime "7098a8d6" :exporter "2c54b98" :sdk "7878a2e" :json "2172061"
                     :rows 5000 :payload-bytes size :payload-sha256 sha
                     :calibration calibration :changed-event-cache-miss-control true
                     :all-output-hashes-equal true :durable-execution false
                     :cache-policy-qualified false :tail-qualified false
                     :observations observations})))))
