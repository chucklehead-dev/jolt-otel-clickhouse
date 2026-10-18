;; Direct serial callback comparison, NOT SDK queue/OTLP/application evidence.
;; Separate best-path processes: JSON retains owned compact streaming, while
;; RowBinary explicitly configures query-budget via the non-streaming open seam.
;; One connection/owner, real compiled runtime, normal JSON exporter versus
;; explicit experimental RowBinary SQL using unchanged production span rows.
(require '[db.jdbc] '[jdbc.core :as jdbc]
         '[jdbc.chdb.durable :as durable] '[jdbc.chdb.native :as native]
         '[jdbc.chdb.owned-statement :as owned]
         '[jdbc.chdb-typed-storage-support :as storage]
         '[otel.sdk.export :as export] '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb-benchmark :as bench]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[clojure.data.json.jolt-native :as json-native]
         '[jolt.scheme :as scheme])
(load-file "/home/chuck/ai-src/evidence/rowbinary-telemetry-spike-helpers-20261017.clj")
(load-file "/home/chuck/ai-src/evidence/rowbinary-rich-abba-budget-20261018.clj")
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(load-file "/home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj")

(def rb-rich-base64
  ;; Invocation-owned library prototype; never replace the runtime b64 helper.
  (scheme/eval-string (slurp "/home/chuck/ai-src/evidence/base64-flat-string-kernel-20261017.ss")))
