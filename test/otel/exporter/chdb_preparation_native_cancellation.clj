(ns otel.exporter.chdb-preparation-native-cancellation
  "Causal native cancellation control, not a performance or general fault gate."
  (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str]
            [db.jdbc-shim :as shim] [jdbc.proto :as proto] [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable] [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.local-posix :as local] [jdbc.chdb.native :as native]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-preparation-adapter-test :as sample]
            [otel.sdk.export :as sdk]))

(defn- check! [condition label]
  (when-not condition
    (throw (ex-info "Native preparation cancellation control failed" {:check label}))))

(defn- await! [signal label]
  (check! (= true (deref signal 10000 ::timeout)) label))

(defn- records []
  (mapv (fn [n]
          (-> sample/span
              (assoc :name (str "cancel-control-" n))
              (assoc-in [:span-context :span-id] (format "%016x" n))
              (update :start-time-unix-nano + n)
              (update :end-time-unix-nano + n))) [1 2]))

(defn- gated-backend [delegate armed entered release events]
  (let [gate! (fn [key]
                (when (and (or (str/starts-with? key "wal/") (str/includes? key "/wal/"))
                           (compare-and-set! armed true false))
                  (swap! events conj :wal-put-entered)
                  (deliver entered true)
                  (await! release :publication-gate-release)
                  (swap! events conj :wal-put-released)))]
    (reify
      backend/ObjectBackend
      (get-bytes [_ key] (backend/get-bytes delegate key))
      (get-with-etag [_ key] (backend/get-with-etag delegate key))
      (put-file-if-absent! [_ key path]
        (gate! key) (backend/put-file-if-absent! delegate key path))
      (put-bytes-if-absent! [_ key bytes]
        (gate! key) (backend/put-bytes-if-absent! delegate key bytes))
      (replace-if-match! [_ key bytes etag] (backend/replace-if-match! delegate key bytes etag))
      (download-to-file! [_ key path] (backend/download-to-file! delegate key path))
      backend/ObjectDigest
      (digest-object [_ key] (backend/digest-object delegate key)))))

(defn- options [root phase store]
  {:namespace-backend store :object-id "cancel-control"
   :scratch-parent (str root "/scratch-" phase)})

