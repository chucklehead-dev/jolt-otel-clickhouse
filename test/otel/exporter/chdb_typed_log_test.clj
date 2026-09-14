(ns otel.exporter.chdb-typed-log-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [db.jdbc]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.attribute-identity :as identity]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.sdk.logs :as sdk-logs]))

(defn- compiled []
  (manifest/compile-manifest
   {:dataset-id "telemetry-prod" :application-id "log-worker"
    :lineage "log-worker-v1" :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema
      :authority :advice :source "advice/log-worker.edn"
      :entries
      [{:signal :logs :table "otel_logs" :location :log-attributes
        :key "job.name" :type :string}
       {:signal :logs :table "otel_logs" :location :log-attributes
        :key "job.complete" :type :boolean}
       {:signal :logs :table "otel_logs" :location :log-attributes
        :key "job.attempt" :type :int64}]}]}))

(defn- installed []
  (let [approved (compiled)
        columns (registry/expected-columns (registry/prepare approved))
        observed (atom {})
        next-column (atom 0)
        target (atom :typed-log-target)]
    (assoc
     (installer/install-approved!
      (backend/memory-backend) approved
      {:target target
       :observe-columns #(vector {:columns (into {} @observed)
                                  :signal :logs :table "otel_logs"})
       :execute-ddl!
       (fn [_]
         (let [{:keys [name type]} (nth columns @next-column)]
           (swap! next-column inc)
           (swap! observed assoc name type)))})
     ::target target)))

(defn- field [installation key]
  (first (filter #(= key (:key %))
                 (get-in installation [:record :manifest :fields]))))

(defn- projected-pair [row installation key]
  (let [physical (:physical (field installation key))]
    [(get row (:value-column physical)) (get row (:status-column physical))]))

(defn- log-record [attributes]
  {:timestamp-unix-nano 1700000000000000000
   :observed-time-unix-nano 1700000000000000001
   :severity-number 9 :severity-text "INFO"
   :body "typed log" :event-name "typed.log"
   :resource {:attributes {:service.name "log-worker"}}
   :scope {:name "typed-log-test" :version "1"}
   :attributes attributes})

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(defn- exported [descriptor-set target attributes]
  (let [statement (atom nil)
        exporter (chdb-export/exporter
                  {:connection target :create-schema? false :signals #{:logs}
                   :typed-log-descriptors descriptor-set})]
    (with-redefs [jdbc/execute!
                  (fn [_ sql] (reset! statement sql) {:count 1})]
      (when-not (sdk-logs/export-logs! exporter [(log-record attributes)])
        (throw (chdb-export/last-error exporter))))
    {:query (first (str/split @statement #" FORMAT JSONEachRow\n" 2))
     :row (json/read-str
           (second (str/split @statement #"FORMAT JSONEachRow\n" 2)))}))

(defn run [check]
  (println "confirmed typed log-record projection")
  (let [installation (installed)
        descriptor-set (:descriptor-set installation)
        target (::target installation)
        projector (projection/log-projector descriptor-set target)
        valid (projector (log-record {"job.name" "archive"
                                      :job.complete false
                                      "job.attempt" 9007199254740993}))]
    (check "only the exact log-record target joins physical support"
           [true false false false]
           [(identity/physically-supported? identity/log-attribute-target)
            (identity/physically-supported?
             (identity/target :logs "otel_logs" :resource-attributes))
            (identity/physically-supported?
             (identity/target :logs "otel_logs" :scope-attributes))
            (identity/physically-supported?
             (identity/target :metrics "otel_metrics_gauge"
                              :metric-attributes))])
    (check "log values retain scalar types and valid status"
           [["archive" 3] [false 3] [9007199254740993 3]]
           [(projected-pair valid installation "job.name")
            (projected-pair valid installation "job.complete")
            (projected-pair valid installation "job.attempt")])
    (let [missing (projector (log-record {}))
          invalid (projector
                   (log-record {"job.name" "" "job.complete" "false"
                                "job.attempt" 9223372036854775808}))]
      (check "log absence uses safe typed defaults"
             [["" 1] [false 1] [0 1]]
             [(projected-pair missing installation "job.name")
              (projected-pair missing installation "job.complete")
              (projected-pair missing installation "job.attempt")])
      (check "empty and invalid log values remain distinguishable"
             [["" 2] [false 4] [0 4]]
             [(projected-pair invalid installation "job.name")
              (projected-pair invalid installation "job.complete")
              (projected-pair invalid installation "job.attempt")]))
    (check "duplicate normalized log keys are row-local invalid"
           [0 4]
           (projected-pair
            (projector (log-record {"job.attempt" 1 :job.attempt 2}))
            installation "job.attempt"))
    (let [{:keys [query row]}
          (exported descriptor-set target
                    {"job.name" "archive" "job.complete" false
                     "job.attempt" 9007199254740993})]
      (check "typed log insert names additive JSONEachRow fields"
             "insert into otel_logs" query)
      (check "typed export preserves LogAttributes fallback"
             [{"job.name" "archive" "job.complete" "false"
               "job.attempt" "9007199254740993"}
              ["archive" 3] [false 3] [9007199254740993 3]]
             [(get row "LogAttributes")
              (projected-pair row installation "job.name")
              (projected-pair row installation "job.complete")
              (projected-pair row installation "job.attempt")]))
    (let [without-capability (exported nil :legacy-log-target {"job.attempt" 7})]
      (check "legacy log export retains its fixed compatibility insert"
             [true {"job.attempt" "7"}]
             [(str/starts-with? (:query without-capability)
                                "insert into otel_logs (")
              (get-in without-capability [:row "LogAttributes"])]))
    (check "a log capability cannot enter trace projection"
           :otel.exporter.chdb.attribute-projection/signal-mismatch
           (:type (thrown-data
                   #(projection/trace-projector descriptor-set target))))
    (check "bare log descriptors cannot substitute for confirmation"
           :otel.exporter.chdb.attribute-registry-installer/unconfirmed-descriptors
           (:type (thrown-data
                   #(projection/log-projector (:descriptors installation)
                                              target))))
    (check "a confirmed log capability cannot cross connections"
           :otel.exporter.chdb.attribute-projection/target-mismatch
           (:type
            (thrown-data
             #(chdb-export/exporter
               {:connection (atom :other) :create-schema? false
                :signals #{:logs} :typed-log-descriptors descriptor-set}))))
    (check "typed log capability requires its explicit connection"
           :otel.exporter.chdb/typed-descriptors-require-connection
           (:type
            (thrown-data
             #(chdb-export/exporter
               {:db-spec "chdb::memory:" :create-schema? false
                :signals #{:logs} :typed-log-descriptors descriptor-set}))))
    (let [ddl (atom 0)
          error
          (thrown-data
           #(installer/install-approved!
             (backend/memory-backend) (compiled)
             {:target (atom :wrong-table)
              :observe-columns
              (fn [] [{:columns {} :signal :spans :table "otel_traces"}])
              :execute-ddl! (fn [_] (swap! ddl inc))}))]
      (check "wrong-table evidence fails before typed log DDL"
             [:otel.exporter.chdb.attribute-registry/missing-table-observation 0]
             [(:type error) @ddl]))))

(defn -main [& _]
  (let [failures (atom 0)]
    (run (fn [label expected actual]
           (if (= expected actual)
             (println "  ok  " label)
             (do (swap! failures inc)
                 (println "  FAIL" label "- expected" (pr-str expected)
                          "got" (pr-str actual))))))
    (when (pos? @failures)
      (throw (ex-info "typed log pure checks failed" {:failures @failures})))
    (println "all typed log pure checks passed")))
