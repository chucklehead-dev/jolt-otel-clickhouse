(ns otel.exporter.chdb-test
  (:require [db.jdbc]
            [jdbc.chdb]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb-property-test :as property]
            [otel.logs :as logs]
            [otel.metrics :as metrics]
            [otel.resource :as resource]
            [otel.sdk :as sdk]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as sdk-logs]
            [otel.sdk.metrics :as sdk-metrics]
            [otel.trace :as trace]))

(def failures (atom 0))

(defn- check [label expected actual]
  (if (= expected actual)
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "- expected" (pr-str expected) "got" (pr-str actual)))))

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(defn- delete-tree! [path]
  (let [root (java.io.File. path)]
    (when (.exists root)
      (doseq [file (reverse (file-seq root))]
        (.delete file)))))

(defn- run-migration-checks []
  (println "versioned chDB schema migrations")
  (with-open [conn (jdbc/connection "chdb::memory:")]
    (schema/migrate! conn)
    (schema/migrate! conn)
    (let [applied (jdbc/fetch conn
                              "select Version, Name, Checksum
                                 from otel_schema_migrations order by Version")]
      (check "fresh database records migration v1" [1] (mapv :version applied))
      (check "migration name is durable" ["initial-otel-tables"]
             (mapv :name applied))
      (check "migration checksum is SHA-256" [64]
             (mapv #(count (:checksum %)) applied))))

  (let [placeholder (java.io.File/createTempFile "jolt-otel-migrations-" ".chdb")
        path (.getAbsolutePath placeholder)
        db-spec (str "chdb:" path)]
    (.delete placeholder)
    (try
      (with-open [conn (jdbc/connection db-spec)]
        (schema/migrate! conn))
      (with-open [conn (jdbc/connection db-spec)]
        (schema/migrate! conn)
        (check "persistent database reopen keeps one migration record" 1
               (:n (jdbc/fetch-one conn
                                    "select count() as n from otel_schema_migrations"))))
      (finally
        (delete-tree! path))))

  ;; chDB has no DDL transaction. Simulate a crash/failure after v1's first
  ;; statement and prove the unrecorded, idempotent migration can be retried.
  (with-open [conn (jdbc/connection "chdb::memory:")]
    (let [execute! jdbc/execute!
          failed? (atom false)
          failure
          (with-redefs [jdbc/execute!
                        (fn
                          ([connection statement]
                           (if (and (= statement schema/logs-ddl)
                                    (compare-and-set! failed? false true))
                             (throw (ex-info "injected migration failure" {}))
                             (execute! connection statement)))
                          ([connection statement options]
                           (execute! connection statement options)))]
            (thrown-data #(schema/migrate! conn)))]
      (check "failed migration identifies statement phase" :statement (:phase failure))
      (check "failed migration identifies version" 1 (:version failure))
      (check "failed migration remains unrecorded" 0
             (:n (jdbc/fetch-one conn
                                  "select count() as n from otel_schema_migrations")))
      (schema/migrate! conn)
      (check "retry records migration exactly once" 1
             (:n (jdbc/fetch-one conn
                                  "select count() as n from otel_schema_migrations")))
      (check "retry completed the interrupted table" 1
             (:n (jdbc/fetch-one conn
                                  "select count() as n from system.tables
                                     where database=currentDatabase() and name='otel_logs'")))))

  (with-open [conn (jdbc/connection "chdb::memory:")]
    (schema/migrate! conn)
    (jdbc/execute! conn
                   "alter table otel_schema_migrations
                      update Checksum=repeat('0', 64) where Version=1
                      settings mutations_sync=2")
    (let [failure (thrown-data #(schema/migrate! conn))]
      (check "checksum drift fails closed" :otel.exporter.chdb.schema/migration-drift
             (:type failure))
      (check "checksum drift identifies version" 1 (:version failure)))))

(defn -main [& _]
  (reset! failures 0)
  (run-migration-checks)
  (println "embedded chDB OTel exporter")
  (with-open [conn (jdbc/connection "chdb::memory:")]
    (let [exporter (chdb-export/exporter {:connection conn})
          handle (sdk/init! {:service-name "ring-demo"
                             :exporter exporter
                             :processor :batch
                             :metrics? false
                             :logs? true
                             :bridge-logging? false})]
      (try
        (trace/with-span [outer (sdk/tracer "demo.http") "GET /outbound"
                          {:kind :server :attributes {:http.route "/outbound"}}]
          (logs/emit! (sdk/logger "demo.http")
                      {:body "calling upstream" :severity :info
                       :attributes {:http.method "GET"}})
          (trace/with-span [inner (sdk/tracer "demo.client") "GET example"
                            {:kind :client}]
            (trace/set-status! inner :ok)))
        (let [r (resource/resource {:service.name "ring-demo"})
              provider (sdk-metrics/meter-provider {:resource r})
              meter (sdk-metrics/get-meter provider {:name "demo.metrics"})]
          (metrics/add! (metrics/counter meter "requests") 2 {:route "/work"})
          (metrics/set-value! (metrics/gauge meter "queue.depth") 3)
          (metrics/record! (metrics/histogram meter "latency" {:boundaries [10.0 100.0]}) 42)
          (check "metric export call succeeds" true
                 (export/export-metrics! exporter r (sdk-metrics/collect! provider))))
        ;; sdk/shutdown! reaches the log and span pipelines separately. Both
        ;; batch queues must drain even after the first signal shuts down.
        (sdk/shutdown! handle)
        (let [spans (jdbc/fetch conn
                                "select TraceId, SpanId, ParentSpanId, SpanName, ServiceName, SpanAttributes from otel_traces order by Timestamp")
              log (jdbc/fetch-one conn
                                  "select TraceId, SpanId, Body, ServiceName, SeverityText from otel_logs")]
          (check "parent and child spans persisted" 2 (count spans))
          (check "ClickStack service column" #{"ring-demo"}
                 (set (map :servicename spans)))
          (check "child points at parent span" true
                 (= (:spanid (first spans)) (:parentspanid (second spans))))
          (check "log body persisted" "calling upstream" (:body log))
          (check "log/span trace correlation" (:traceid (first spans)) (:traceid log))
          (check "severity uses ClickStack column" "INFO" (:severitytext log)))
        (chdb-export/exporter {:connection conn})
        (check "exporter reopen does not duplicate migration history" 1
               (:n (jdbc/fetch-one conn
                                    "select count() as n from otel_schema_migrations")))
        (check "ClickStack gauge table" 1
               (:n (jdbc/fetch-one conn "select count() as n from otel_metrics_gauge")))
        (check "ClickStack sum table" 1
               (:n (jdbc/fetch-one conn "select count() as n from otel_metrics_sum")))
        (check "ClickStack histogram table" 1
               (:n (jdbc/fetch-one conn "select count() as n from otel_metrics_histogram")))
        (finally (sdk/shutdown! handle)))))
  (let [exporter (chdb-export/exporter {:db-spec "chdb::memory:"
                                        :signals #{:spans}})]
    (check "undeclared signal export is rejected" false
           (sdk-logs/export-logs! exporter []))
    (check "undeclared signal failure is diagnosable" :logs
           (:signal (ex-data (chdb-export/last-error exporter))))
    (check "declared signal still owns shutdown" true
           (export/shutdown-exporter! exporter)))
  (doseq [{:keys [label result]} (property/run-properties!)]
    (println "  hegel" label "seed" (:seed result))
    (check (str "Hegel " label) true (:passed? result))
    (check (str "Hegel " label " is deterministic") false (:flaky? result)))
  (if (zero? @failures)
    (println "all checks passed")
    (throw (ex-info (str @failures " checks failed") {:failures @failures}))))
