(ns otel.exporter.chdb-typed-log-explorer-test
  (:require [clojure.string :as str]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.typed-log-explorer :as log-explorer]))

(defn- installed []
  (let [approved
        (manifest/compile-manifest
         {:dataset-id "telemetry-prod" :application-id "log-query"
          :lineage "log-query-v1" :version 7
          :fragments
          [{:schema manifest/reviewed-fragment-schema
            :authority :advice :source "advice/log-query.edn"
            :entries
            [{:signal :logs :table "otel_logs" :location :log-attributes
              :key "job.name" :type :string}
             {:signal :logs :table "otel_logs" :location :log-attributes
              :key "job.complete" :type :boolean}
             {:signal :logs :table "otel_logs" :location :log-attributes
              :key "job.attempt" :type :int64}]}]})
        columns (registry/expected-columns (registry/prepare approved))
        observed (atom {})
        next-column (atom 0)
        target (atom :log-query-target)
        installation
        (installer/install-approved!
         (backend/memory-backend) approved
         {:target target
          :observe-columns #(vector {:columns @observed :signal :logs
                                     :table "otel_logs"})
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

(defn- request [field value operator]
  {:attribute-key (:key field) :attribute-location :log-attributes
   :schema-binding (binding field) :signal :logs
   :start-unix-nano 1700000000000000000
   :end-unix-nano 1700000001000000000
   :operator operator :value value :limit 10 :max-text-length 64})

(defn- coverage-request [field]
  (select-keys (request field nil nil)
               [:attribute-key :attribute-location :schema-binding :signal
                :start-unix-nano :end-unix-nano]))

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(defn run [check]
  (println "schema-bound typed log explorer")
  (let [installation (installed)
        descriptors (:descriptor-set installation)
        target (::target installation)
        attempt (field installation "job.attempt")
        calls (atom [])
        coverage {:valid 2 :presentempty 0 :absent 1 :invalid 1
                  :historicalfallback 1 :historicalunavailable 1
                  :unknownstatus 0 :total 6}
        match {:timestampunixnano 1700000000500000000
               :traceid "trace" :spanid "span" :servicename "worker"
               :severitytext "INFO" :body "attempted" :attributevalue
               9007199254740993 :typedstatus 3}
        result
        (with-redefs [jdbc/fetch
                      (fn [_ sqlvec options]
                        (swap! calls conj [sqlvec options])
                        (if (= 1 (:max-rows options)) [coverage] [match]))]
          (log-explorer/typed-log-filtered-records
           target descriptors (request attempt 9007199254740993 :gte)))]
    (check "capability names every promoted scalar type"
           {:operators {:boolean [:eq] :int64 [:eq :gte :lt]
                        :string [:eq :prefix :contains]}
            :signals [:logs] :types [:boolean :int64 :string]}
           (log-explorer/supported-typed-log-filters))
    (check "exact schema identity and six-way coverage survive"
           (merge (binding attempt)
                  {:coverage {:valid 2 :present-empty 0 :absent 1 :invalid 1
                              :historical-untyped-fallback 1
                              :historical-untyped-unavailable 1 :total 6}
                   :filter {:operator :gte :value 9007199254740993}
                   :matches [(merge (binding attempt)
                                    {:attribute-value 9007199254740993
                                     :body "attempted" :service-name "worker"
                                     :severity-text "INFO" :signal :logs
                                     :source :typed :span-id "span"
                                     :timestamp-unix-nano 1700000000500000000
                                     :trace-id "trace" :typed-status 3})]
                   :signal :logs})
           result)
    (check "logical key/value are parameters, never SQL identifiers"
           [true true false]
           [(= "job.attempt" (second (first (first @calls))))
            (= 9007199254740993
               (nth (first (second @calls)) (- (count (first (second @calls))) 2)))
            (boolean
             (some #(str/includes? (first (first %)) "job.attempt") @calls))])
    (let [stale (assoc-in (coverage-request attempt)
                          [:schema-binding :manifest-version] 8)]
      (check "changed schema version is stale before query"
             :otel.exporter.chdb.typed-log-explorer/stale-binding
             (:type (thrown-data
                     #(log-explorer/typed-log-coverage target descriptors stale)))))
    (check "capability cannot cross connections"
           :otel.exporter.chdb.attribute-projection/target-mismatch
           (:type (thrown-data
                   #(log-explorer/typed-log-coverage
                     (atom :other) descriptors (coverage-request attempt)))))
    (check "wrong signal cannot select the log target"
           :otel.exporter.chdb.typed-log-explorer/unsupported-signal
           (:type (thrown-data
                   #(log-explorer/typed-log-coverage
                     target descriptors (assoc (coverage-request attempt)
                                               :signal :spans)))))))

(defn -main [& _]
  (let [failures (atom 0)]
    (run (fn [label expected actual]
           (if (= expected actual)
             (println "  ok  " label)
             (do (swap! failures inc)
                 (println "  FAIL" label "expected" (pr-str expected)
                          "got" (pr-str actual))))))
    (when (pos? @failures)
      (throw (ex-info "typed log explorer checks failed"
                      {:failures @failures})))
    (println "all typed log explorer checks passed")))
