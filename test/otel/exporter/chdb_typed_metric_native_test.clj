(ns otel.exporter.chdb-typed-metric-native-test
  "Real direct native evidence for the bounded typed metric projection slice.

  This exercises installer DDL, direct exporter rows, and SQL readback only.
  OTLP socket and Durable recovery evidence remain deliberately separate."
  (:require [db.jdbc]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb.typed-metric-explorer :as metric-explorer]
            [otel.sdk.export :as export]))

(def ^:private int64-max 9223372036854775807)
(def ^:private observed-checks (atom 0))

(defn- check! [label expected actual]
  (swap! observed-checks inc)
  (if (= expected actual)
    (println "  ok  " label)
    (throw (ex-info label {:expected expected :actual actual}))))

(defn- approved [application-id table entries]
  (manifest/compile-manifest
   {:dataset-id "typed-metric-native" :application-id application-id
    :lineage "typed-metric-native-v1" :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema :authority :advice
      :source (str "advice/" application-id ".edn")
      :entries (mapv (fn [[location key type]]
                       {:signal :metrics :table table :location location
                        :key key :type type})
                     entries)}]}))

(defn- observed-columns [connection table]
  (into {} (map (juxt :name :type))
        (jdbc/fetch connection (str "DESCRIBE TABLE " table))))

(defn- install! [store connection approved table]
  (let [ddl (atom 0)
        result (installer/install-approved!
                store approved
                {:target connection
                 :observe-columns #(vector {:signal :metrics :table table
                                            :columns (observed-columns connection table)})
                 :execute-ddl! (fn [operation]
                                 (swap! ddl inc)
                                 (jdbc/execute! connection operation))})]
    (assoc result :ddl-count @ddl)))

(defn- physical-by-key [installation]
  (into {} (map (juxt :key :physical))
        (get-in installation [:record :manifest :fields])))

(defn- expected-column-types [installation]
  (into {}
        (mapcat (fn [{:keys [clickhouse-type physical]}]
                  [[(:value-column physical) clickhouse-type]
                   [(:status-column physical) "UInt8"]])
                (get-in installation [:record :manifest :fields]))))

(defn- field-columns [physical]
  (mapcat (fn [[_ {:keys [value-column status-column]}]]
            [[value-column "value"] [status-column "status"]])
          physical))

(defn- typed-select [table physical]
  (str "SELECT ResourceAttributes AS resource, ScopeAttributes AS scope, Attributes AS attributes, "
       (clojure.string/join ", "
                            (map (fn [[column role]]
                                   (if (= role "value")
                                     (str "`" column "` AS `" column "`")
                                     (str "`" column "` AS `" column "`")))
                                 (field-columns physical)))
       " FROM " table " ORDER BY MetricName"))

(defn- type-select [table physical]
  (str "SELECT "
       (clojure.string/join ", "
                            (map (fn [[column _]]
                                   (str "toTypeName(`" column "`) AS `" column "`"))
                                 (field-columns physical)))
       " FROM " table " LIMIT 1"))

(defn- metric-batch
  ([] (metric-batch 7))
  ([workers]
   [{:scope {:name "typed-native"
             :attributes {"runtime.workers" workers "sum.scope.workers" workers
                          "scope.generic" "kept-generic"}}
     :metrics [{:type :gauge :name "typed.gauge" :description "" :unit "1"
                :data-points [{:value 2.0 :time-unix-nano 1000000000
                               :attributes {"queue.ready" false "queue.count" int64-max
                                            "point.generic" "kept-generic"}}]}
               {:type :sum :name "typed.sum" :description "" :unit "1"
                :temporality :cumulative :monotonic? true
                :data-points [{:value 3.0 :time-unix-nano 1000000000
                               :attributes {"request.success" false "request.count" int64-max
                                            "point.generic" "kept-generic"}}]}]}]))

(defn- value-status [row physical key]
  [(get row (keyword (:value-column (get physical key))))
   (get row (keyword (:status-column (get physical key))))])

(defn- schema-binding [field]
  {:attribute-key (:key field) :attribute-location (:location field)
   :attribute-type (:type field) :field-id (:id field)
   :manifest-version (get-in field [:identity :version])})

(defn- gauge-request
  ([installation key value]
   (gauge-request installation key :eq value))
  ([installation key operator value]
   (let [field (first (filter #(= key (:key %))
                              (get-in installation [:record :manifest :fields])))]
     {:schema-binding (schema-binding field) :signal :metrics :metric-kind :gauge
      :start-unix-nano 0 :end-unix-nano 2000000000
      :operator operator :value value :limit 10 :max-text-length 64})))

(defn- gauge-coverage-request [installation key]
  (select-keys (gauge-request installation key nil)
               [:schema-binding :signal :metric-kind :start-unix-nano :end-unix-nano]))

