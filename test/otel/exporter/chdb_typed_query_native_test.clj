(ns otel.exporter.chdb-typed-query-native-test
  (:require [db.jdbc]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.explorer :as explorer]
            [otel.exporter.chdb.schema :as schema]
            [otel.sdk.export :as export]))

(def malicious-key "checkout.count') OR 1=1 --")
(def empty-key "checkout.note")
(def int64-max 9223372036854775807)
(def timestamp-base 1700000000000000000)

(defn- compiled-manifest []
  (manifest/compile-manifest
   {:dataset-id "telemetry-prod" :application-id "checkout"
    :lineage "checkout-v1" :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema
      :authority :advice :source "advice/checkout.edn"
      :entries
      [{:location :span-attributes :key malicious-key :type :int64}
       {:location :span-attributes :key empty-key :type :string}]}]}))

(defn- observe-columns [connection]
  (into {}
        (map (juxt :name :type))
        (jdbc/fetch connection "DESCRIBE TABLE otel_traces")))

(defn- span [n attributes]
  {:name (str "typed-native-" n) :kind :internal
   :start-time-unix-nano (+ timestamp-base n)
   :end-time-unix-nano (+ timestamp-base n 1)
   :span-context
   {:trace-id (str "0000000000000000000000000000000" n)
    :span-id (str "000000000000000" n)}
   :resource {:attributes {}} :scope {:name "typed-native-test"}
   :attributes attributes :events [] :links [] :status {:code :unset}})

(defn- export! [exporter spans]
  (when-not (export/export-spans! exporter spans)
    (throw (or (chdb-export/last-error exporter)
               (ex-info "native typed span export failed" {})))))

(defn run [check]
  (println "native capability-bound typed span round trip")
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (schema/ensure-schema! connection)
    (let [installation
          (installer/install-approved!
           (backend/memory-backend) (compiled-manifest)
           {:target connection
            :observe-columns #(observe-columns connection)
            :execute-ddl! #(jdbc/execute! connection %)})
          descriptor-set (:descriptor-set installation)
          typed-exporter
          (chdb-export/exporter
           {:connection connection :create-schema? false :signals #{:spans}
            :typed-span-descriptors descriptor-set})
          legacy-exporter
          (chdb-export/exporter
           {:connection connection :create-schema? false :signals #{:spans}})]
      ;; Statuses 3/2, 4, and 1 are emitted through the confirmed projector.
      ;; A capability-free export leaves additive status columns at their
      ;; status-0 defaults and exercises the historical generic fallback.
      (export! typed-exporter
               [(span 1 {malicious-key int64-max empty-key ""})
                (span 2 {malicious-key "not-an-int"})
                (span 3 {})])
      (export! legacy-exporter [(span 4 {malicious-key "legacy"})])
      (let [actual
            (sort-by (juxt :attribute-key :typed-status :value)
                     (explorer/typed-span-values
                      connection descriptor-set
                      {:signal :spans :keys [malicious-key empty-key]
                       :start-unix-nano timestamp-base
                       :end-unix-nano (+ timestamp-base 1000)
                       :limit 20 :max-text-length 256}))]
        (check "real DDL, JSON inserts, Map lookup, and typed query round trip"
               (sort-by
                (juxt :attribute-key :typed-status :value)
                [{:attribute-key malicious-key :count 1 :signal :spans
                  :source :generic-fallback :typed-status 0 :value "legacy"}
                 {:attribute-key malicious-key :count 1 :signal :spans
                  :source :typed :typed-status 3 :value (str int64-max)}
                 {:attribute-key malicious-key :count 1 :signal :spans
                  :source :generic-fallback :typed-status 4
                  :value "not-an-int"}
                 {:attribute-key empty-key :count 1 :signal :spans
                  :source :typed :typed-status 2 :value ""}])
               actual)
        (check "status-1 absent rows are not published"
               false
               (boolean (some #(= 1 (:typed-status %)) actual)))))))
