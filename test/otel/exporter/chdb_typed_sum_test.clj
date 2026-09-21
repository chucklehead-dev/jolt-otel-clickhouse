(ns otel.exporter.chdb-typed-sum-test
  "Pure bounded contract for typed sum attributes.

  Native DDL and loopback/readback evidence deliberately remain a subsequent
  slice; this verifies only capability ownership, row projection, and the
  exporter core's additive insert selection."
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
            [otel.exporter.chdb-test-support :as support]
            [otel.sdk.export :as export]))

(defn- compiled []
  (manifest/compile-manifest
   {:dataset-id "telemetry-prod" :application-id "sum-worker"
    :lineage "sum-worker-v1" :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema :authority :advice
      :source "advice/sum-worker.edn"
      :entries [{:signal :metrics :table "otel_metrics_sum"
                 :location :resource-attributes :key "service.ready" :type :boolean}
                {:signal :metrics :table "otel_metrics_sum"
                 :location :scope-attributes :key "scope.workers" :type :int64}
                {:signal :metrics :table "otel_metrics_sum"
                 :location :metric-attributes :key "request.success" :type :boolean}
                {:signal :metrics :table "otel_metrics_sum"
                 :location :metric-attributes :key "request.count" :type :int64}]}]}))

(defn- histogram-compiled []
  (manifest/compile-manifest
   {:dataset-id "telemetry-prod" :application-id "histogram-crossing-control"
    :lineage "histogram-crossing-control-v1" :version 1
    :fragments [{:schema manifest/reviewed-fragment-schema :authority :advice
                 :source "advice/histogram-crossing-control.edn"
                 :entries [{:signal :metrics :table "otel_metrics_histogram"
                            :location :metric-attributes :key "histogram.tier" :type :string}]}]}))

(defn- installed [target]
  (let [approved (compiled) columns (registry/expected-columns (registry/prepare approved))
        observed (atom {}) index (atom 0)]
    (assoc
     (installer/install-approved!
      (backend/memory-backend) approved
      {:target target
       :observe-columns #(vector {:signal :metrics :table "otel_metrics_sum"
                                  :columns @observed})
       :execute-ddl! (fn [_]
                       (let [{:keys [name type]} (nth columns @index)]
                         (swap! index inc) (swap! observed assoc name type)))})
     ::target target)))

(defn- installed-histogram [target]
  (let [approved (histogram-compiled)
        columns (registry/expected-columns (registry/prepare approved))
        observed (atom {}) index (atom 0)]
    (assoc
     (installer/install-approved!
      (backend/memory-backend) approved
      {:target target
       :observe-columns #(vector {:signal :metrics :table "otel_metrics_histogram"
                                  :columns @observed})
       :execute-ddl! (fn [_]
                       (let [{:keys [name type]} (nth columns @index)]
                         (swap! index inc) (swap! observed assoc name type)))})
     ::target target)))

(defn- pair [row installation key]
  (let [physical (:physical (first (filter #(= key (:key %))
                                          (get-in installation [:record :manifest :fields]))))]
    [(get row (:value-column physical)) (get row (:status-column physical))]))

(defn- collected [attributes]
  [{:scope {:name "typed-sum" :attributes {"scope.workers" 7}}
    :metrics [{:type :sum :name "request.count" :description "" :unit "1"
               :temporality :cumulative :monotonic? true
               :data-points [{:value 2.0 :time-unix-nano 1000000000
                              :attributes attributes}]}]}])

(defn- exported [descriptor-set target attributes]
  (let [received (atom nil)
        writer (support/call-with-qualified-native
                #(exporter/exporter {:connection target :create-schema? false
                                     :signals #{:metrics}
                                     :typed-sum-descriptors descriptor-set}))]
    (with-redefs [chdb/insert-json-rows!
                  (fn [_ table columns payload]
                    (reset! received {:table table :columns columns
                                      :rows (mapv json/read-str
                                                  (remove str/blank?
                                                          (str/split-lines payload)))})
                    {:count 1})]
      (when-not (export/export-metrics! writer {:attributes {"service.ready" false}}
                                       (collected attributes))
        (throw (exporter/last-error writer))))
    @received))

