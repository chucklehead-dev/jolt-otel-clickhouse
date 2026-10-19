;; Component attribution only: no SQL execution, queue or durability claim.
;; Both modes project inside the REAL native batch binding. Prepared mode
;; realizes one whole batch first; this is diagnostic, not a product change to
;; streaming/error/budget ordering or peak-memory guarantees.
(require '[clojure.string :as str] '[jolt.scheme :as scheme]
         '[clojure.data.json :as json] '[clojure.java.io :as io]
         '[clojure.data.json.jolt-native :as native]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.durable.digest :as digest])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(load-file "/home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj")

(native/load-payload-byte-buffer-writer!)
(let [source (slurp (io/resource "clojure/data/json/jolt_byte_writer.ss"))
      pure-source (slurp "/home/chuck/ai-src/evidence/stock-data-emitter-diagnostic-20261019.ss")
      entry "(define (emit! x)\n    (let ((root (var-cell-root djn-method-cell)))"
      ending "(else (invoke! impl x)))))))"
      _ (assert (and (str/includes? source entry) (str/includes? source ending)))
      staged-source (-> source
                        (str/replace entry
                          (str pure-source "\n(define (emit! x)\n"
                               "(if (vector-ref pure-mode 0) (emit-stock-data! x)\n"
                               "(let ((root (var-cell-root djn-method-cell)))"))
                        (str/replace ending "(else (invoke! impl x))))))))"))
      stage (scheme/eval-string
              (str "(let ((pure-mode (vector #f))) " staged-source
                   " (jolt-vector djn-make-byte-buffer-writer"
                   " (lambda (enabled) (vector-set! pure-mode 0 enabled))))"))
      select! (nth stage 1)]
 (with-redefs [native/load-payload-byte-buffer-writer! (fn [] ((nth stage 0)))]
  ;; Calibrate the deliberate semantic difference outside the timed section.
  ;; Pure mode MUST ignore this live JSONWriter root: it is NOT an eligible
  ;; replacement for the guarded product backend, even if fixture bytes match.
  (let [control
        (with-redefs [json/-write (fn [_ out _] (.write out "0"))]
          (mapv (fn [pure?]
                  (select! pure?)
                  (binding [json/*experimental-native-writer* (native/load-payload-byte-buffer-writer!)]
                    (json/write-str "probe"))) [false true]))]
    (assert (= ["0" "\"probe\""] control)))
  (select! false)
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
          _ (assert (= 8648398 reference-size))
          _ (assert (= "862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39" reference-sha))
          changed (assoc spans 0 (assoc (first spans) :name "causal-output-control"))
          _ (assert (not= reference-sha (digest/sha256-bytes (:bytes (run! :lazy changed)))))
          _ (doseq [pure? [false true true false]]
              (select! pure?) (run! :lazy spans) (run! :prepared spans))
          _ (System/gc)
          observations
          (mapv (fn [i]
                  (let [mode (nth [:lazy :prepared :prepared :lazy] (mod i 4))
                        pure? (contains? #{1 2} (mod (quot i 4) 4))
                        _ (select! pure?)
                        result (run! mode spans)]
                    ;; Hash/size outside the timed section; no row/payload in
                    ;; scalar output. Every arm must actually emit the fixture.
                    (assert (= reference-size (alength (:bytes result))))
                    (assert (= reference-sha (digest/sha256-bytes (:bytes result))))
                    (println :row-emission-sample :ordinal i :mode mode :nanos (:nanos result))
                    (flush)
                    (assoc (dissoc result :bytes) :ordinal i :mode mode :closed-data? pure?)))
                (range 64))]
      (assert (= {:lazy 32 :prepared 32} (frequencies (map :mode observations))))
      (spit (first *command-line-args*)
            (pr-str {:scope :closed-data-counterfactual-encoding-ceiling-not-compatible-backend
                     :rows 5000 :columns 28 :samples 64 :order [:lazy :prepared :prepared :lazy]
                     :runtime "7098a8d6" :exporter "2c54b98" :sdk "7878a2e" :json "2172061"
                     :source-overlay true :deliberately-bypasses-json-extension-contracts true
                     :live-root-semantic-difference-control true
                     :json-resource (str (io/resource "clojure/data/json/jolt_byte_writer.ss"))
                     :native-batch-binding-context true :payload-bytes reference-size
                     :payload-sha256 reference-sha :ordinary-one-row-reference true
                     :changed-input-output-control true :all-output-hashes-equal true
                     :whole-batch-preparation-diagnostic-only true :durable-execution false
                     :tail-qualified false :observations observations})))))))
