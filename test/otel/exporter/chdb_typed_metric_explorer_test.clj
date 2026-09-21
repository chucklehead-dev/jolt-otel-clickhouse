(ns otel.exporter.chdb-typed-metric-explorer-test
  (:require [clojure.string :as str]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.typed-metric-explorer :as metric-explorer]))

(defn- installed [metric-kind]
  (let [table (case metric-kind
                :gauge "otel_metrics_gauge"
                :sum "otel_metrics_sum"
                :histogram "otel_metrics_histogram")
        approved (manifest/compile-manifest
                  {:dataset-id "telemetry-prod" :application-id "metric-query"
                   :lineage "metric-query-v1" :version 7
                   :fragments [{:schema manifest/reviewed-fragment-schema
                                :authority :advice :source "advice/metric-query.edn"
                                :entries [{:signal :metrics :table table
                                           :location :resource-attributes :key "service.tier" :type :string}
                                          {:signal :metrics :table table
                                           :location :scope-attributes :key "runtime.pool" :type :int64}
                                          {:signal :metrics :table table
                                           :location :metric-attributes :key "queue.ready" :type :boolean}]}]})
        columns (registry/expected-columns (registry/prepare approved))
        observed (atom {}) next-column (atom 0) target (atom metric-kind)
        installation (installer/install-approved!
                      (backend/memory-backend) approved
                      {:target target
                       :observe-columns #(vector {:columns @observed :signal :metrics
                                                   :table table})
                       :execute-ddl! (fn [_]
                                       (let [{:keys [name type]} (nth columns @next-column)]
                                         (swap! next-column inc)
                                         (swap! observed assoc name type)))})]
    (assoc installation ::target target)))

(defn- field [installation key]
  (first (filter #(= key (:key %))
                 (get-in installation [:record :manifest :fields]))))

(defn- binding [field]
  {:attribute-key (:key field) :attribute-location (:location field)
   :attribute-type (:type field) :field-id (:id field)
   :manifest-version (get-in field [:identity :version])})

(defn- request [metric-kind field value operator]
  {:schema-binding (binding field) :signal :metrics :metric-kind metric-kind
   :start-unix-nano 1700000000000000000
   :end-unix-nano 1700000001000000000
   :operator operator :value value :limit 10 :max-text-length 64})

