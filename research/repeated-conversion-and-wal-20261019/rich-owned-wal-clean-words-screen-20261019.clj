(require '[clojure.data.json.jolt-native :as native]
         '[jolt.scheme :as scheme]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as projection]
         '[otel.exporter.chdb-attribute-projection-test :as installed]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb.owned-statement :as owned]
         '[jdbc.chdb.durable.wal :as wal]
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
      prefix (with-bindings {#'e/*timestamp-wire* :raw-ticks}
               (str (#'e/compact-insert-query "otel_traces" columns) " FORMAT JSONCompactEachRow\n"))
      payload (with-bindings {#'e/*timestamp-wire* :raw-ticks #'e/*json-backend* :native-guarded-byte-batch}
                (native/write-prefixed-batch-bytes! prefix
                  (map #(into (#'e/span-row % nil true) (projector %)) spans) 67108864))
      _ (assert (= 8648398 (alength payload)))
      _ (assert (= "862345e59a41b20bccce71f81bef02e2915bc525c692be3b59fd45814e9d8e39"
                   (digest/sha256-bytes payload)))
      statement (owned/try-snapshot payload)
      _ (assert (owned/statement? statement))
      baseline (scheme/eval-string (slurp "/home/chuck/ai-src/worktrees/chdb-selected-native-settings-audit-20261019/resources/jdbc/chdb/owned_ascii_wal.ss"))
      candidate (scheme/eval-string (slurp "/home/chuck/ai-src/worktrees/chdb-owned-wal-clean-words-20261019/resources/jdbc/chdb/owned_ascii_wal.ss"))
      sample #'bench/counter-sample delta #'bench/counter-delta
      emit (fn [prepared]
             (let [out (java.io.ByteArrayOutputStream.)]
               (wal/write-prepared! out prepared) (.toByteArray out)))
      run! (fn [kernel]
             (let [before (sample) start (System/nanoTime)
                   result (with-redefs-fn {#'owned/wal-kernel (delay kernel)}
                            #(owned/prepared-wal statement))
                   end (System/nanoTime) after (sample)]
               {:result result :nanos (- end start) :counters (delta before after)}))
      reference (emit (:result (run! baseline)))
      size (alength reference) sha (digest/sha256-bytes reference)
      _ (dotimes [_ 3] (run! baseline) (run! candidate))
      _ (System/gc)
      observations
      (mapv (fn [ordinal]
              (let [arm (nth [:baseline :candidate :candidate :baseline] (mod ordinal 4))
                    result (run! (if (= arm :baseline) baseline candidate))
                    prepared (:result result)
                    bytes (emit prepared)]
                (assert (= size (alength bytes) (:byte-count prepared)))
                (assert (= sha (digest/sha256-bytes bytes)))
                (println :wal-clean-words ordinal arm (:nanos result)) (flush)
                (assoc (dissoc result :result) :ordinal ordinal :arm arm
                       :chunks (count (:chunks prepared))))) (range 48))]
  (spit (first *command-line-args*)
        (pr-str {:scope :same-process-owned-wal-kernel-screen-not-durable-throughput
                 :runtime "7098a8d6" :exporter "2c54b98" :sdk "7878a2e" :json "2172061"
                 :driver "3d766fa" :rows 5000 :input-bytes (alength payload)
                 :wal-bytes size :wal-sha256 sha :all-output-hashes-equal true
                 :durable-execution false :tail-qualified false :observations observations})))
