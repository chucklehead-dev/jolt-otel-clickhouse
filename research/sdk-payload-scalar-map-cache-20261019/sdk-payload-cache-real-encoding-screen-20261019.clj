;; Actual exporter entry, with local SDK prototype only. No database execution.
;; Shared vs independently constructed maps calibrate the identity-cache effect.
(require '[clojure.java.io :as io] '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[otel.any-value :as any] '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.owned-statement :as owned]
         '[jdbc.chdb.durable.digest :as digest])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(load-file "/home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj")
(assert (.contains (str (io/resource "otel/any_value.clj")) "otel-payload-scalar-map-"))
(assert (.contains (str (io/resource "otel/exporter/chdb.clj")) "exporter-sdk-payload-scalar-cache-consumer-20261019"))
(assert (.contains (str (io/resource "clojure/data/json/jolt_byte_writer.ss")) "2172061060a102ccab779f5adc5d77f8b83a81f6"))
(assert (some? (any/scalar-string-map-payload-converter)))
(native/load-payload-byte-buffer-writer!)
(def mechanism-converter (atom nil))
(defn mechanism-factory [] @mechanism-converter)
(let [target (Object.) installation (#'installed/installed (fixture/approved) target)
      descriptors (:descriptor-set installation)
      fields (projection/confirmed-span-fields descriptors target)
      projector (projection/trace-vector-projector descriptors target true)
      columns (fixture/columns fields)
      shared (otel.research.trace-affinity/group-traces
               (mapv (fn [span]
                       (update span :attributes into
                         (map (fn [i] [(str "extra-" i)
                                      (case (mod i 3) 0 "value" 1 false i)]))
                         (range 12)))
                 (nested-sorted-sdk-shape
                   (with-redefs [fixture/batch-size 5000]
                     (fixture/spans "typed-compact-prefix")))))
      unshared (mapv (fn [span]
                       (-> span
                           (update-in [:resource :attributes] #(into (sorted-map) %))
                           (update-in [:scope :attributes] #(into (sorted-map) %))
                           (update :events
                             (fn [events]
                               (mapv #(update % :attributes
                                         (fn [m] (into (sorted-map) m))) events))))) shared)
      records {:shared shared :unshared unshared}
      _ (assert (= shared unshared))
      _ (assert (not (identical? (get-in (first unshared) [:resource :attributes])
                                  (get-in (second unshared) [:resource :attributes]))))
      row (fn [span] (into (#'e/span-row span nil true) (projector span)))
      prefix (with-bindings {#'e/*timestamp-wire* :raw-ticks}
               (str (#'e/compact-insert-query "otel_traces" columns) " FORMAT JSONCompactEachRow\n"))
      factory @#'e/sdk-scalar-map-payload-factory
      sample #'bench/counter-sample delta #'bench/counter-delta
      emit! (fn [enabled? spans]
              (with-redefs-fn {#'e/sdk-scalar-map-payload-factory (if enabled? factory (delay nil))}
                (fn []
                  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch
                                  #'e/*insert-byte-limit* 67108864
                                  #'e/*timestamp-wire* :raw-ticks}
                    (#'e/json-each-row-payload (map row spans) prefix true)))))
      wire (fn [statement] (.getBytes (owned/text statement) "UTF-8"))
      reference (wire (emit! false shared))
      size (alength reference) sha (digest/sha256-bytes reference)
      _ (assert (= 8648398 size))
      _ (assert (= "862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39" sha))
      _ (doseq [shape [:shared :unshared] enabled? [false true]]
          (assert (= sha (digest/sha256-bytes (wire (emit! enabled? (records shape)))))))
      _ (assert (not= sha (digest/sha256-bytes
                           (wire (emit! true (assoc shared 0 (assoc (first shared) :name "changed-output")))))))
      mechanism
      (let [plain (atom 0) sorted (atom 0)
            real-plain @#'any/original-try-scalar-string-map
            real-sorted @#'any/original-try-sorted-scalar-string-map]
        ;; Construct before replacing private aliases. Constructing afterwards
        ;; invalidates the defining anchors and merely tests the old fallback.
        ;; This calibration cache is used once and never in the timed samples.
        (reset! mechanism-converter (any/scalar-string-map-payload-converter))
        (with-redefs [any/original-try-scalar-string-map
                      (fn [m] (swap! plain inc) (real-plain m))
                      any/original-try-sorted-scalar-string-map
                      (fn [m] (swap! sorted inc) (real-sorted m))
                      e/sdk-scalar-map-payload-factory (delay #'mechanism-factory)]
          (with-bindings {#'e/*json-backend* :native-guarded-byte-batch
                          #'e/*insert-byte-limit* 67108864
                          #'e/*timestamp-wire* :raw-ticks}
            (#'e/json-each-row-payload (map row shared) prefix true))
          {:plain-misses @plain :sorted-misses @sorted}))
      _ (assert (= 1 (:sorted-misses mechanism)))
      _ (assert (> (:plain-misses mechanism) 1000))
      _ (doseq [enabled? [false true true false]] (emit! enabled? shared))
      _ (System/gc)
      observations
      (mapv (fn [ordinal]
              (let [shape (nth [:shared :unshared :unshared :shared] (mod ordinal 4))
                    enabled? (contains? #{1 2} (mod (quot ordinal 4) 4))
                    before (sample) start (System/nanoTime)
                    result (emit! enabled? (records shape))
                    end (System/nanoTime) after (sample)
                    bytes (wire result)]
                (assert (= size (alength bytes)))
                (assert (= sha (digest/sha256-bytes bytes)))
                (println :sample ordinal :shape shape :cache? enabled? :nanos (- end start))
                (flush)
                {:ordinal ordinal :shape shape :cache? enabled?
                 :nanos (- end start) :counters (delta before after)})) (range 64))]
  (assert (nil? @#'e/*sdk-scalar-map-payload-converter*))
  (spit (first *command-line-args*)
    (pr-str {:scope :real-exporter-owned-encoding-with-local-sdk-prototype
             :rows 5000 :columns (count columns) :samples 64
             :runtime "7098a8d6" :exporter-base "2c54b98" :sdk-base "7878a2e"
             :json "2172061" :sdk-local-diff true :exporter-local-diff true
             :sdk-source-resource (str (io/resource "otel/any_value.clj"))
             :complete-payload-bytes size :complete-payload-sha256 sha
             :all-output-hashes-equal true :changed-input-control true
             :actual-exporter-entry true :durable-execution false
             :explicit-insert-byte-limit 67108864
             :tail-qualified false :mechanism mechanism :observations observations})))