(def rb-rich-alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")
(doseq [n [0 1 2 3 4 127 128 129 255 256 257]]
  (let [bytes (byte-array (map #(unchecked-byte (mod % 256)) (range n)))]
    (assert (= (.encodeToString (java.util.Base64/getEncoder) bytes)
               (rb-rich-base64 bytes rb-rich-alphabet true)))))

(def ^:dynamic *rich-observation* nil)
(let [[root] *command-line-args*
      selected (keyword (System/getenv "RICH_FORMAT_ARM"))
      _ (assert (contains? #{:json :rowbinary} selected))
      _ (assert (= :local (storage/arm)))
      _ (native/ensure-loaded!)
      _ (assert (= "26.9.0" (native/chdb-version)))
      spans (otel.research.trace-affinity/group-traces
              (mapv (fn [span]
                      (update span :attributes into
                              (map (fn [i] [(str "extra-" i)
                                            (case (mod i 3) 0 "value" 1 false i)])) (range 12)))
                    (nested-sorted-sdk-shape
                      (with-redefs [fixture/batch-size 5000]
                        (fixture/spans "typed-compact-prefix")))))
      _ (assert (= 625 (count (group-by #(get-in % [:span-context :trace-id]) spans))))
      observations (atom []) phases (atom []) sizes (atom {})
      streams (atom 0) queries (atom 0)
      stream native/chdb-stream-insert-n
      execute durable/execute-owned-and-flush!
      encode-json json-native/write-prefixed-batch-bytes!
      sample #'bench/counter-sample delta #'bench/counter-delta
      observe (fn [phase f]
                (if-not *rich-observation* (f)
                  (let [before (sample) start (System/nanoTime) result (f)
                        end (System/nanoTime) after (sample)]
                    (assert (< (count @phases) 128))
                    (swap! phases conj (assoc *rich-observation* :phase phase
                                             :nanos (- end start) :counters (delta before after)))
                    result)))]
  (with-redefs [native/chdb-stream-insert-n
                (fn [& args] (swap! streams inc) (apply stream args))
                durable/execute-owned-and-flush!
                (fn [& args] (observe :native-and-confirm #(apply execute args)))
                json-native/write-prefixed-batch-bytes!
                (fn [& args] (observe :projection-and-encoding #(apply encode-json args)))]
    (with-open [conn (jdbc/connection
                       (durable/writer-dbspec
                         (merge (storage/options root "writer")
                                {:owner "typed-compact-prefix" :instance "writer-1"
                                 :database "otel" :owned-compact-stream? (= :json selected)
                                 :lease-ttl-ms 900000 :heartbeat-interval-ms 300000
                                 :operations (merge (when (= :rowbinary selected)
                                                      (rb-rich-budget-operations))
                                                    {:json-reader (json-native/load-reader!)})})))]
      (let [{:keys [capability]} (fixture/setup conn)
            _ (assert (= :committed (:status (durable/checkpoint! conn))))
            owner (e/exporter {:connection conn :durable? true :create-schema? false
                               :datetime64-wire :raw-ticks :signals #{:spans}
                               :typed-span-descriptors capability
                               :insert-format :json-compact-each-row
                               :json-backend :native-guarded-byte-batch
                               :owned-statement-output? true :max-insert-bytes 67108864})]
        (try
          (let [initial @(:state owner) plan (:typed-span-vector-plan initial)
                columns (:span-insert-columns initial)
                projector (:vector-projector plan)
                type-map (into {} (map (juxt :name :type))
                               (jdbc/fetch conn "DESCRIBE TABLE otel_traces"))
                types (mapv type-map columns) descriptors (mapv rb-type types)
                prefix (rb-prefix columns types)
                checked! (fn []
                           (let [now @(:state owner)]
                             (assert (#'e/direct-typed-span-layout? now))
                             (assert (identical? plan (:typed-span-vector-plan now)))
                             (assert (= columns (:span-insert-columns now)))))
                row (fn [span] (into (#'e/span-row span nil true) (projector span)))
                binary! (fn []
                          (checked!)
                          (let [bytes (observe :projection-and-encoding
                                               #(rb-encode descriptors spans row 16777216))
                                statement
                                (observe :framing
                                         #(let [sql (str prefix (rb-rich-base64 bytes rb-rich-alphabet true) "'))")
                                                wire (.getBytes sql "UTF-8")]
                                            (assert (< (alength wire) 16777216))
                                            (swap! sizes assoc :rowbinary {:raw-bytes (alength bytes)
                                                                          :sql-bytes (alength wire)})
                                            (or (owned/try-snapshot wire)
                                                (throw (ex-info "Owned RowBinary snapshot declined" {})))))]
                            (checked!)
                            (let [result (durable/execute-owned-and-flush! conn statement)]
                              (assert (contains? #{:committed :reconciled} (:status result)))
                              (swap! queries inc)
                              true)))
                run! (fn [arm]
                       (case arm
                         :json (assert (true? (export/export-spans! owner spans)))
                         :rowbinary
                         (with-bindings {#'e/*timestamp-wire* :raw-ticks
                                         #'e/*json-backend* :native-guarded-byte-batch}
                           (binary!))))]
            (dotimes [_ 3] (run! selected))
            (System/gc)
            (dotimes [i 32]
              (let [arm selected
                    before (sample) start (System/nanoTime)]
                (binding [*rich-observation* {:ordinal i :arm arm}] (run! arm))
                (let [end (System/nanoTime) after (sample)]
                  (swap! observations conj {:ordinal i :arm arm :rows 5000
                                             :nanos (- end start) :counters (delta before after)})
                  (println :rich-format-sample :ordinal i :arm arm :nanos (- end start))
                  (flush))))
            (assert (= (if (= :json selected) 35 0) @streams))
            (assert (= (if (= :rowbinary selected) 35 0) @queries))
            (assert (= {selected 32} (frequencies (map :arm @observations))))
            (assert (= 175000 (:n (jdbc/fetch-one conn "SELECT count() AS n FROM otel_traces"))))
            (spit (str root "/writer.edn")
                  (pr-str {:scope :experimental-format-best-native-path-direct-callback
                           :arm selected
                           :sdk-queue false :source-overlay false :experimental-rowbinary-kernel true
                           :experimental-local-base64-kernel (= :rowbinary selected) :runtime-b64-replaced false
                           :query-budget (when (= :rowbinary selected) 16777216) :rows 175000 :batch-size 5000
                           :samples 32 :warmups 3 :native-streams @streams :binary-queries @queries
                           :sizes @sizes :observations @observations :phases @phases
                           :tail-qualified false :gate-status :writer-passed})))
          (finally (assert (true? (export/shutdown-exporter! owner))))))))
  (println :rich-format-writer :passed))
