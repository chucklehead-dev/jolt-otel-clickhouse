;; Component attribution only: no SQL execution, queue or durability claim.
;; Both modes project inside the REAL native batch binding. Prepared mode
;; realizes one whole batch first; this is diagnostic, not a product change to
;; streaming/error/budget ordering or peak-memory guarantees.
(require '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
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
      fields (projection/confirmed-span-fields descriptors target)
      projector (projection/trace-vector-projector descriptors target true)
      columns (fixture/columns fields)
      _ (assert (= 28 (count columns)))
      spans (otel.research.trace-affinity/group-traces
              (mapv (fn [span]
                      (update span :attributes into
                              (map (fn [i] [(str "extra-" i)
                                            (case (mod i 3) 0 "value" 1 false i)])) (range 12)))
                    (nested-sorted-sdk-shape
                      (with-redefs [fixture/batch-size 5000]
                        (fixture/spans "typed-compact-prefix")))))
      row (fn [span] (into (#'e/span-row span nil true) (projector span)))
      prefix (with-bindings {#'e/*timestamp-wire* :raw-ticks}
               (str (#'e/compact-insert-query "otel_traces" columns) " FORMAT JSONCompactEachRow\n"))
      sample #'bench/counter-sample delta #'bench/counter-delta
      run! (fn [mode records]
             (let [preparation (atom nil)
                   rows (case mode
                          :lazy (map row records)
                          :prepared
                          (lazy-seq
                            (let [before (sample) start (System/nanoTime)
                                  values (mapv row records)
                                  end (System/nanoTime) after (sample)]
                              (reset! preparation {:nanos (- end start) :counters (delta before after)})
                              (seq values))))
                   before (sample) start (System/nanoTime)
                   bytes (native/write-prefixed-batch-bytes! prefix rows 67108864)
                   end (System/nanoTime) after (sample)]
               {:bytes bytes :nanos (- end start) :counters (delta before after)
                :preparation @preparation}))]
  ;; A stock ordinary writer independently checks conversion + escaping of
  ;; one complete rich row. Do not time or retain that reference String.
  (let [ordinary (with-bindings {#'e/*timestamp-wire* :raw-ticks #'e/*json-backend* :configured}
                   (binding [json/*experimental-native-writer* nil]
                     (str prefix (json/write-str (row (first spans))) "\n")))
        native-bytes (with-bindings {#'e/*timestamp-wire* :raw-ticks #'e/*json-backend* :native-guarded-byte-batch}
                       (:bytes (run! :lazy [(first spans)])))]
    (assert (= ordinary (String. native-bytes "UTF-8"))))
  (with-bindings {#'e/*timestamp-wire* :raw-ticks #'e/*json-backend* :native-guarded-byte-batch}
    (let [reference (:bytes (run! :lazy spans))
          reference-size (alength reference) reference-sha (digest/sha256-bytes reference)
          changed (assoc spans 0 (assoc (first spans) :name "causal-output-control"))
          _ (assert (not= reference-sha (digest/sha256-bytes (:bytes (run! :lazy changed)))))
          _ (dotimes [_ 3] (run! :lazy spans) (run! :prepared spans))
          _ (System/gc)
          observations
          (mapv (fn [i]
                  (let [mode (nth [:lazy :prepared :prepared :lazy] (mod i 4))
                        result (run! mode spans)]
                    ;; Hash/size outside the timed section; no row/payload in
                    ;; scalar output. Every arm must actually emit the fixture.
                    (assert (= reference-size (alength (:bytes result))))
                    (assert (= reference-sha (digest/sha256-bytes (:bytes result))))
                    (println :row-emission-sample :ordinal i :mode mode :nanos (:nanos result))
                    (flush)
                    (assoc (dissoc result :bytes) :ordinal i :mode mode)))
                (range 32))]
      (assert (= {:lazy 16 :prepared 16} (frequencies (map :mode observations))))
      (spit "/home/chuck/ai-src/evidence/json-rich-row-vs-emission-20261018.edn"
            (pr-str {:scope :row-construction-vs-emission-component-screen
                     :rows 5000 :columns 28 :samples 32 :order [:lazy :prepared :prepared :lazy]
                     :runtime "7098a8d6" :exporter "4d48871" :sdk "7878a2e" :json "96f3e47"
                     :native-batch-binding-context true :payload-bytes reference-size
                     :payload-sha256 reference-sha :ordinary-one-row-reference true
                     :changed-input-output-control true :all-output-hashes-equal true
                     :whole-batch-preparation-diagnostic-only true :durable-execution false
                     :tail-qualified false :observations observations})))))
