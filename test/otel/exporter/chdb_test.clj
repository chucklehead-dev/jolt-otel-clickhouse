(ns otel.exporter.chdb-test
  (:require [db.jdbc]
            [clojure.test :as test]
            [clojure.string :as str]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb]
            [jdbc.core :as jdbc]
            [jolt.process :as process]
            [otel.context :as context]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb-test-support :as test-support]
            [otel.exporter.chdb-ordinary-rows-test :as ordinary-rows-test]
            [otel.exporter.chdb-ordinary-transport-diagnostics-test]
            [otel.exporter.chdb-ordinary-typed-rows-test :as ordinary-typed-rows-test]
            [otel.exporter.chdb-attribute-manifest-test :as manifest-test]
            [otel.exporter.chdb-attribute-projection-test :as attribute-projection-test]
            [otel.exporter.chdb-attribute-registry-test :as registry-test]
            [otel.exporter.chdb-attribute-registry-installer-test :as registry-installer-test]
            [otel.exporter.chdb-attribute-registry-store-test :as registry-store-test]
            [otel.exporter.chdb-typed-query-test :as typed-query-test]
            [otel.exporter.chdb-typed-query-native-test :as typed-query-native-test]
            [otel.exporter.chdb-typed-log-test :as typed-log-test]
            [otel.exporter.chdb-typed-gauge-test :as typed-gauge-test]
            [otel.exporter.chdb-typed-sum-test :as typed-sum-test]
            [otel.exporter.chdb-typed-log-explorer-test :as typed-log-explorer-test]
            [otel.exporter.chdb-typed-log-socket-native-test :as typed-log-socket-test]
            [otel.exporter.chdb-benchmark :as benchmark]
            [otel.exporter.chdb-dependency-test :as dependency-test]
            [otel.exporter.chdb-explorer-test :as explorer-test]
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

(defn- child-test-executable
  ([] (child-test-executable (System/getenv "JOLT_TEST_CHILD_EXECUTABLE")))
  ([selected]
  ;; Local qualification supplies an absolute, checksum-guarded command:
  ;; the mandatory Chez wrapper prepends ~/.local/bin to PATH.
    (if (nil? selected)
      "jolt"
      (do
        (when-not (and (not (str/blank? selected))
                       (.isAbsolute (java.io.File. selected)))
          (throw (ex-info "Invalid child test executable"
                          {:type ::invalid-child-test-executable})))
        selected))))

(defn- finish-checks! []
  (if (zero? @failures)
    (println "all checks passed")
    (throw (ex-info (str @failures " checks failed") {:failures @failures}))))