(defn- coverage-request [metric-kind field]
  (select-keys (request metric-kind field nil nil)
               [:schema-binding :signal :metric-kind :start-unix-nano :end-unix-nano]))

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(defn run [check]
  (println "schema-bound typed gauge explorer")
  (let [installation (installed :gauge)
        descriptors (:descriptor-set installation)
        target (::target installation)
        resource (field installation "service.tier")
        scope (field installation "runtime.pool")
        ready (field installation "queue.ready")
        calls (atom [])
        coverage {:valid 2 :presentempty 0 :absent 1 :invalid 1
                  :historicalfallback 1 :historicalunavailable 1
                  :unknownstatus 0 :total 6}
        match {:timestampunixnano 1700000000000000000
               :metricname "queue.depth" :metricunit "1" :metricvalue 2.5
               :servicename "worker" :scopename "queue" :attributevalue false
               :typedstatus 3}
        result (with-redefs [jdbc/fetch
                             (fn [_ sqlvec options]
                               (swap! calls conj [sqlvec options])
                               (if (= 1 (:max-rows options)) [coverage] [match]))]
                 (metric-explorer/typed-gauge-filtered-points
                  target descriptors (request :gauge ready false :eq)))]
    (check "gauge capability publishes one closed filter vocabulary"
           {:operators {:boolean [:eq] :int64 [:eq :gte :lt]
                        :string [:eq :prefix :contains]}
            :signals [:metrics] :metric-kinds [:gauge]
            :locations [:resource-attributes :scope-attributes :metric-attributes]
            :types [:boolean :int64 :string]}
           (metric-explorer/supported-typed-gauge-filters))
    (check "discovery returns schema-bound gauge fields without physical names"
           #{[:resource-attributes :string] [:scope-attributes :int64]
             [:metric-attributes :boolean]}
           (set (map (fn [{:keys [schema-binding]}]
                       [(:attribute-location schema-binding) (:attribute-type schema-binding)])
                     (metric-explorer/typed-gauge-fields target descriptors))))
    (check "exact gauge identity and six-way coverage survive"
           (merge (binding ready)
                  {:coverage {:valid 2 :present-empty 0 :absent 1 :invalid 1
                              :historical-untyped-fallback 1
                              :historical-untyped-unavailable 1 :total 6}
                   :filter {:operator :eq :value false}
                   :matches [(merge (binding ready)
                                    {:attribute-value false :metric-name "queue.depth"
                                     :metric-unit "1" :metric-value 2.5
                                     :scope-name "queue" :service-name "worker"
                                     :signal :metrics :metric-kind :gauge :source :typed
                                     :timestamp-unix-nano 1700000000000000000
                                     :typed-status 3})]
                   :signal :metrics :metric-kind :gauge})
           result)
    (check "logical key/value remain JDBC parameters and never SQL identifiers"
           [true true false]
           [(= "queue.ready" (second (first (first @calls))))
            (= false (nth (first (second @calls)) (- (count (first (second @calls))) 2)))
            (boolean (some #(str/includes? (first (first %)) "queue.ready") @calls))])
    (let [captured (atom nil)]
      (with-redefs [jdbc/fetch (fn [_ sqlvec _] (reset! captured sqlvec) [coverage])]
        (metric-explorer/typed-gauge-coverage target descriptors (coverage-request :gauge resource)))
      (check "resource coverage selects its fixed compatibility map"
             true (str/includes? (first @captured) "mapContains(ResourceAttributes, ?)")))
    (let [captured (atom nil)]
      (with-redefs [jdbc/fetch (fn [_ sqlvec _] (reset! captured sqlvec) [coverage])]
        (metric-explorer/typed-gauge-coverage target descriptors (coverage-request :gauge scope)))
      (check "scope coverage selects its fixed compatibility map"
             true (str/includes? (first @captured) "mapContains(ScopeAttributes, ?)")))
    (check "changed schema version is stale before query"
           :otel.exporter.chdb.typed-metric-explorer/stale-binding
           (:type (thrown-data #(metric-explorer/typed-gauge-coverage
                                 target descriptors
                                 (assoc-in (coverage-request :gauge ready)
                                           [:schema-binding :manifest-version] 8)))))
    (check "gauge capability cannot cross connections"
           :otel.exporter.chdb.attribute-projection/target-mismatch
           (:type (thrown-data #(metric-explorer/typed-gauge-coverage
                                 (atom :other) descriptors (coverage-request :gauge ready)))))
    (let [queries (atom 0)
          invalid [(assoc (request :gauge ready false :eq) :metric-kind :sum)
                   (assoc (request :gauge ready false :eq) :operator :contains)
                   (assoc (request :gauge ready false :eq) :value "false")
                   (assoc (request :gauge ready false :eq) :limit 101)]]
      (with-redefs [jdbc/fetch (fn [& _] (swap! queries inc) [coverage])]
        (doseq [bad invalid]
          (thrown-data #(metric-explorer/typed-gauge-filtered-points
                         target descriptors bad))))
      (check "invalid typed gauge requests execute no query" 0 @queries)))
  (let [installation (installed :sum)
        descriptors (:descriptor-set installation)
        target (::target installation)
        ready (field installation "queue.ready")
        coverage {:valid 2 :presentempty 0 :absent 1 :invalid 1
                  :historicalfallback 1 :historicalunavailable 1
                  :unknownstatus 0 :total 6}
        match {:timestampunixnano 1700000000000000000
               :metricname "queue.depth" :metricunit "1" :metricvalue 2.5
               :servicename "worker" :scopename "queue" :attributevalue false
               :typedstatus 3}
        calls (atom [])
        result (with-redefs [jdbc/fetch
                             (fn [_ sqlvec options]
                               (swap! calls conj [sqlvec options])
                               (if (= 1 (:max-rows options)) [coverage] [match]))]
                 (metric-explorer/typed-sum-filtered-points
                  target descriptors (request :sum ready false :eq)))]
    (check "sum capability publishes the same closed filter vocabulary"
           (assoc (metric-explorer/supported-typed-gauge-filters) :metric-kinds [:sum])
           (metric-explorer/supported-typed-sum-filters))
    (check "sum discovery returns only sum schema bindings"
           #{[:resource-attributes :string] [:scope-attributes :int64]
             [:metric-attributes :boolean]}
           (set (map (fn [{:keys [schema-binding]}]
                       [(:attribute-location schema-binding) (:attribute-type schema-binding)])
                     (metric-explorer/typed-sum-fields target descriptors))))
    (check "sum result identifies the sum table and six-way coverage"
           [:sum {:valid 2 :present-empty 0 :absent 1 :invalid 1
                  :historical-untyped-fallback 1
                  :historical-untyped-unavailable 1 :total 6}
            true]
           [(:metric-kind result) (:coverage result)
            (boolean (some #(str/includes? (first (first %)) "FROM otel_metrics_sum") @calls))])
    (let [queries (atom 0)]
      (with-redefs [jdbc/fetch (fn [& _] (swap! queries inc) [coverage])]
        (thrown-data #(metric-explorer/typed-gauge-filtered-points
                       target descriptors (request :sum ready false :eq))))
      (check "gauge operation rejects a sum capability before query" 0 @queries)))
  (let [installation (installed :histogram)
        descriptors (:descriptor-set installation)
        target (::target installation)
        ready (field installation "queue.ready")
        coverage {:valid 1 :presentempty 0 :absent 0 :invalid 0
                  :historicalfallback 0 :historicalunavailable 0
                  :unknownstatus 0 :total 1}
        match {:timestampunixnano 1700000000000000000
               :metricname "queue.latency" :metricunit "ms" :metricvalue 3
               :servicename "worker" :scopename "queue" :attributevalue false
               :typedstatus 3}
        calls (atom [])
        result (with-redefs [jdbc/fetch
                             (fn [_ sqlvec options]
                               (swap! calls conj [sqlvec options])
                               (if (= 1 (:max-rows options)) [coverage] [match]))]
                 (metric-explorer/typed-histogram-filtered-points
                  target descriptors (request :histogram ready false :eq)))]
    (check "histogram capability is a closed table-bound filter surface"
           (assoc (metric-explorer/supported-typed-gauge-filters) :metric-kinds [:histogram])
           (metric-explorer/supported-typed-histogram-filters))
    (check "histogram discovery returns only histogram schema bindings"
           #{[:resource-attributes :string] [:scope-attributes :int64]
             [:metric-attributes :boolean]}
           (set (map (fn [{:keys [schema-binding]}]
                       [(:attribute-location schema-binding) (:attribute-type schema-binding)])
                     (metric-explorer/typed-histogram-fields target descriptors))))
    (check "histogram readback uses Count and its fixed table"
           [3 true]
           [(get-in result [:matches 0 :metric-value])
            (boolean (some #(str/includes? (first (first %)) "FROM otel_metrics_histogram") @calls))])
    (let [queries (atom 0)]
      (with-redefs [jdbc/fetch (fn [& _] (swap! queries inc) [coverage])]
        (thrown-data #(metric-explorer/typed-sum-filtered-points
                       target descriptors (request :histogram ready false :eq))))
      (check "sum operation rejects a histogram capability before query" 0 @queries))))

(defn -main [& _]
  (let [failures (atom 0)]
    (run (fn [label expected actual]
           (if (= expected actual)
             (println "  ok  " label)
             (do (swap! failures inc)
                 (println "  FAIL" label "expected" (pr-str expected)
                          "got" (pr-str actual))))))
    (when (pos? @failures)
      (throw (ex-info "typed gauge explorer checks failed" {:failures @failures})))
    (println "all typed gauge explorer checks passed")))
