(ns otel.exporter.chdb-typed-gauge-test
  "Pure capability-to-gauge-row contract. Native readback lives beside the
  ordinary native fixture because it needs one shared chDB anchor."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [jdbc.chdb :as chdb]
            [jdbc.chdb.durable.backend :as backend]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.attribute-identity :as identity]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb-test-support :as support]
            [otel.sdk.export :as export]))

(defn- compiled []
  (manifest/compile-manifest
   {:dataset-id "telemetry-prod" :application-id "gauge-worker"
    :lineage "gauge-worker-v1" :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema :authority :advice
      :source "advice/gauge-worker.edn"
      :entries [{:signal :metrics :table "otel_metrics_gauge"
                 :location :metric-attributes :key "queue.ready" :type :boolean}
                {:signal :metrics :table "otel_metrics_gauge"
                 :location :metric-attributes :key "queue.depth" :type :int64}
                {:signal :metrics :table "otel_metrics_gauge"
                 :location :resource-attributes :key "service.tier" :type :string}
                {:signal :metrics :table "otel_metrics_gauge"
                 :location :scope-attributes :key "runtime.pool" :type :int64}]}]}))

(defn- installed [target]
  (let [approved (compiled) columns (registry/expected-columns (registry/prepare approved))
        observed (atom {}) index (atom 0)]
    (assoc
     (installer/install-approved!
      (backend/memory-backend) approved
      {:target target
       :observe-columns #(vector {:signal :metrics :table "otel_metrics_gauge"
                                  :columns @observed})
       :execute-ddl! (fn [_]
                       (let [{:keys [name type]} (nth columns @index)]
                         (swap! index inc) (swap! observed assoc name type)))})
     ::target target)))

(defn- pair [row installation key]
  (let [physical (:physical (first (filter #(= key (:key %))
                                          (get-in installation [:record :manifest :fields]))))]
    [(get row (:value-column physical)) (get row (:status-column physical))]))

(defn- collected [scope attributes]
  [{:scope scope
    :metrics [{:type :gauge :name "queue.depth" :description "" :unit ""
               :data-points [{:value 2.0 :time-unix-nano 1000000000
                              :attributes attributes}]}]}])

(defn- exported [descriptor-set target resource scope attributes]
  (let [received (atom nil)
        writer (support/call-with-qualified-native
                #(exporter/exporter {:connection target :create-schema? false
                                     :signals #{:metrics}
                                     :typed-gauge-descriptors descriptor-set}))]
    (with-redefs [chdb/insert-json-rows!
                  (fn [_ table columns payload]
                    (reset! received {:table table :columns columns
                                      :rows (mapv json/read-str
                                                  (remove str/blank?
                                                          (str/split-lines payload)))})
                    {:count 1})]
      (when-not (export/export-metrics! writer resource (collected scope attributes))
        (throw (exporter/last-error writer))))
    @received))

(defn run [check]
  (println "confirmed typed gauge projection")
  (with-open [target (support/connection)]
    (let [installation (installed target) capability (:descriptor-set installation)
          target (::target installation) projector (projection/gauge-projector capability target)
          valid (projector {:resource {:attributes {"service.tier" "gold"}}
                            :scope {:attributes {"runtime.pool" 7}}
                            :point {:attributes {:queue.ready false
                                                 :queue.depth 9007199254740993}}})]
      (check "only bounded gauge and sum targets join physical support"
             [true true true true true false]
             [(identity/physically-supported? identity/gauge-attribute-target)
              (identity/physically-supported? (identity/target :metrics "otel_metrics_gauge" :resource-attributes))
              (identity/physically-supported? (identity/target :metrics "otel_metrics_gauge" :scope-attributes))
              (identity/physically-supported? (identity/target :metrics "otel_metrics_sum" :metric-attributes))
              (identity/physically-supported? (identity/target :metrics "otel_metrics_sum" :resource-attributes))
              (identity/physically-supported? (identity/target :metrics "otel_metrics_histogram" :metric-attributes))])
      (check "gauge typed point resource and scope values preserve their locations"
             [[false 3] [9007199254740993 3] ["gold" 3] [7 3]]
             [(pair valid installation "queue.ready") (pair valid installation "queue.depth")
              (pair valid installation "service.tier") (pair valid installation "runtime.pool")])
      (check "gauge absent and invalid status remain explicit"
             [[[false 1] [0 1]] [[false 4] [0 4]]]
             [(mapv #(pair (projector {:attributes {}}) installation %) ["queue.ready" "queue.depth"])
              (mapv #(pair (projector {:attributes {:queue.ready "false" :queue.depth 9223372036854775808}}) installation %)
                    ["queue.ready" "queue.depth"])])
      (let [resource {:attributes {"service.tier" "gold"
                                   "resource.generic" "kept-generic"}}
            scope {:name "typed-gauge"
                   :attributes {"runtime.pool" 7
                                "scope.generic" "kept-generic"}}
            {:keys [table columns rows]} (exported capability target resource scope
                                                   {:queue.ready false :queue.depth 9007199254740993})
            fields (projection/confirmed-gauge-fields capability target)
            typed-columns (vec (mapcat (fn [field] [(get-in field [:physical :value-column])
                                                     (get-in field [:physical :status-column])]) fields))
            row (first rows)]
        (check "typed gauge export targets only the gauge table" "otel_metrics_gauge" table)
        (check "typed gauge insert uses only the gauge schema plus owned columns"
               (into (get schema/clickstack-metric-insert-columns :gauge) typed-columns)
               columns)
        (check "export-metrics projects resource scope and point fields while retaining generic maps"
               [{"resource.generic" "kept-generic" "service.tier" "gold"}
                {"runtime.pool" "7" "scope.generic" "kept-generic"}
                {"queue.ready" "false" "queue.depth" "9007199254740993"}
                ["gold" 3] [7 3] [false 3] [9007199254740993 3]]
               [(get row "ResourceAttributes") (get row "ScopeAttributes")
                (get row "Attributes") (pair row installation "service.tier")
                (pair row installation "runtime.pool") (pair row installation "queue.ready")
                (pair row installation "queue.depth")]))
      (check "gauge capability cannot enter trace projection"
             :otel.exporter.chdb.attribute-projection/signal-mismatch
             (try (projection/trace-projector capability target) nil
                  (catch Throwable error (:type (ex-data error)))))
      (check "gauge capability cannot cross connections"
             :otel.exporter.chdb.attribute-projection/target-mismatch
             (try (exporter/exporter {:connection (atom :other) :create-schema? false
                                      :signals #{:metrics} :typed-gauge-descriptors capability}) nil
                  (catch Throwable error (:type (ex-data error))))))))

(defn -main [& _]
  (let [failures (atom 0)]
    (run (fn [label expected actual]
           (if (= expected actual)
             (println "  ok  " label)
             (do (swap! failures inc)
                 (println "  FAIL" label "- expected" (pr-str expected) "got" (pr-str actual))))))
    (when (pos? @failures)
      (throw (ex-info "typed gauge pure checks failed" {:failures @failures})))
    (println "all typed gauge pure checks passed")))
