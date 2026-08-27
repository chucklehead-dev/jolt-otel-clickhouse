(ns otel.exporter.chdb-test
  (:require [db.jdbc]
            [clojure.string :as str]
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

(defn- normalize-clickstack-type [type]
  (-> type
      (str/replace #"LowCardinality\(([^()]*)\)" "$1")
      (str/replace #"\s+" "")))

(def clickstack-trace-types
  {"Timestamp" "DateTime64(9)"
   "TraceId" "String" "SpanId" "String" "ParentSpanId" "String"
   "TraceState" "String" "SpanName" "String" "SpanKind" "String"
   "ServiceName" "String" "ResourceAttributes" "Map(String,String)"
   "ScopeName" "String" "ScopeVersion" "String"
   "SpanAttributes" "Map(String,String)" "Duration" "UInt64"
   "StatusCode" "String" "StatusMessage" "String"
   "Events.Timestamp" "Array(DateTime64(9))"
   "Events.Name" "Array(String)"
   "Events.Attributes" "Array(Map(String,String))"
   "Links.TraceId" "Array(String)" "Links.SpanId" "Array(String)"
   "Links.TraceState" "Array(String)"
   "Links.Attributes" "Array(Map(String,String))"})

(defn- run-clickstack-trace-schema-checks [conn]
  (let [described (jdbc/fetch conn "describe table otel_traces")
        actual (into {} (map (fn [{:keys [name type]}]
                               [name (normalize-clickstack-type type)]))
                     described)
        expected (mapv (fn [column]
                         [column (get clickstack-trace-types column)])
                       schema/clickstack-trace-insert-columns)]
    (check "all official ClickStack trace insert columns exist"
           schema/clickstack-trace-insert-columns
           (filterv #(contains? actual %)
                    schema/clickstack-trace-insert-columns))
    (check "normalized ClickStack trace insert types are compatible"
           expected
           (mapv (fn [column] [column (get actual column)])
                 schema/clickstack-trace-insert-columns))))

(defn- run-migration-checks []
  (println "versioned chDB schema migrations")
  (with-open [conn (jdbc/connection "chdb::memory:")]
    (schema/migrate! conn)
    (schema/migrate! conn)
    (let [applied (jdbc/fetch conn
                              "select Version, Name, Checksum
                                 from otel_schema_migrations order by Version")]
      (check "fresh database records migrations v1 and v2" [1 2] (mapv :version applied))
      (check "migration names are durable"
             ["initial-otel-tables" "clickstack-trace-nested-and-lookup"]
             (mapv :name applied))
      (check "migration checksums are SHA-256" [64 64]
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
        (check "persistent database reopen keeps both migration records" 2
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
      (check "retry records both migrations exactly once" 2
             (:n (jdbc/fetch-one conn
                                  "select count() as n from otel_schema_migrations")))
      (check "retry completed the interrupted table" 1
             (:n (jdbc/fetch-one conn
                                  "select count() as n from system.tables
                                     where database=currentDatabase() and name='otel_logs'")))))

  ;; A partially applied v2 must leave the immutable v1 history intact and
  ;; safely resume its IF NOT EXISTS ALTER/CREATE statements.
  (with-open [conn (jdbc/connection "chdb::memory:")]
    (let [execute! jdbc/execute!
          failed? (atom false)
          failure
          (with-redefs [jdbc/execute!
                        (fn
                          ([connection statement]
                           (if (and (= statement schema/trace-links-span-id-ddl)
                                    (compare-and-set! failed? false true))
                             (throw (ex-info "injected v2 migration failure" {}))
                             (execute! connection statement)))
                          ([connection statement options]
                           (execute! connection statement options)))]
            (thrown-data #(schema/migrate! conn)))]
      (check "partial v2 failure identifies version" 2 (:version failure))
      (check "partial v2 leaves only v1 recorded" [1]
             (mapv :version
                   (jdbc/fetch conn
                               "select Version from otel_schema_migrations order by Version")))
      (schema/migrate! conn)
      (check "partial v2 retry records v2 once" [1 2]
             (mapv :version
                   (jdbc/fetch conn
                               "select Version from otel_schema_migrations order by Version")))
      (run-clickstack-trace-schema-checks conn)))

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

(defn- run-logical-database-checks []
  (println "logical database namespace")
  (let [base {:vendor "chdb" :name ":memory:"}
        project (jdbc/connection (assoc base :database "project"))
        exporter (chdb-export/exporter
                  {:db-spec (assoc base :database "otel")
                   :signals #{:spans}})]
    (try
      ;; These deliberately collide with exporter-owned names. Their schemas
      ;; are application data, not an OTel migration registry or trace table.
      (jdbc/execute! project
                     "create table otel_traces (ProjectValue String) engine=Memory")
      (jdbc/execute! project
                     "create table otel_schema_migrations (ProjectValue String) engine=Memory")
      (jdbc/execute! project
                     ["insert into otel_traces values (?)" "project-trace"])
      (jdbc/execute! project
                     ["insert into otel_schema_migrations values (?)" "project-history"])
      (check "exporter-owned dbspec selects logical OTel database" "otel"
             (:database (jdbc/fetch-one (:connection exporter)
                                        "select currentDatabase() database")))
      (check "OTel migration history lives in selected database" 2
             (:n (jdbc/fetch-one (:connection exporter)
                                 "select count() as n from otel_schema_migrations")))
      (check "OTel tables live in selected database" 5
             (:n (jdbc/fetch-one
                  (:connection exporter)
                  "select count() as n from system.tables
                     where database=currentDatabase()
                       and name in ('otel_traces', 'otel_logs',
                                    'otel_metrics_gauge', 'otel_metrics_sum',
                                    'otel_metrics_histogram')")))
      (check "same-named project trace table remains isolated" "project-trace"
             (:projectvalue (jdbc/fetch-one project
                                            "select ProjectValue from otel_traces")))
      (check "same-named project history table remains isolated" "project-history"
             (:projectvalue
              (jdbc/fetch-one project
                              "select ProjectValue from otel_schema_migrations")))
      (finally
        (export/shutdown-exporter! exporter)
        (.close project))))

  (with-open [application-otel
              (jdbc/connection {:vendor "chdb" :name ":memory:"
                                :database "application_otel"})]
    (let [exporter (chdb-export/exporter {:connection application-otel})]
      (check "application-owned connection keeps its selected database"
             "application_otel"
             (:database (jdbc/fetch-one application-otel
                                        "select currentDatabase() database")))
      (check "application-owned migration history uses selected database" 2
             (:n (jdbc/fetch-one application-otel
                                 "select count() as n from otel_schema_migrations")))
      ;; Shared ownership remains unchanged: exporter shutdown must not close
      ;; the application's connection.
      (export/shutdown-exporter! exporter)
      (check "application connection remains usable after exporter shutdown" 1
             (:n (jdbc/fetch-one application-otel "select 1 as n")))))

  (with-open [default-conn (jdbc/connection "chdb::memory:")]
    (chdb-export/exporter {:connection default-conn})
    (check "default dbspec behavior remains compatible" "default"
           (:database (jdbc/fetch-one default-conn
                                      "select currentDatabase() database")))
    (check "default database still receives migration history" 2
           (:n (jdbc/fetch-one default-conn
                               "select count() as n from otel_schema_migrations")))))

(defn -main [& _]
  (reset! failures 0)
  (run-migration-checks)
  (run-logical-database-checks)
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
        (run-clickstack-trace-schema-checks conn)
        (let [linked (trace/span-context
                      {:trace-id "11111111111111111111111111111111"
                       :span-id "2222222222222222"
                       :trace-state [["vendor" "state"]]
                       :sampled? true})]
          (trace/with-span [outer (sdk/tracer "demo.http") "GET /outbound"
                            {:kind :server :attributes {:http.route "/outbound"}}]
            (trace/add-event! outer "request.enriched" {:component "cache"}
                              1700000000000000002)
            (trace/add-link! outer linked {:rel "follows"})
            (logs/emit! (sdk/logger "demo.http")
                        {:body "calling upstream" :severity :info
                         :attributes {:http.method "GET"}})
            (trace/with-span [inner (sdk/tracer "demo.client") "GET example"
                              {:kind :client}]
              (trace/set-status! inner :ok))))
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
                                  "select TraceId, SpanId, Body, ServiceName, SeverityText from otel_logs")
              nested (jdbc/fetch-one
                      conn
                      "select `Events.Name` EventNames,
                              `Events.Attributes` EventAttributes,
                              `Links.TraceId` LinkTraceIds,
                              `Links.TraceState` LinkTraceStates,
                              `Links.Attributes` LinkAttributes,
                              EventsJSON, LinksJSON
                         from otel_traces where SpanName='GET /outbound'")
              lookup (jdbc/fetch-one
                      conn
                      ["select TraceId, toUnixTimestamp(Start) StartUnix,
                               toUnixTimestamp(End) EndUnix
                          from otel_traces_trace_id_ts where TraceId=?"
                       (:traceid (first spans))])]
          (check "parent and child spans persisted" 2 (count spans))
          (check "ClickStack service column" #{"ring-demo"}
                 (set (map :servicename spans)))
          (check "child points at parent span" true
                 (= (:spanid (first spans)) (:parentspanid (second spans))))
          (check "log body persisted" "calling upstream" (:body log))
          (check "log/span trace correlation" (:traceid (first spans)) (:traceid log))
          (check "severity uses ClickStack column" "INFO" (:severitytext log))
          (check "nested event name round-trips" ["request.enriched"]
                 (:eventnames nested))
          (check "nested event attributes round-trip" [{"component" "cache"}]
                 (:eventattributes nested))
          (check "nested link trace ID round-trips"
                 ["11111111111111111111111111111111"] (:linktraceids nested))
          (check "nested link trace state uses W3C raw form" ["vendor=state"]
                 (:linktracestates nested))
          (check "nested link attributes round-trip" [{"rel" "follows"}]
                 (:linkattributes nested))
          (check "legacy viewer event JSON remains populated" true
                 (str/includes? (:eventsjson nested) "request.enriched"))
          (check "legacy viewer link JSON remains populated" true
                 (str/includes? (:linksjson nested)
                                "11111111111111111111111111111111"))
          (check "trace-ID lookup materialized view receives trace" (:traceid (first spans))
                 (:traceid lookup))
          (check "trace-ID lookup range is ordered" true
                 (<= (:startunix lookup) (:endunix lookup))))
        (chdb-export/exporter {:connection conn})
        (check "exporter reopen does not duplicate migration history" 2
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