(defn- unsupported-target-error [table location]
  (try
    (registry/prepare
     (manifest/compile-manifest
      {:dataset-id "telemetry-prod" :application-id "rejected-metric-target"
       :lineage "rejected-metric-target-v1" :version 1
       :fragments
       [{:schema manifest/reviewed-fragment-schema :authority :advice
         :source "advice/rejected-metric-target.edn"
         :entries [{:signal :metrics :table table :location location
                    :key "not.promoted" :type :string}]}]}))
    nil
    (catch Throwable error (:type (ex-data error)))))

(defn run [check]
  (println "confirmed typed sum projection")
  (with-open [target (support/connection)]
    (let [installation (installed target) capability (:descriptor-set installation)
          target (::target installation) projector (projection/sum-projector capability target)
          valid (projector {:resource {:attributes {"service.ready" false}}
                            :scope {:attributes {"scope.workers" 7}}
                            :point {:attributes {:request.success false
                                                 :request.count 9007199254740993}}})]
      (check "sum values preserve resource scope false and exact Int64"
             [[false 3] [7 3] [false 3] [9007199254740993 3]]
             [(pair valid installation "service.ready")
              (pair valid installation "scope.workers")
              (pair valid installation "request.success")
              (pair valid installation "request.count")])
      (check "sum absent and invalid status remain explicit"
             [[[false 1] [0 1]] [[false 4] [0 4]]]
             [(mapv #(pair (projector {:attributes {}}) installation %)
                    ["request.success" "request.count"])
              (mapv #(pair (projector {:attributes {:request.success "false"
                                                    :request.count 9223372036854775808}})
                           installation %)
                    ["request.success" "request.count"])])
      (let [{:keys [table columns rows]} (exported capability target
                                                  {:request.success false
                                                   :request.count 9007199254740993})
            fields (projection/confirmed-sum-fields capability target)
            typed-columns (vec (mapcat (fn [field] [(get-in field [:physical :value-column])
                                                    (get-in field [:physical :status-column])]) fields))
            row (first rows)]
        (check "typed sum export targets only the sum table" "otel_metrics_sum" table)
        (check "typed sum insert names additive columns"
               typed-columns (vec (take-last (count typed-columns) columns)))
        (check "typed sum export retains generic maps and projects all locations"
               [{"request.success" "false" "request.count" "9007199254740993"}
                [false 3] [7 3] [false 3] [9007199254740993 3]]
               [(get row "Attributes") (pair row installation "service.ready")
                (pair row installation "scope.workers")
                (pair row installation "request.success")
                (pair row installation "request.count")]))
      (check "sum capability cannot enter gauge projection"
             :otel.exporter.chdb.attribute-projection/signal-mismatch
             (try (projection/gauge-projector capability target) nil
                  (catch Throwable error (:type (ex-data error)))))
      (check "sum resource and scope targets are physically supported"
             [true true]
             [(identity/physically-supported?
               (identity/target :metrics "otel_metrics_sum" :resource-attributes))
              (identity/physically-supported?
               (identity/target :metrics "otel_metrics_sum" :scope-attributes))])
      (check "histogram targets are physically supported for their own capability"
             [true true true]
             (mapv #(identity/physically-supported?
                     (identity/target :metrics "otel_metrics_histogram" %))
                   [:metric-attributes :resource-attributes :scope-attributes]))
      (let [histogram-capability (:descriptor-set (installed-histogram target))]
        (check "histogram capability cannot enter sum projection"
               :otel.exporter.chdb.attribute-projection/signal-mismatch
               (try (projection/sum-projector histogram-capability target) nil
                    (catch Throwable error (:type (ex-data error)))))))))

(defn -main [& _]
  (let [failures (atom 0)]
    (run (fn [label expected actual]
           (if (= expected actual)
             (println "  ok  " label)
             (do (swap! failures inc)
                 (println "  FAIL" label "- expected" (pr-str expected) "got" (pr-str actual))))))
    (when (pos? @failures)
      (throw (ex-info "typed sum pure checks failed" {:failures @failures})))
    (println "all typed sum pure checks passed")))