(defn -main [& _]
  (reset! observed-checks 0)
  (println "typed metric direct native qualification")
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (schema/ensure-schema! connection)
    (let [store (backend/memory-backend)
          gauge-approved (approved "gauge" "otel_metrics_gauge"
                                   [[:resource-attributes "service.ready" :boolean]
                                    [:resource-attributes "service.tier" :string]
                                    [:scope-attributes "runtime.workers" :int64]
                                    [:metric-attributes "queue.ready" :boolean]
                                    [:metric-attributes "queue.count" :int64]])
          sum-approved (approved "sum" "otel_metrics_sum"
                                 [[:resource-attributes "sum.resource.ready" :boolean]
                                  [:scope-attributes "sum.scope.workers" :int64]
                                  [:metric-attributes "request.success" :boolean]
                                  [:metric-attributes "request.count" :int64]])
          gauges (install! store connection gauge-approved "otel_metrics_gauge")
          sums (install! store connection sum-approved "otel_metrics_sum")
          gauge-physical (physical-by-key gauges)
          sum-physical (physical-by-key sums)]
      (check! "real gauge and sum installer DDL activates both capabilities"
              [:active :active] [(:status gauges) (:status sums)])
      (check! "first native installs emit exactly owned additive DDL"
              [10 8] [(:ddl-count gauges) (:ddl-count sums)])
      (check! "native observed gauge columns retain exact owned types"
              (expected-column-types gauges)
              (select-keys (observed-columns connection "otel_metrics_gauge")
                           (keys (expected-column-types gauges))))
      (check! "native observed sum columns retain exact owned types"
              (expected-column-types sums)
              (select-keys (observed-columns connection "otel_metrics_sum")
                           (keys (expected-column-types sums))))
      (check! "second native gauge install is idempotent"
              [:active 0]
              (let [again (install! store connection gauge-approved "otel_metrics_gauge")]
                [(:status again) (:ddl-count again)]))
      (let [writer (exporter/exporter {:connection connection :create-schema? false
                                       :signals #{:metrics}
                                       :typed-gauge-descriptors (:descriptor-set gauges)
                                       :typed-sum-descriptors (:descriptor-set sums)})]
        (try
          (check! "direct native gauge and sum export succeeds" true
                  (export/export-metrics! writer
                                          {:attributes {"service.ready" false
                                                        "service.tier" "gold"
                                                        "sum.resource.ready" false
                                                        "resource.generic" "kept-generic"}}
                                          (metric-batch)))
          (let [gauge-row (jdbc/fetch-one connection (typed-select "otel_metrics_gauge" gauge-physical))
                sum-row (jdbc/fetch-one connection (typed-select "otel_metrics_sum" sum-physical))]
            (check! "native gauge retains generic maps and projects all three locations"
                    [{"service.ready" "false" "service.tier" "gold"
                      "resource.generic" "kept-generic"}
                     {"runtime.workers" "7" "scope.generic" "kept-generic"}
                     {"queue.ready" "false" "queue.count" (str int64-max) "point.generic" "kept-generic"}
                     [[false 3] ["gold" 3] [7 3] [false 3] [int64-max 3]]]
                    [(:resource gauge-row) (:scope gauge-row) (:attributes gauge-row)
                     [(value-status gauge-row gauge-physical "service.ready")
                      (value-status gauge-row gauge-physical "service.tier")
                      (value-status gauge-row gauge-physical "runtime.workers")
                      (value-status gauge-row gauge-physical "queue.ready")
                      (value-status gauge-row gauge-physical "queue.count")]])
            (check! "native sum retains generic maps and projects all three locations"
                    [{"sum.resource.ready" "false" "resource.generic" "kept-generic"}
                     {"runtime.workers" "7" "sum.scope.workers" "7" "scope.generic" "kept-generic"}
                     {"request.success" "false" "request.count" (str int64-max) "point.generic" "kept-generic"}
                     [[false 3] [7 3] [false 3] [int64-max 3]]]
                    [(:resource sum-row) (:scope sum-row) (:attributes sum-row)
                     [(value-status sum-row sum-physical "sum.resource.ready")
                      (value-status sum-row sum-physical "sum.scope.workers")
                      (value-status sum-row sum-physical "request.success")
                      (value-status sum-row sum-physical "request.count")]])
            (check! "native typed value and status columns have exact types"
                    ["Bool" "UInt8" "Int64" "UInt8"]
                    (let [types (jdbc/fetch-one connection (type-select "otel_metrics_gauge" gauge-physical))
                          ready (get gauge-physical "queue.ready") count (get gauge-physical "queue.count")]
                      [(get types (keyword (:value-column ready)))
                       (get types (keyword (:status-column ready)))
                       (get types (keyword (:value-column count)))
                       (get types (keyword (:status-column count)))]))
            (let [request (gauge-request gauges "queue.ready" false)
                  result (metric-explorer/typed-gauge-filtered-points
                          connection (:descriptor-set gauges) request)]
              (check! "native gauge discovery and typed Boolean filter read back the installed row"
                      [[[:metric-attributes :boolean]
                        [:metric-attributes :int64]
                        [:resource-attributes :boolean]
                        [:resource-attributes :string]
                        [:scope-attributes :int64]]
                       {:valid 1 :present-empty 0 :absent 0 :invalid 0
                        :historical-untyped-fallback 0
                        :historical-untyped-unavailable 0 :total 1}
                       [false 3 "typed.gauge" 2]]
                      [(->> (metric-explorer/typed-gauge-fields connection (:descriptor-set gauges))
                            (mapv (fn [{:keys [schema-binding]}]
                                    [(:attribute-location schema-binding)
                                     (:attribute-type schema-binding)]))
                            sort vec)
                       (:coverage result)
                       (let [row (first (:matches result))]
                         [(:attribute-value row) (:typed-status row)
                          (:metric-name row) (:metric-value row)])]))
            ;; Exercise the direct path's three locations and all stored
            ;; availability states. These rows are deliberately exported, not
            ;; forged with SQL, so the query observes real value/status pairs.
            (check! "direct typed gauge export emits empty absent and invalid resource String states"
                    [true true true]
                    [(export/export-metrics! writer
                                              {:attributes {"service.ready" false
                                                            "service.tier" ""}}
                                              (metric-batch 6))
                     (export/export-metrics! writer
                                              {:attributes {"service.ready" false}}
                                              (metric-batch 8))
                     (export/export-metrics! writer
                                              {:attributes {"service.ready" false
                                                            "service.tier" 7}}
                                              (metric-batch 7))])
            (let [legacy (exporter/exporter {:connection connection :create-schema? false
                                             :signals #{:metrics}})]
              (try
                (check! "direct capability-free rows retain fallback and unavailable resource states"
                        [true true]
                        [(export/export-metrics! legacy {:attributes {"service.tier" "legacy"}}
                                                (metric-batch))
                         (export/export-metrics! legacy {:attributes {}}
                                                (metric-batch))])
                (finally (export/shutdown-metric-exporter! legacy))))
            (let [query #(metric-explorer/typed-gauge-filtered-points
                          connection (:descriptor-set gauges) %)
                  boolean (query (gauge-request gauges "queue.ready" false))
                  int64-eq (query (gauge-request gauges "runtime.workers" :eq 7))
                  int64-gte (query (gauge-request gauges "runtime.workers" :gte 7))
                  int64-lt (query (gauge-request gauges "runtime.workers" :lt 8))
                  string-eq (query (gauge-request gauges "service.tier" :eq "gold"))
                  string-prefix (query (gauge-request gauges "service.tier" :prefix "go"))
                  string-contains (query (gauge-request gauges "service.tier" :contains "ol"))
                  hostile (query (gauge-request gauges "service.tier" :contains "gold' OR 1=1 --"))]
              (check! "direct native filters cover Boolean metric Int64 scope and String resource locations"
                      [#{false} #{7} #{7 8} #{6 7} ["gold"] ["gold"] ["gold"] []]
                      [(set (map :attribute-value (:matches boolean)))
                       (set (map :attribute-value (:matches int64-eq)))
                       (set (map :attribute-value (:matches int64-gte)))
                       (set (map :attribute-value (:matches int64-lt)))
                       (mapv :attribute-value (:matches string-eq))
                       (mapv :attribute-value (:matches string-prefix))
                       (mapv :attribute-value (:matches string-contains))
                       (mapv :attribute-value (:matches hostile))]))
            (check! "direct native String coverage distinguishes all six availability states"
                    {:valid 1 :present-empty 1 :absent 1 :invalid 1
                     :historical-untyped-fallback 1
                     :historical-untyped-unavailable 1 :total 6}
                    (:coverage (metric-explorer/typed-gauge-coverage
                                connection (:descriptor-set gauges)
                                (gauge-coverage-request gauges "service.tier")))))
          (finally (export/shutdown-metric-exporter! writer)))
        ;; This is a real table-qualified DESCRIBE failure path, not a forged
        ;; observation. A wrong existing type must mark the same registry
        ;; revision failed and must not issue corrective additive DDL.
        (let [column (:value-column (get gauge-physical "queue.ready"))]
          (jdbc/execute! connection (str "ALTER TABLE otel_metrics_gauge MODIFY COLUMN `"
                                         column "` String"))
          (let [failed (install! store connection gauge-approved "otel_metrics_gauge")]
            (check! "native table-qualified wrong type fails without DDL"
                    [:failed 0] [(:status failed) (:ddl-count failed)]))))))
  (when-not (= 15 @observed-checks)
    (throw (ex-info "typed metric native check inventory changed"
                    {:expected 15 :actual @observed-checks})))
  (println "typed-metric-native-qualified :observed-checks" @observed-checks))
