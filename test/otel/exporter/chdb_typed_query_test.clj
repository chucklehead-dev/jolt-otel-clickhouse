(ns otel.exporter.chdb-typed-query-test
  (:require [db.jdbc]
            [clojure.string :as str]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [otel.context :as context]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.explorer :as explorer]))

(def malicious-key "checkout.count') OR 1=1 --")

(defn- installed []
  (let [compiled
        (manifest/compile-manifest
         {:dataset-id "telemetry-prod" :application-id "checkout"
          :lineage "checkout-v1" :version 1
          :fragments
          [{:schema manifest/reviewed-fragment-schema
            :authority :advice :source "advice/checkout.edn"
            :entries
            [{:location :span-attributes
              :key malicious-key :type :int64}
             {:location :span-attributes
              :key "checkout.complete" :type :boolean}]}]})
        columns (registry/expected-columns (registry/prepare compiled))
        observed (atom {})
        next-column (atom 0)
        target (atom :query-target)
        installation
        (installer/install-approved!
         (backend/memory-backend) compiled
         {:target target
          :observe-columns #(into {} @observed)
          :execute-ddl!
          (fn [_]
            (let [{:keys [name type]} (nth columns @next-column)]
              (swap! next-column inc)
              (swap! observed assoc name type)))})]
    {:descriptor-set (:descriptor-set installation)
     :descriptors (:descriptors installation)
     :installation installation :target target}))

(defn- request
  ([keys] (request keys {}))
  ([keys overrides]
   (merge {:signal :spans :keys keys
           :start-unix-nano 1700000000000000000
           :end-unix-nano 1700000001000000000
           :limit 7 :max-text-length 42}
          overrides)))

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(defn run [check]
  (println "capability-bound typed span queries")
  (let [{:keys [descriptor-set descriptors installation target]} (installed)
        calls (atom [])]
    (with-redefs
      [jdbc/fetch
       (fn [connection sqlvec options]
         (swap! calls conj [connection sqlvec options
                            (context/instrumentation-suppressed?)])
         (if (= malicious-key (second sqlvec))
           [{:value "7" :typedstatus 3 :count 4}]
           [{:value "not-bool" :typedstatus 4 :count 2}]))]
      (check "approved keys query typed and generic-fallback distributions"
             [{:attribute-key malicious-key :count 4 :signal :spans
               :source :typed :typed-status 3 :value "7"}
              {:attribute-key "checkout.complete" :count 2 :signal :spans
               :source :generic-fallback :typed-status 4
               :value "not-bool"}]
             (explorer/typed-span-values
              target descriptor-set
              (request [malicious-key "checkout.complete"]))))
    (check "typed query runs once per requested key under suppression"
           [2 [true true] [{:max-rows 7} {:max-rows 7}]]
           [(count @calls) (mapv #(nth % 3) @calls)
            (mapv #(nth % 2) @calls)])
    (doseq [[_ [sql key text-length start end limit] _ _] @calls]
      (check "logical key and every caller scalar remain JDBC parameters"
             [42 1700000000000000000 1700000001000000000 7]
             [text-length start end limit])
      (check "typed query uses only fixed table and library-owned columns"
             true
             (and (str/includes? sql "FROM otel_traces")
                  (str/includes? sql "SpanAttributes[?]")
                  (str/includes? sql "IN (2, 3)")
                  (str/includes? sql "IN (0, 4)")
                  (str/includes? sql "`av_sp_")
                  (str/includes? sql "`as_sp_")
                  (not (str/includes? sql key))
                  (not (str/includes? sql "system.tables")))))

    (let [queries (atom 0)
          invalid
          [[(request ["unknown.key"])
            :otel.exporter.chdb.explorer/unknown-typed-key]
           [(request [malicious-key malicious-key])
            :otel.exporter.chdb.explorer/duplicate-typed-keys]
           [(request [malicious-key] {:signal :logs})
            :otel.exporter.chdb.explorer/unsupported-typed-signal]
           [(assoc (request [malicious-key]) :table "system.tables")
            :otel.exporter.chdb.explorer/unsupported-request-key]]]
      (with-redefs [jdbc/fetch (fn [& _] (swap! queries inc) [])]
        (doseq [[bad expected-type] invalid]
          (check "unknown, duplicate, wrong-signal, and table input fail closed"
                 expected-type
                 (:type
                  (thrown-data
                   #(explorer/typed-span-values target descriptor-set bad)))))
        (check "invalid typed selections execute no query" 0 @queries)))

    (check "bare descriptor vectors have no query authority"
           :otel.exporter.chdb.attribute-registry-installer/unconfirmed-descriptors
           (:type
            (thrown-data
             #(explorer/typed-span-values
               target descriptors (request [malicious-key])))))
    (check "typed query capability cannot cross connection identity"
           :otel.exporter.chdb.attribute-projection/target-mismatch
           (:type
            (thrown-data
             #(explorer/typed-span-values
               (atom :other-target) descriptor-set
               (request [malicious-key])))))
    (with-redefs [jdbc/fetch
                  (fn [& _] [{:value "corrupt" :typedstatus 9 :count 1}])]
      (check "unknown persisted status fails instead of becoming fallback"
             :otel.exporter.chdb.explorer/invalid-typed-result
             (:type
              (thrown-data
               #(explorer/typed-span-values
                 target descriptor-set (request [malicious-key]))))))
    (check "legacy generic explorer fields remain available without capability"
           true
           (contains? (set (explorer/supported-fields :spans))
                      :http-request-method))))
