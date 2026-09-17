(ns otel.exporter.chdb-durable-typed-native-test
  "Explicit two-process Durable typed acceptance; never a canonical runner replacement."
  (:require [db.jdbc] [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.local-posix :as local]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.native :as native]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.sdk.export :as export] [otel.sdk.logs :as logs]))

(def last-check (atom :not-started))
(def observed-checks (atom 0))
(defn- check! [label expected actual]
  (reset! last-check label)
  (swap! observed-checks inc)
  (when-not (= expected actual)
    ;; Never retain actual telemetry, SQL, paths or arbitrary exception data.
    (throw (ex-info "Durable typed native assertion failed" {:assertion label})))
  (println :ok label))

(defn- selector [signal]
  {:dataset-id "durable-native" :application-id (name signal)
   :lineage "native-v1" :version 1})

(defn- table [signal] (if (= signal :spans) "otel_traces" "otel_logs"))
(defn- observe [connection signal]
  [{:signal signal :table (table signal)
    :columns (into {} (map (juxt :name :type))
                   (jdbc/fetch connection (str "DESCRIBE TABLE " (table signal))))}])

(defn- approved [signal]
  (manifest/compile-manifest
    (assoc (selector signal)
           :fragments [{:schema manifest/reviewed-fragment-schema
                        :authority :advice :source "advice/native-test.edn"
                        :entries (mapv (fn [[key type]]
                                         {:signal signal :table (table signal)
                                          :location (if (= signal :spans) :span-attributes :log-attributes)
                                          :key key :type type})
                                       [["flag" :boolean] ["count" :int64]])}])))

(def ticks [0 1 1700000000123456789 0])
(def inputs [["a-zero" {"flag" false "count" 0}]
             ["b-min" {"flag" true "count" -9223372036854775808}]
             ["c-max" {"count" 9223372036854775807}]
             ["d-invalid" {"count" 9223372036854775808N}]])