(defn- run-ordinary-transport-diagnostics-checks []
  (let [suite 'otel.exporter.chdb-ordinary-transport-diagnostics-test
        names '[known-schema-categories-are-closed
                unknown-and-malicious-data-cannot-leak
                setup-markers-preserve-original-calls-and-outcomes
                successful-setup-result-is-unchanged
                registry-readback-is-closed-and-read-only-by-construction
                maintained-profile-and-failure-exit-source-contract]
        actual (set (for [[name var] (ns-publics suite) :when (:test (meta var))] name))]
    (check "ordinary transport diagnostics exact six-test inventory" (set names) actual)
    (when (pos? @failures) (finish-checks!))
    (binding [test/*report-counters* (ref test/*initial-report-counters*)]
      (test/test-vars (mapv #(ns-resolve suite %) names))
      (check "ordinary transport diagnostics strict summary"
             {:test 6 :pass 52 :fail 0 :error 0}
             (select-keys @test/*report-counters* [:test :pass :fail :error])))
    ;; Do not enter native gates after missing tests or a failing pure suite.
    (when (pos? @failures) (finish-checks!))))

(defn- isolated-memory-db-spec [scenario]
  ;; Separate query contexts select separate logical databases while respecting
  ;; the immutable native process-lifetime physical :memory: anchor.
  {:vendor "chdb" :name ":memory:"
   :database (str "exporter_" scenario "_"
                  (str/replace (str (java.util.UUID/randomUUID)) "-" "_"))})

(defn- run-clean-source-load-check []
  ;; This cannot be an in-process require-order check: this test runner has
  ;; already loaded db.jdbc for its native integration tests. Disable the child
  ;; AOT cache so jdbc.core is compiled in a genuinely fresh runtime where the
  ;; public namespace must install the shim itself.
  (doseq [source-ns ['otel.exporter.chdb
                     'otel.exporter.chdb.attribute-manifest
                     'otel.exporter.chdb.attribute-projection
                     'otel.exporter.chdb.attribute-registry
                     'otel.exporter.chdb.attribute-registry-installer
                     'otel.exporter.chdb.attribute-registry-store
                     'otel.exporter.chdb.schema
                     'otel.exporter.chdb.explorer
                     'otel.exporter.chdb.typed-log-explorer]]
    (let [expression (str "(require '" source-ns ")"
                          "(println :clean-source-load '" source-ns ")")
          child (process/process
                 [(child-test-executable) "-e" expression]
                 {:out :string :err :string
                  :extra-env {"JOLT_AOT_CACHE" "0"}})
          result (deref child 60000 ::timeout)]
      (when (= ::timeout result)
        (try (process/destroy-tree child) (catch Throwable _ nil)))
      (check (str source-ns " owns clean-process db.jdbc bootstrap")
             true
             (and (map? result)
                  (zero? (:exit result))
                  (str/includes? (str (:out result))
                                 ":clean-source-load")))
      (when (and (map? result) (not (zero? (:exit result))))
        (println "  clean source stderr:" (str (:err result)))))))

(defn- run-backend-benchmark-gate []
  (let [report (benchmark/run! {:batches 1 :items 2 :query-iterations 1})]
    (check "bounded backend benchmark reconciles every table"
           (get-in report [:workload :expected-counts])
           (get-in report [:workload :actual-counts]))))

(defn- delete-tree! [path]
  (let [root (java.io.File. path)]
    (when (.exists root)
      (doseq [file (reverse (file-seq root))]
        (.delete file)))))

(defn- run-causal-record-effect-readback-process! [path]
  (let [terminal? (atom false)
        owned-child (atom nil)]
    (try
      (let [expression (str "(try (require 'otel.exporter.chdb-migration-record-effect-child) "
                            "((ns-resolve 'otel.exporter.chdb-migration-record-effect-child "
                            "'-main)) "
                            "(catch Throwable _ "
                            "(println :causal-record-effect-readback-child-error) "
                            "(System/exit 1)))")
            child (process/process [(child-test-executable) "-A:test" "-e" expression]
                                   {:out :string :err :string
                                    :extra-env {"JOLT_AOT_CACHE" "0"
                                                "JOLT_CAUSAL_RECORD_EFFECT_PATH" path}})]
        (reset! owned-child child)
        (let [result (deref child 120000 ::timeout)]
          (reset! terminal? (and (map? result) (integer? (:exit result))))
          (when-not (and (map? result) (zero? (:exit result)))
            (println "  record-effect readback child stdout:" (str (:out result)))
            (println "  record-effect readback child stderr:" (str (:err result))))
          (check "record-effect readback qualifies in a separate native process"
                 true
                 (and (map? result)
                      (zero? (:exit result))
                      (str/includes? (str (:out result))
                                     ":causal-record-effect-readback-child-confirmed")
                      (str/includes? (str (:out result))
                                     ":causal-record-effect-readback-child-result :qualified true")))))
      (catch Throwable _
        (check "record-effect readback child setup remains an accounted failure" true false))
      (finally
        ;; Do not remove a persistent native database until its reader has a
        ;; terminal result; destroy-tree only requests that termination.
        (when (and @owned-child (not @terminal?))
          (try (process/destroy-tree @owned-child) (catch Throwable _ nil))
          (let [settled (try (deref @owned-child 5000 ::cleanup-timeout)
                             (catch Throwable _ ::cleanup-error))]
            (reset! terminal? (and (map? settled) (integer? (:exit settled))))
            (check "record-effect readback cleanup confirms terminal ownership"
                   true @terminal?)))
        (if @terminal?
          (delete-tree! path)
          (println :causal-record-effect-readback-cleanup-terminal-unconfirmed))))))

(defn- run-causal-record-effect-writer-process! [path]
  (let [terminal? (atom false)
        owned-child (atom nil)
        qualified? (atom false)]
    (try
      (let [expression (str "(try (require 'otel.exporter.chdb-migration-record-effect-writer) "
                            "((ns-resolve 'otel.exporter.chdb-migration-record-effect-writer '-main)) "
                            "(catch Throwable _ "
                            "(println :causal-record-effect-writer-child-error) "
                            "(System/exit 1)))")
            child (process/process [(child-test-executable) "-A:test" "-e" expression]
                                   {:out :string :err :string
                                    :extra-env {"JOLT_AOT_CACHE" "0"
                                                "JOLT_CAUSAL_RECORD_EFFECT_PATH" path}})]
        (reset! owned-child child)
        (let [result (deref child 120000 ::timeout)
              qualified (and (map? result)
                             (zero? (:exit result))
                             (str/includes? (str (:out result))
                                            ":causal-record-effect-writer-child-confirmed")
                             (str/includes? (str (:out result))
                                            ":causal-record-effect-writer-child-result :qualified true"))]
          (reset! terminal? (and (map? result) (integer? (:exit result))))
          (reset! qualified? qualified)
          (when-not qualified
            (println "  record-effect writer child stdout:" (str (:out result)))
            (println "  record-effect writer child stderr:" (str (:err result))))
          (check "post-native record effect writer qualifies" true qualified)))
      (catch Throwable _
        (check "record-effect writer child setup remains an accounted failure" true false))
      (finally
        (when (and @owned-child (not @terminal?))
          (try (process/destroy-tree @owned-child) (catch Throwable _ nil))
          (let [settled (try (deref @owned-child 5000 ::cleanup-timeout)
                             (catch Throwable _ ::cleanup-error))]
            (reset! terminal? (and (map? settled) (integer? (:exit settled))))
            (check "record-effect writer cleanup confirms terminal ownership"
                   true @terminal?)))))
    @qualified?))

(defn- run-causal-record-effect-check []
  (let [placeholder (java.io.File/createTempFile "jolt-otel-record-effect-" ".chdb")
        path (.getAbsolutePath placeholder)]
    (.delete placeholder)
    (try
      (when (run-causal-record-effect-writer-process! path)
        ;; This is intentionally a second native process, not a same-process
        ;; query. It characterizes native effect versus result consumption and
        ;; makes no retry or unrecorded-result claim.
        (run-causal-record-effect-readback-process! path))
      (finally
        (when (.exists (java.io.File. path))
          (delete-tree! path))))))

(defn- normalize-clickstack-type [type]
  (-> type
      (str/replace #"LowCardinality\(([^()]*)\)" "$1")
      (str/replace #"\s+" "")))

(defn- normalize-describe-type [type]
  (str/replace type #"\s+" ""))

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

(defn- run-clickstack-log-schema-checks [conn]
  (let [described (jdbc/fetch conn "describe table otel_logs")
        actual (into {} (map (fn [{:keys [name type]}]
                               [name (normalize-describe-type type)]))
                     described)
        expected (mapv (fn [column]
                         [column (get schema/clickstack-log-insert-types column)])
                       schema/clickstack-log-insert-columns)]
    (check "all official ClickStack log insert columns exist"
           schema/clickstack-log-insert-columns
           (filterv #(contains? actual %)
                    schema/clickstack-log-insert-columns))
    (check "ClickStack log insert types match the pinned collector"
           expected
           (mapv (fn [column] [column (get actual column)])
                 schema/clickstack-log-insert-columns))))

(defn- metric-expected-type [kind column]
  (get-in schema/clickstack-metric-insert-types [kind column]))

(defn- run-clickstack-metric-schema-checks [conn]
  (doseq [kind [:gauge :sum :histogram]]
    (let [columns (get schema/clickstack-metric-insert-columns kind)
          described (jdbc/fetch
                     conn
                     (str "describe table " (get schema/metric-table-names kind)))
          actual (into {} (map (fn [{:keys [name type]}]
                                 [name (normalize-describe-type type)]))
                       described)
          expected (mapv (fn [column]
                           [column (metric-expected-type kind column)])
                         columns)]
      (check (str "all official ClickStack " (name kind) " insert columns exist")
             columns (filterv #(contains? actual %) columns))
      (check (str "ClickStack " (name kind) " insert types match documented contract")
             expected
             (mapv (fn [column] [column (get actual column)]) columns)))))

(defn run-persistent-migration-child!
  "One native physical path in a fresh process; no anchor reset or alternate pins."
  []
  (reset! failures 0)
  (let [observed (atom 0)
        original-check check]
   (with-redefs [check (fn [& arguments]
                        (swap! observed inc)
                        (apply original-check arguments))]
  (let [path (System/getenv "JOLT_PERSISTENT_MIGRATION_PATH")
        db-spec (str "chdb:" path)]
    (when-not (and (string? path) (not (str/blank? path)))
      (throw (ex-info "Persistent migration child path missing" {})))
      (with-open [conn (jdbc/connection db-spec)]
        (schema/migrate! conn))
      (with-open [conn (jdbc/connection db-spec)]
        (schema/migrate! conn)
        (check "persistent database reopen keeps all migration records" 4
               (:n (jdbc/fetch-one conn
                                    "select count() as n from otel_schema_migrations"))))))
  (let [failure-count @failures
        qualified? (and (= 1 @observed) (zero? failure-count))]
    (println :persistent-migration-child-result
             :assertions @observed :failures failure-count)
    (when qualified?
      (println :persistent-migration-child-confirmed))
    (System/exit (if qualified? 0 1)))))

(defn- run-persistent-migration-process-check []
  ;; Use the same explicit child executable contract as clean-source checks.
  ;; :test supplies this namespace; no root pin or production override is added.
  (let [placeholder (java.io.File/createTempFile "jolt-otel-migrations-" ".chdb")
        path (.getAbsolutePath placeholder)
        terminal? (atom false)
        owned-child (atom nil)]
    (.delete placeholder)
    (try
     (let [expression "(try (require 'otel.exporter.chdb-test) ((ns-resolve 'otel.exporter.chdb-test 'run-persistent-migration-child!)) (catch Throwable _ (println :persistent-migration-child-error) (System/exit 1)))"
           child (process/process [(child-test-executable) "-A:test" "-e" expression]
                                  {:out :string :err :string
                                   :extra-env {"JOLT_AOT_CACHE" "0"
                                               "JOLT_PERSISTENT_MIGRATION_PATH" path}})]
       ;; Publish ownership before any potentially throwing wait/output read.
       (reset! owned-child child)
      (let [result (deref child 120000 ::timeout)]
       (reset! terminal? (and (map? result) (integer? (:exit result))))
    ;; Validate a completed child and its unique assertion/summary witnesses.
    ;; A failed/timeout child increments the parent's existing failure counter.
    (check "persistent migrations qualify in a separate native process"
           true
           (and (map? result) (zero? (:exit result))
                (str/includes? (str (:out result))
                               "persistent database reopen keeps all migration records")
                (= 1 (count (re-seq #"(?m)^:persistent-migration-child-confirmed$"
                                   (str (:out result)))))
                (str/includes? (str (:out result))
                               ":persistent-migration-child-result :assertions 1 :failures 0")))))
     (catch Throwable _
       (check "persistent migration child setup remains an accounted failure" true false))
     (finally
       ;; Every post-spawn exceptional path retains the same owned handle.
       ;; A terminal child is not driven twice. Otherwise request retirement
       ;; once and independently await its actual result within five seconds.
       (when (and @owned-child (not @terminal?))
         (try (process/destroy-tree @owned-child) (catch Throwable _ nil))
         (let [settled (try (deref @owned-child 5000 ::cleanup-timeout)
                            (catch Throwable _ ::cleanup-error))]
           (reset! terminal? (and (map? settled) (integer? (:exit settled))))
           (check "persistent migration child cleanup confirms terminal ownership"
                  true @terminal?)))
       ;; destroy-tree is only a request. Do not delete a DB while its child
       ;; might still own it; an unresolved timeout remains a failed gate.
       (if @terminal?
         (delete-tree! path)
         (println :persistent-migration-cleanup-terminal-unconfirmed))))))

(defn- run-migration-checks []
  (println "versioned chDB schema migrations")
  (with-open [conn (jdbc/connection (isolated-memory-db-spec "fresh"))]
    (schema/migrate! conn)
    (schema/migrate! conn)
    (let [applied (jdbc/fetch conn
                              "select Version, Name, Checksum
                                 from otel_schema_migrations order by Version")]
      (check "fresh database records migrations v1 through v4" [1 2 3 4]
             (mapv :version applied))
      (check "migration names are durable"
             ["initial-otel-tables" "clickstack-trace-nested-and-lookup"
              "clickstack-log-insert-types"
              "clickstack-canonical-metric-inserts"]
             (mapv :name applied))
      (check "migration checksums are SHA-256" [64 64 64 64]
             (mapv #(count (:checksum %)) applied))))

  (run-persistent-migration-process-check)
  (run-causal-record-effect-check)

  ;; chDB has no DDL transaction. Simulate a crash/failure after v1's first
  ;; statement and prove the unrecorded, idempotent migration can be retried.
  (with-open [conn (jdbc/connection (isolated-memory-db-spec "v1_failure"))]
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
      (check "v1 migration injection guard ran" true @failed?)
      (check "failed migration identifies statement phase" :statement (:phase failure))
      (check "failed migration identifies version" 1 (:version failure))
      (check "failed migration remains unrecorded" 0
             (:n (jdbc/fetch-one conn
                                  "select count() as n from otel_schema_migrations")))
      (schema/migrate! conn)
      (check "retry records all migrations exactly once" 4
             (:n (jdbc/fetch-one conn
                                  "select count() as n from otel_schema_migrations")))
      (check "retry completed the interrupted table" 1
             (:n (jdbc/fetch-one conn
                                  "select count() as n from system.tables
                                     where database=currentDatabase() and name='otel_logs'")))))

  ;; A partially applied v2 must leave the immutable v1 history intact and
  ;; safely resume its IF NOT EXISTS ALTER/CREATE statements.
  (with-open [conn (jdbc/connection (isolated-memory-db-spec "v2_failure"))]
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
      (check "v2 migration injection guard ran" true @failed?)
      (check "partial v2 failure identifies version" 2 (:version failure))
      (check "partial v2 leaves only v1 recorded" [1]
             (mapv :version
                   (jdbc/fetch conn
                               "select Version from otel_schema_migrations order by Version")))
      (schema/migrate! conn)
      (check "partial v2 retry records remaining migrations once" [1 2 3 4]
             (mapv :version
                   (jdbc/fetch conn
                               "select Version from otel_schema_migrations order by Version")))
      (run-clickstack-trace-schema-checks conn)))

  ;; v3 is an in-place sequence of idempotent type/codec changes. A partial
  ;; application is not recorded and retry must converge without changing the
  ;; immutable v1/v2 history.
  (with-open [conn (jdbc/connection (isolated-memory-db-spec "v3_failure"))]
    (let [execute! jdbc/execute!
          failed? (atom false)
          failure
          (with-redefs [jdbc/execute!
                        (fn
                          ([connection statement]
                           (if (and (= statement schema/log-resource-attributes-ddl)
                                    (compare-and-set! failed? false true))
                             (throw (ex-info "injected v3 migration failure" {}))
                             (execute! connection statement)))
                          ([connection statement options]
                           (execute! connection statement options)))]
            (thrown-data #(schema/migrate! conn)))]
      (check "v3 migration injection guard ran" true @failed?)
      (check "partial v3 failure identifies version" 3 (:version failure))
      (check "partial v3 leaves v1/v2 recorded" [1 2]
             (mapv :version
                   (jdbc/fetch conn
                               "select Version from otel_schema_migrations order by Version")))
      (schema/migrate! conn)
      (check "partial v3 retry records remaining migrations once" [1 2 3 4]
             (mapv :version
                   (jdbc/fetch conn
                               "select Version from otel_schema_migrations order by Version")))
      (run-clickstack-log-schema-checks conn)))

  ;; Exercise the v3 DDL against data shaped by the immutable v1 table, rather
  ;; than proving type changes only on an empty fresh database.
  (with-open [conn (jdbc/connection (isolated-memory-db-spec "legacy_v3"))]
    (jdbc/execute! conn schema/logs-ddl)
    (jdbc/execute!
     conn
     "insert into otel_logs
        (Timestamp, Body, ResourceAttributes, LogAttributes)
        values (fromUnixTimestamp64Nano(1700000000123456789), 'pre-v3',
                map('service.name', 'legacy'), map('answer', '42'))")
    (let [statements (:statements (nth schema/migrations 2))]
      (doseq [statement statements] (jdbc/execute! conn statement))
      (doseq [statement statements] (jdbc/execute! conn statement)))
    (check "v3 preserves rows from the v1 physical schema"
           ["pre-v3" {"service.name" "legacy"} {"answer" "42"}]
           ((juxt :body :resourceattributes :logattributes)
            (jdbc/fetch-one conn
                            "select Body, ResourceAttributes, LogAttributes
                               from otel_logs")))
    (run-clickstack-log-schema-checks conn))

  ;; v4 spans three existing tables. Failure after earlier actions must retain
  ;; the immutable v1-v3 prefix and retry every IF EXISTS/IF NOT EXISTS action.
  (with-open [conn (jdbc/connection (isolated-memory-db-spec "v4_failure"))]
    (let [execute! jdbc/execute!
          failed? (atom false)
          fail-statement (nth schema/metric-v4-statements 17)
          failure
          (with-redefs [jdbc/execute!
                        (fn
                          ([connection statement]
                           (if (and (= statement fail-statement)
                                    (compare-and-set! failed? false true))
                             (throw (ex-info "injected v4 migration failure" {}))
                             (execute! connection statement)))
                          ([connection statement options]
                           (execute! connection statement options)))]
            (thrown-data #(schema/migrate! conn)))]
      (check "v4 migration injection guard ran" true @failed?)
      (check "partial v4 failure identifies version" 4 (:version failure))
      (check "partial v4 leaves v1-v3 recorded" [1 2 3]
             (mapv :version
                   (jdbc/fetch conn
                               "select Version from otel_schema_migrations order by Version")))
      (schema/migrate! conn)
      (check "partial v4 retry records v4 once" [1 2 3 4]
             (mapv :version
                   (jdbc/fetch conn
                               "select Version from otel_schema_migrations order by Version")))
      (run-clickstack-metric-schema-checks conn)))

  (with-open [conn (jdbc/connection (isolated-memory-db-spec "checksum_drift"))]
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
      (check "OTel migration history lives in selected database" 4
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
      (check "application-owned migration history uses selected database" 4
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
    (check "default database still receives migration history" 4
           (:n (jdbc/fetch-one default-conn
                               "select count() as n from otel_schema_migrations")))))

(defn- run-instrumentation-suppression-checks []
  (println "telemetry database self-observation suppression")
  (with-open [connection (test-support/connection)]
   (let [seen (atom [])
        exporter (test-support/call-with-qualified-native #(chdb-export/exporter
                  {:connection connection :create-schema? false :signals #{:spans}}))
        span {:name "test"
              :kind :internal
              :start-time-unix-nano 1
              :end-time-unix-nano 2
              :span-context {:trace-id "11111111111111111111111111111111"
                             :span-id "2222222222222222"}
              :resource {:attributes {}}
              :scope {:name "test"}
              :attributes {}
              :events []
              :links []
              :status {:code :unset}}]
    (with-redefs [jdbc.chdb/insert-json-rows!
                  (fn [actual-connection _ _ _]
                    (check "suppression probe reaches the actual ordinary connection"
                           true (identical? connection actual-connection))
                    (swap! seen conj (context/instrumentation-suppressed?))
                    {:count 1})]
      (check "span export succeeds under suppression"
             true (export/export-spans! exporter [span])))
    (check "exporter suppresses its own database instrumentation"
           [true] @seen)))
  (let [seen (atom [])]
    (with-redefs [jdbc/execute!
                  (fn [& _]
                    (swap! seen conj (context/instrumentation-suppressed?))
                    {:count 0})
                  jdbc/fetch-one
                  (fn [& _]
                    (swap! seen conj (context/instrumentation-suppressed?))
                    {:checksum (apply str (repeat 64 "0"))})
                  jdbc/fetch
                  (fn [& _]
                    (swap! seen conj (context/instrumentation-suppressed?))
                    [])]
      (schema/migrate! :fake))
    (check "schema migration suppresses every database operation"
           true (and (seq @seen) (every? true? @seen))))
  (let [statements (atom [])]
    (with-redefs [jdbc/execute!
                  (fn [_ statement]
                    (swap! statements conj statement)
                    {:count 1})
                  jdbc/fetch-one
                  (fn [& _]
                    {:checksum (apply str (repeat 64 "0"))})
                  jdbc/fetch (fn [& _] [])]
      (schema/migrate! :fake))
    (let [records (filterv #(and (string? %)
                                 (str/includes?
                                  % "insert into otel_schema_migrations"))
                           @statements)]
      (check "each migration record is one replayable statement"
             4 (count records))
      (check "migration records use deterministic JSONEachRow"
             true (every? #(str/includes? % " FORMAT JSONEachRow\n")
                          records))
      (check "migration record WAL contains no clock expression"
             true (not-any? #(str/includes? % "now64") records)))))

(defn- run-durable-export-barrier-checks []
  (println "durable export acknowledgement boundary")
  (let [calls (atom [])
        span {:name "durable-span" :kind :internal
              :start-time-unix-nano 1 :end-time-unix-nano 2
              :span-context
              {:trace-id "11111111111111111111111111111111"
               :span-id "2222222222222222"}
              :resource {:attributes {}} :scope {:name "durable-test"}
              :attributes {} :events [] :links [] :status {:code :unset}}]
    (with-redefs [durable/connection-role
                  (fn [_] (swap! calls conj :role) :writer)
                  schema/ensure-schema!
                  (fn [_] (swap! calls conj :schema))
                  durable/checkpoint!
                  (fn [_] (swap! calls conj :checkpoint) {:status :committed})
                  durable/flush!
                  (fn [_] (swap! calls conj :barrier) {:status :committed})
                  jdbc/execute!
                  (fn [& _] (swap! calls conj :insert) {:count 1})]
      (let [exporter (test-support/call-with-qualified-native #(chdb-export/exporter
                      {:connection :fake :durable? true :signals #{:spans}}))]
        (check "Durable startup preflights before schema checkpoint"
               [:role :schema :checkpoint] @calls)
        (check "non-empty Durable span batch succeeds" true
               (export/export-spans! exporter [span]))
        (check "batch success follows its persistence barrier"
               [:role :schema :checkpoint :insert :barrier] @calls)
        (check "force flush reaches the same persistence barrier" true
               (export/flush-exporter! exporter))
        (check "force flush completes after the barrier"
               [:role :schema :checkpoint :insert :barrier :barrier] @calls))))
  (let [span {:name "unconfirmed-span" :kind :internal
              :start-time-unix-nano 1 :end-time-unix-nano 2
              :span-context {:trace-id "" :span-id ""}
              :resource {:attributes {}} :scope {:name "durable-test"}
              :attributes {} :events [] :links [] :status {:code :unset}}
        exporter
        (chdb-export/->ChdbExporter
         :fake false #{:spans}
         (atom {:closed-signals #{}
                :connection-close-claimed? false
                :connection-close-status :open
                :connection-closed? false
                :persistence-barrier (fn [_] {:status :empty})
                :durable? true :last-error nil}))]
    (with-redefs [jdbc/execute! (fn [& _] {:count 1})]
      (check "non-empty batch rejects an empty Durable flush" false
             (export/export-spans! exporter [span])))
    (check "unconfirmed Durable publication is diagnosable"
           :otel.exporter.chdb/durable-barrier-unconfirmed
           (:type (ex-data (chdb-export/last-error exporter)))))
  (let [calls (atom [])]
    (with-redefs [durable/connection-role
                  (fn [_] (swap! calls conj :role) :reader)
                  schema/ensure-schema!
                  (fn [_] (swap! calls conj :schema))]
      (check "Durable reader is rejected with a precise type"
             :otel.exporter.chdb/durable-writer-required
             (:type (thrown-data
                     #(chdb-export/exporter
                       {:connection :reader :durable? true}))))
      (check "reader rejection happens before schema mutation"
             [:role] @calls)))
  (let [opened (atom 0)]
    (with-redefs [jdbc/connection (fn [_] (swap! opened inc) :fake)]
      (check "invalid persistence barrier is rejected precisely"
             :otel.exporter.chdb/invalid-persistence-barrier
             (:type (thrown-data
                     #(chdb-export/exporter
                       {:persistence-barrier 42}))))
      (check "invalid barrier is rejected before connection open" 0 @opened))))

(defn -main [& _]
  (reset! failures 0)
  (run-ordinary-transport-diagnostics-checks)
  (ordinary-rows-test/run check)
  (ordinary-typed-rows-test/run check)
  (dependency-test/run check)
  (manifest-test/run check)
  (attribute-projection-test/run check)
  (registry-test/run check)
  (registry-store-test/run check)
  (registry-installer-test/run check)
  (typed-query-test/run check)
  (typed-log-test/run check)
  (typed-gauge-test/run check)
  (typed-sum-test/run check)
  (typed-log-explorer-test/run check)
  (typed-query-native-test/run check)
  (typed-log-socket-test/-main)
  (run-clean-source-load-check)
  (run-backend-benchmark-gate)
  (run-migration-checks)
  (run-logical-database-checks)
  (run-instrumentation-suppression-checks)
  (run-durable-export-barrier-checks)
  (println "embedded chDB OTel exporter")
  (with-open [conn (jdbc/connection (isolated-memory-db-spec "sdk_example"))]
    (let [exporter (chdb-export/exporter {:connection conn})
          handle (sdk/init! {:service-name "ring-demo"
                             :exporter exporter
                             :processor :batch
                             :metrics? false
                             :logs? true
                             :bridge-logging? false})]
      (try
        (run-clickstack-trace-schema-checks conn)
        (run-clickstack-log-schema-checks conn)
        (run-clickstack-metric-schema-checks conn)
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
        (let [r (resource/resource
                 {:service.name "ring-demo" :deployment.environment.name "test"}
                 {:schema-url "https://example.test/metric-resource/1"})
              provider (sdk-metrics/meter-provider {:resource r})
              meter (sdk-metrics/get-meter
                     provider {:name "demo.metrics" :version "2.0"
                               :schema-url "https://example.test/metric-scope/1"})]
          (metrics/add! (metrics/counter meter "requests"
                                         {:description "accepted requests"
                                          :unit "{request}"})
                        2 {:route "/work"})
          (metrics/set-value! (metrics/gauge meter "queue.depth"
                                             {:description "queued work"
                                              :unit "{item}"})
                              3)
          (metrics/record! (metrics/histogram
                            meter "latency" {:boundaries [10.0 100.0]
                                             :description "request latency"
                                             :unit "ms"})
                           42 {:route "/work"})
          (check "metric export call succeeds" true
                 (export/export-metrics! exporter r (sdk-metrics/collect! provider))))
        (check "canonical ClickStack log export succeeds" true
               (sdk-logs/export-logs!
                exporter
                [{:timestamp-unix-nano 0
                  :observed-time-unix-nano 1700000000123456789
                  :trace-id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  :span-id "bbbbbbbbbbbbbbbb"
                  :trace-flags 257
                  :severity-text "INFO2"
                  :severity-number 265
                  :body {"job" "refresh"}
                  :event-name "cache.refresh"
                  :resource (resource/resource
                             {:service.name "log-worker"
                              :deployment.environment.name "test"}
                             {:schema-url "https://example.test/resource/1"})
                  :scope {:name "demo.events"
                          :version "2.1"
                          :schema-url "https://example.test/scope/1"
                          :attributes {:scope.mode "async"}}
                  :attributes {:http.status_code 202
                               :payload {"safe" true}}}]))
        ;; sdk/shutdown! reaches the log and span pipelines separately. Both
        ;; batch queues must drain even after the first signal shuts down.
        (sdk/shutdown! handle)
        (let [spans (jdbc/fetch conn
                                "select TraceId, SpanId, ParentSpanId, SpanName, ServiceName, SpanAttributes from otel_traces order by Timestamp")
              log (jdbc/fetch-one conn
                                  "select TraceId, SpanId, Body, ServiceName, SeverityText
                                     from otel_logs where EventName=''")
              canonical-log
              (jdbc/fetch-one
               conn
               "select toUnixTimestamp64Nano(Timestamp) TimestampNanos,
                       TraceId, SpanId, TraceFlags, SeverityText, SeverityNumber,
                       ServiceName, Body, ResourceSchemaUrl, ResourceAttributes,
                       ScopeSchemaUrl, ScopeName, ScopeVersion, ScopeAttributes,
                       LogAttributes, EventName
                  from otel_logs where EventName='cache.refresh'")
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
          (check "zero event timestamp falls back to observed timestamp"
                 1700000000123456789 (:timestampnanos canonical-log))
          (check "log correlation IDs round-trip"
                 ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" "bbbbbbbbbbbbbbbb"]
                 [(:traceid canonical-log) (:spanid canonical-log)])
          (check "collector uint8 conversion semantics round-trip" [1 9]
                 [(:traceflags canonical-log) (:severitynumber canonical-log)])
          (check "structured body uses pdata-compatible JSON text"
                 "{\"job\":\"refresh\"}" (:body canonical-log))
          (check "resource metadata round-trips"
                 ["log-worker" "https://example.test/resource/1"
                  {"service.name" "log-worker"
                   "deployment.environment.name" "test"}]
                 [(:servicename canonical-log)
                  (:resourceschemaurl canonical-log)
                  (:resourceattributes canonical-log)])
          (check "scope metadata round-trips"
                 ["https://example.test/scope/1" "demo.events" "2.1"
                  {"scope.mode" "async"}]
                 [(:scopeschemaurl canonical-log) (:scopename canonical-log)
                  (:scopeversion canonical-log) (:scopeattributes canonical-log)])
          (check "log attributes and EventName round-trip"
                 [{"http.status_code" "202" "payload" "{\"safe\":true}"}
                  "cache.refresh"]
                 [(:logattributes canonical-log) (:eventname canonical-log)])
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
        (check "exporter reopen does not duplicate migration history" 4
               (:n (jdbc/fetch-one conn
                                    "select count() as n from otel_schema_migrations")))
        (check "ClickStack gauge table" 1
               (:n (jdbc/fetch-one conn "select count() as n from otel_metrics_gauge")))
        (check "ClickStack sum table" 1
               (:n (jdbc/fetch-one conn "select count() as n from otel_metrics_sum")))
        (check "ClickStack histogram table" 1
               (:n (jdbc/fetch-one conn "select count() as n from otel_metrics_histogram")))
        (let [gauge (jdbc/fetch-one
                     conn
                     "select ResourceAttributes, ResourceSchemaUrl, ScopeName,
                             ScopeVersion, ScopeAttributes, ScopeDroppedAttrCount,
                             ScopeSchemaUrl, ServiceName, MetricName,
                             MetricDescription, MetricUnit, Attributes,
                             toUnixTimestamp(StartTimeUnix) StartUnix, Flags,
                             `Exemplars.FilteredAttributes` ExemplarAttrs,
                             `Exemplars.TimeUnix` ExemplarTimes,
                             `Exemplars.Value` ExemplarValues,
                             `Exemplars.SpanId` ExemplarSpanIds,
                             `Exemplars.TraceId` ExemplarTraceIds, Value
                        from otel_metrics_gauge")
              sum (jdbc/fetch-one
                   conn
                   "select MetricName, Value, Flags, AggregationTemporality,
                           IsMonotonic, `Exemplars.TraceId` ExemplarTraceIds
                      from otel_metrics_sum")
              histogram (jdbc/fetch-one
                         conn
                         "select MetricName, Count, Sum, BucketCounts,
                                 ExplicitBounds, Flags, Min, Max,
                                 AggregationTemporality,
                                 `Exemplars.TraceId` ExemplarTraceIds
                            from otel_metrics_histogram")]
          (check "canonical metric resource/scope metadata round-trips"
                 [{"service.name" "ring-demo"
                   "deployment.environment.name" "test"}
                  "https://example.test/metric-resource/1"
                  "demo.metrics" "2.0" {}
                  0 "https://example.test/metric-scope/1" "ring-demo"]
                 [(:resourceattributes gauge) (:resourceschemaurl gauge)
                  (:scopename gauge) (:scopeversion gauge)
                  (:scopeattributes gauge) (:scopedroppedattrcount gauge)
                  (:scopeschemaurl gauge) (:servicename gauge)])
          (check "gauge canonical defaults and descriptor round-trip"
                 ["queue.depth" "queued work" "{item}" {} 0 0
                  [] [] [] [] [] 3]
                 [(:metricname gauge) (:metricdescription gauge)
                  (:metricunit gauge) (:attributes gauge) (:startunix gauge)
                  (:flags gauge) (:exemplarattrs gauge) (:exemplartimes gauge)
                  (:exemplarvalues gauge) (:exemplarspanids gauge)
                  (:exemplartraceids gauge) (:value gauge)])
          (check "sum canonical fields round-trip"
                 ["requests" 2 0 2 true []]
                 [(:metricname sum) (:value sum) (:flags sum)
                  (:aggregationtemporality sum) (:ismonotonic sum)
                  (:exemplartraceids sum)])
          (check "histogram canonical fields round-trip"
                 ["latency" 1 42 [0 1 0] [10 100] 0 42 42 2 []]
                 [(:metricname histogram) (:count histogram) (:sum histogram)
                  (:bucketcounts histogram) (:explicitbounds histogram)
                  (:flags histogram) (:min histogram) (:max histogram)
                  (:aggregationtemporality histogram)
                  (:exemplartraceids histogram)]))
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
  (explorer-test/run check)
  (finish-checks!))