(defn- writer! [root ownership]
  (check! (contains? #{"owned" "borrowed"} ownership) :ownership)
  (doseq [directory ["objects" "scratch-writer"]] (.mkdirs (io/file root directory)))
  (let [armed (atom false) entered (promise) release (promise) events (atom [])
        first-prepared (promise) second-queued (promise) second-prepared (promise)
        fenced (promise) result (promise) prepared-count (atom 0) submitted-count (atom 0)
        store (gated-backend (local/local-backend (str root "/objects")) armed entered release events)
        spec (durable/writer-dbspec (assoc (options root "writer" store)
                                         :owner "cancel-control" :instance ownership :database "otel"))
        owned? (= ownership "owned") borrowed (when-not owned? (jdbc/connection spec))
        target (exporter/exporter
                 (assoc {:durable? true :signals #{:spans} :datetime64-wire :raw-ticks
                         :json-backend :native-guarded-byte-batch
                         :insert-format :json-compact-each-row
                         :owned-statement-output? true :experimental-span-codec? true}
                        (if owned? :db-spec :connection) (if owned? spec borrowed)))
        connection (:connection target)
        handle (:handle (shim/driver-context (proto/connection connection) :chdb-durable))
        publisher @(:thread (:worker handle))
        owner (atom nil) closer (atom nil) spans (records)]
    (try
      (check! (= "26.9.0" (native/chdb-version)) :native-version)
      ;; Independent expected-table row construction is committed before the
      ;; gate is armed. Only A may appear in the fresh reader after cancellation.
      (jdbc/execute! connection "CREATE TABLE otel_traces_expected AS otel_traces")
      (jdbc/execute! connection
        (with-bindings {#'exporter/*timestamp-wire* :raw-ticks #'exporter/*json-backend* :configured}
          (str (#'exporter/timestamp-insert-query "insert into otel_traces_expected")
               " FORMAT JSONEachRow\n"
               (#'exporter/json-each-row-payload [(#'exporter/span-row (first spans) nil)]))))
      (check! (contains? #{:committed :reconciled} (:status (durable/checkpoint! connection)))
              :schema-and-oracle-confirmed)
      (let [config (exporter/experimental-span-batch-options target
                     {:max-export-batch-size 1 :max-queue-size 8 :schedule-delay-ms 60000})
            adapter (:experimental-span-preparation config)
            wrapped
            (assoc adapter
              :prepare! (fn [cap batch]
                          (let [n (inc @prepared-count)]
                            ;; Force B's actual materialization to begin only
                            ;; AFTER the publisher has entered A's real WAL
                            ;; put. Admission alone does not prove overlap.
                            (when (= n 2) (await! entered :publisher-entered-before-second-preparation))
                            (let [ok ((:prepare! adapter) cap batch)]
                              (check! (true? ok) :real-preparation-completed)
                              (swap! prepared-count inc)
                              (swap! events conj [:prepared n])
                              (case n
                                1 (do (deliver first-prepared true)
                                      (await! second-queued :second-queued-before-first-submit))
                                2 (deliver second-prepared true))
                              ok)))
              :submit! (fn [cap]
                         (let [ok ((:submit! adapter) cap)]
                           (when (true? ok) (swap! submitted-count inc))
                           ok))
              :fence! (fn []
                        (let [ok ((:fence! adapter))]
                          (check! (true? ok) :real-adapter-fence)
                          (swap! events conj :publication-fenced)
                          (deliver fenced true)
                          ok)))]
        (reset! armed true)
        (reset! owner (sdk/batch-processor target (assoc config :experimental-span-preparation wrapped)))
        (sdk/on-end @owner (first spans))
        (await! first-prepared :first-prepared)
        (sdk/on-end @owner (second spans))
        (deliver second-queued true)
        (await! entered :real-wal-publication-held)
        (await! second-prepared :second-prepared-while-first-publishing)
        (check! (= 1 @submitted-count) :only-first-admitted-before-cancellation)
        (check! (zero? (:exported-span-count (sdk/batch-processor-stats @owner)))
                :held-publication-is-not-success)
        (reset! closer
          (Thread. #(deliver result (try {:value (sdk/shutdown! @owner)}
                                        (catch Throwable error {:throwable error})))))
        (.start @closer)
        (await! fenced :shutdown-fences-before-publication-release)
        (check! (not (realized? result)) :shutdown-cannot-finish-held-request)
        (deliver release true)
        (let [outcome (deref result 10000 ::timeout)]
          (check! (= {:value false} outcome) :cancellation-is-not-success))
        (.join @closer 3000)
        (check! (not (.isAlive @closer)) :shutdown-caller-finished)
        (check! (not (.isAlive (:worker @owner))) :sdk-worker-finished)
        (check! (= 2 @prepared-count) :both-batches-prepared)
        (check! (= 1 @submitted-count) :second-never-writer-admitted)
        (let [stats (sdk/batch-processor-stats @owner)]
          (check! (= {:queue-size 0 :attempted-span-count 2 :exported-span-count 1
                      :failed-span-count 1 :dropped-count 0} stats) :exact-sdk-accounting)
          (check! (zero? (:in-flight @(:state target))) :exporter-admissions-retired)
          (if owned?
            (check! (not (.isAlive publisher)) :owned-publisher-finished)
            (do
              (check! (.isAlive publisher) :borrowed-publisher-remains-owned-by-consumer)
              (check! (= 1 (:n (jdbc/fetch-one connection "SELECT count() AS n FROM otel_traces")))
                      :borrowed-connection-remains-usable)
              (.close connection)
              (check! (not (.isAlive publisher)) :consumer-close-finishes-publisher)))
          (spit (str root "/writer.edn")
            (pr-str {:ownership ownership :prepared 2 :submitted 1 :stats stats
                     :shutdown-result false :publisher-terminated true :events @events}))))
      (println :native-cancellation-writer-confirmed ownership)
      (finally
        ;; Unblock the synthetic gates on failure. Never manually close a
        ;; connection while this fixture still observes an active SDK worker.
        (deliver second-queued true) (deliver release true)
        (when @owner (try (sdk/shutdown! @owner) (catch Throwable _ nil)))
        (when (and (or (nil? @owner) (not (.isAlive (:worker @owner))))
                   (zero? (:in-flight @(:state target))))
          (if owned? (sdk/shutdown-exporter! target) (.close borrowed)))))))

(defn- reader! [root]
  (.mkdirs (io/file root "scratch-reader"))
  (let [store (local/local-backend (str root "/objects"))]
    (with-open [connection (jdbc/connection (durable/snapshot-dbspec (options root "reader" store)))]
      (let [select (fn [table] (str "SELECT toUnixTimestamp64Nano(Timestamp) AS timestamp_ns, * FROM "
                                    table " ORDER BY SpanId"))
            actual (vec (jdbc/fetch connection (select "otel_traces")))
            expected (vec (jdbc/fetch connection (select "otel_traces_expected")))
            writer (edn/read-string (slurp (str root "/writer.edn")))]
        (check! (= 1 (count actual)) :only-admitted-first-batch-recovered)
        (check! (= expected actual) :independent-full-row-parity)
        (check! (= (:start-time-unix-nano (first (records))) (:timestamp_ns (first actual)))
                :exact-nanoseconds)
        (check! (and (= 1 (:submitted writer)) (:publisher-terminated writer)) :writer-receipt)
        (spit (str root "/reader.edn")
          (pr-str {:rows 1 :full-row-parity true :exact-nanoseconds true :cancelled-batch-absent true})))))
  (println :native-cancellation-reader-confirmed))

(defn -main [phase root & [ownership]]
  (case phase "writer" (writer! root ownership) "reader" (reader! root)
    (throw (ex-info "writer or reader required" {:phase phase}))))