(defn- span-record [index]
  (let [[name attributes] (nth inputs index) tick (nth ticks index)]
    {:name name :kind :internal :start-time-unix-nano tick :end-time-unix-nano (inc tick)
     :span-context {:trace-id "11111111111111111111111111111111" :span-id "2222222222222222"}
     :resource {:attributes {}} :scope {:name "native"} :attributes attributes
     :events (if (zero? index) (mapv #(hash-map :name "tick" :timestamp-unix-nano %) (take 3 ticks)) [])
     :links [] :status {:code :unset}}))

(defn- log-record [index]
  (let [[name attributes] (nth inputs index) tick (nth ticks index)]
    {:timestamp-unix-nano (if (= index 2) 0 tick)
     :observed-time-unix-nano (if (= index 2) tick 0)
     :body name :severity-text "INFO" :severity-number 9
     :resource {:attributes {}} :scope {:name "native"} :attributes attributes}))

(defn- counts [connection]
  (mapv #(-> (jdbc/fetch-one connection (str "SELECT count() AS n FROM " %)) :n)
        ["otel_traces" "otel_logs"]))

(defn- verify! [connection capability signal]
  (let [fields ((if (= signal :spans) projection/confirmed-span-fields projection/confirmed-log-fields)
                 capability connection)
        physical (fn [key] (:physical (first (filter #(= key (:key %)) fields))))
        c (physical "count") f (physical "flag")
        rows (jdbc/fetch connection
                         (str "SELECT " (if (= signal :spans) "SpanName" "Body") " AS label, "
                              (if (= signal :spans) "SpanAttributes" "LogAttributes") " AS generic, "
                              "toString(toUnixTimestamp64Nano(Timestamp)) AS ticks, "
                              "toString(`" (:value-column c) "`) AS value, "
                              "toTypeName(`" (:value-column c) "`) AS physical_type, "
                              "`" (:status-column c) "` AS status, "
                              "`" (:value-column f) "` AS flag, "
                              "`" (:status-column f) "` AS flag_status FROM " (table signal) " ORDER BY label"))]
    (check! :labels (mapv first inputs) (mapv :label rows))
    (check! :timestamp-nanos (mapv str ticks) (mapv :ticks rows))
    (check! :typed-counts ["0" "-9223372036854775808" "9223372036854775807" "0"] (mapv :value rows))
    (check! :count-types (vec (repeat 4 "Int64")) (mapv :physical_type rows))
    (check! :count-statuses [3 3 3 4] (mapv :status rows))
    (check! :flags [[false 3] [true 3] [false 1] [false 1]] (mapv #(vector (:flag %) (:flag_status %)) rows))
    (check! :generic-maps [{"flag" "false" "count" "0"}
                           {"flag" "true" "count" "-9223372036854775808"}
                           {"count" "9223372036854775807"} {"count" "9223372036854775808N"}]
            (mapv :generic rows))))

(defn- wait-file! [path timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond (.isFile (java.io.File. path)) true
            (>= (System/nanoTime) deadline)
            (throw (ex-info "Bounded reader handshake expired" {:assertion :handshake-timeout}))
            :else (do (Thread/sleep 25) (recur))))))

(defn run! [phase root]
  (native/ensure-loaded!)
  (check! :actual-package "26.7.3" (native/chdb-version))
  (let [namespace (local/local-backend (str root "/objects"))
        telemetry (backend/object-backend namespace "telemetry")
        catalog (backend/object-backend namespace "typed-catalog")
        snapshot #(control/read-head-read-only! telemetry)
        options {:namespace-backend namespace :object-id "telemetry"
                 :scratch-parent (str root "/scratch-" phase)}]
    (case phase
      "writer"
      (with-open [connection (jdbc/connection
                               (durable/writer-dbspec
                                 (merge options {:owner "typed-native" :instance "writer-1" :database "otel"
                                                 :lease-ttl-ms 900000 :heartbeat-interval-ms 300000})))]
        (schema/ensure-schema! connection)
        (let [install (fn [signal]
                        (let [result (installer/install-approved! catalog (approved signal)
                                        {:target connection :observe-columns #(observe connection signal)
                                         :execute-ddl! #(jdbc/execute! connection %)})]
                          (check! :installation-active :active (:status result))
                          (:descriptor-set result)))
              spans (install :spans) records (install :logs)]
          (check! :schema-checkpoint :committed (:status (durable/checkpoint! connection)))
          (let [primary (atom nil)
                writer (exporter/exporter {:connection connection :durable? true :create-schema? false
                                          :signals #{:spans :logs} :typed-span-descriptors spans
                                          :typed-log-descriptors records})]
            (try
              (check! :spans-accepted true (export/export-spans! writer (mapv span-record (range 4))))
              (check! :logs-accepted true (logs/export-logs! writer (mapv log-record (range 4))))
              (check! :writer-counts [4 4] (counts connection))
              (let [head (:head (snapshot))]
                (check! :base-present true (some? (get-in head ["manifest" "base"])))
                (check! :wal-present true (boolean (seq (get-in head ["manifest" "wal"])))))
              (doseq [invalid [-1 9223372036854775808N]]
                (let [before (snapshot) before-counts (counts connection)]
                  (check! :later-bad-span false (export/export-spans! writer [(span-record 0) (assoc (span-record 1) :start-time-unix-nano invalid)]))
                  (check! :later-bad-observed false (logs/export-logs! writer [(log-record 0) (assoc (log-record 1) :timestamp-unix-nano 0 :observed-time-unix-nano invalid)]))
                  (check! :later-bad-event false (export/export-spans! writer [(span-record 0) (assoc (span-record 1) :events [{:name "invalid" :timestamp-unix-nano invalid}])]))
                  (check! :invalid-counts-unchanged before-counts (counts connection))
                  (check! :invalid-head-and-etag-unchanged before (snapshot))))
              (spit (str root "/wal-ready") "ready\n")
              (println :wal-ready)
              (wait-file! (str root "/reader-done") 120000)
              (catch Throwable error (reset! primary error) (throw error))
              (finally
                ;; Independent shutdown attempts; an exception cannot skip the other facade.
                (let [a (try (export/shutdown-exporter! writer) (catch Throwable _ false))
                      b (try (logs/shutdown-log-exporter! writer) (catch Throwable _ false))]
                  (if @primary
                    (println :secondary-cleanup-confirmed (= [true true] [a b]))
                    (check! :signal-shutdowns [true true] [a b]))))))))
      "reader"
      (do
       (with-open [connection (jdbc/connection (durable/snapshot-dbspec options))]
        (let [head (:head (snapshot))]
          (check! :reader-base-present true (some? (get-in head ["manifest" "base"])))
          (check! :reader-wal-present true (boolean (seq (get-in head ["manifest" "wal"])))))
        (check! :reader-counts [4 4] (counts connection))
        (doseq [signal [:spans :logs]]
          (let [result (installer/acquire-active! catalog (selector signal)
                         {:target connection :observe-columns #(observe connection signal)})]
            (check! :reacquisition-active :active (:status result))
            (verify! connection (:descriptor-set result) signal)))
        (check! :event-nanoseconds ["0" "1" "1700000000123456789"]
                (:ticks (jdbc/fetch-one connection "SELECT arrayMap(x -> toString(toUnixTimestamp64Nano(x)), `Events.Timestamp`) AS ticks FROM otel_traces WHERE notEmpty(`Events.Timestamp`)"))))
        ;; Acknowledge only after the reader connection has retired.
        (spit (str root "/reader-done") "done\n")
        (println :reader-verified)))))

(defn -main [phase root]
  (try (run! phase root)
       (println :observed-checks @observed-checks)
       (catch Throwable _
         ;; Values are assigned exclusively by internal fixed-label checks.
         (println :durable-native-failed :last-check @last-check
                  :observed-checks @observed-checks)
         (System/exit 1))))
