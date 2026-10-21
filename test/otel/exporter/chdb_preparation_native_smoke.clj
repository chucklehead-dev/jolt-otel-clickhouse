(ns otel.exporter.chdb-preparation-native-smoke
  "Small two-process functional smoke. No timing or performance qualification."
  (:require [clojure.edn :as edn] [clojure.java.io :as io]
            [db.jdbc-shim :as shim] [jdbc.proto :as proto] [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable] [jdbc.chdb.durable.local-posix :as local]
            [jdbc.chdb.native :as native] [otel.exporter.chdb :as e]
            [otel.exporter.chdb-preparation-adapter-test :as sample]
            [otel.sdk.export :as sdk]))

(defn- check! [condition label]
  (when-not condition (throw (ex-info "Native preparation smoke failed" {:check label}))))

(defn- options [root phase]
  {:namespace-backend (local/local-backend (str root "/objects"))
   :object-id "prepared-writer" :scratch-parent (str root "/scratch-" phase)})

(defn- spans []
  (mapv (fn [n] (-> sample/span
                     (assoc-in [:span-context :span-id] (format "%016x" n))
                     (update :start-time-unix-nano + n)
                     (update :end-time-unix-nano + n))) (range 1 5)))

(defn- writer! [root ownership]
  (doseq [directory ["objects" "scratch-writer"]]
    (.mkdirs (io/file root directory)))
  (let [owned? (= ownership "owned")
        spec (durable/writer-dbspec (assoc (options root "writer")
                                          :owner "prepared-smoke" :instance ownership :database "otel"))
        borrowed (when-not owned? (jdbc/connection spec))
        target (e/exporter
                 (assoc {:durable? true
                         :signals #{:spans}
                         :json-backend :native-guarded-byte-batch
                         :insert-format :json-compact-each-row
                         :owned-statement-output? true
                         :experimental-span-codec? true
                         :datetime64-wire :raw-ticks}
                        (if owned? :db-spec :connection)
                        (if owned? spec borrowed)))
        connection (:connection target)
        handle (:handle (shim/driver-context (proto/connection connection) :chdb-durable))
        publisher @(:thread (:worker handle))
        records (spans)
        owner (atom nil)]
    (try
      (check! (= "26.9.0" (native/chdb-version)) :exact-native-version)
      ;; Independent baseline row-map/data.json materialization and physical
      ;; table coercion, not the experimental compact encoder's own output.
      (jdbc/execute! connection "CREATE TABLE otel_traces_expected AS otel_traces")
      (let [sql (with-bindings {#'e/*timestamp-wire* :raw-ticks #'e/*json-backend* :configured}
                  (str (#'e/timestamp-insert-query "insert into otel_traces_expected")
                       " FORMAT JSONEachRow\n"
                       (#'e/json-each-row-payload (mapv #(#'e/span-row % nil) records))))]
        (jdbc/execute! connection sql))
      (check! (contains? #{:committed :reconciled} (:status (durable/checkpoint! connection)))
              :expected-table-checkpoint)
      (reset! owner (sdk/batch-processor target
                      (e/experimental-span-batch-options target
                        {:max-export-batch-size 1 :max-queue-size 16 :schedule-delay-ms 60000})))
      (doseq [span records] (sdk/on-end @owner span))
      ;; Do not force work onto the caller. Observe actual background completion
      ;; before using force-flush as the final retirement barrier.
      (let [deadline (+ (System/nanoTime) 30000000000)]
        (loop []
          (let [stats (sdk/batch-processor-stats @owner)]
            (check! (zero? (:failed-span-count stats)) :no-sdk-failures)
            (when-not (= 4 (:exported-span-count stats))
              (check! (< (System/nanoTime) deadline) :background-completion-deadline)
              (Thread/sleep 1) (recur)))))
      (check! (true? (sdk/force-flush! @owner)) :confirmed-force-flush)
      (check! (true? (sdk/shutdown! @owner)) :sdk-shutdown)
      (check! (zero? (:in-flight @(:state target))) :exporter-admissions-retired)
      (if owned?
        (check! (not (.isAlive publisher)) :owned-publisher-terminated)
        (do
          (check! (.isAlive publisher) :borrowed-publisher-not-closed-by-exporter)
          (check! (= 4 (:n (first (jdbc/fetch connection "SELECT count() AS n FROM otel_traces"))))
                  :borrowed-still-queryable)
          (.close connection)
          (check! (not (.isAlive publisher)) :consumer-close-terminated-publisher)))
      (spit (str root "/writer.edn") (pr-str {:ownership ownership :rows 4
                                               :stats (sdk/batch-processor-stats @owner)
                                               :publisher-terminated true}))
      (println :native-preparation-writer-confirmed ownership)
      (finally
        (when @owner (try (sdk/shutdown! @owner) (catch Throwable _ nil)))
        (when borrowed (.close borrowed))
        (when owned? (sdk/shutdown-exporter! target))))))

(defn- reader! [root]
  (.mkdirs (io/file root "scratch-reader"))
  (with-open [connection (jdbc/connection (durable/snapshot-dbspec (options root "reader")))]
    (let [selection (fn [table] (str "SELECT toUnixTimestamp64Nano(Timestamp) AS timestamp_ns, * FROM "
                                     table " ORDER BY SpanId"))
          actual (vec (jdbc/fetch connection (selection "otel_traces")))
          expected (vec (jdbc/fetch connection (selection "otel_traces_expected")))
          writer (edn/read-string (slurp (str root "/writer.edn")))]
      (check! (= 4 (count actual)) :fresh-reader-row-count)
      (check! (= expected actual) :independent-full-row-parity)
      (check! (= (mapv :start-time-unix-nano (spans)) (mapv :timestamp_ns actual)) :exact-nanoseconds)
      (check! (:publisher-terminated writer) :writer-termination-receipt)
      (spit (str root "/reader.edn") (pr-str {:rows 4 :full-row-parity true :exact-nanoseconds true}))))
  (println :native-preparation-reader-confirmed))

(defn -main [phase root & [ownership]]
  (case phase "writer" (writer! root ownership) "reader" (reader! root)
    (throw (ex-info "writer or reader required" {:phase phase}))))
