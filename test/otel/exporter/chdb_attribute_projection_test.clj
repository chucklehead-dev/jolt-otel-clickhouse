(ns otel.exporter.chdb-attribute-projection-test
  (:require [db.jdbc]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.attribute-registry-store :as store]
            [otel.sdk.export :as export]))

(defn- compiled []
  (manifest/compile-manifest
   {:dataset-id "telemetry-prod" :application-id "checkout"
    :lineage "checkout-v1" :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema
      :authority :advice :source "advice/checkout.edn"
      :entries
      [{:signal :spans :table "otel_traces"
        :location :span-attributes :key "checkout.name" :type :string}
       {:signal :spans :table "otel_traces"
        :location :span-attributes :key "checkout.complete" :type :boolean}
       {:signal :spans :table "otel_traces"
        :location :span-attributes :key "checkout.count" :type :int64}]}]}))

(defn- installed []
  (let [manifest (compiled)
        columns (registry/expected-columns (registry/prepare manifest))
        observed (atom {})
        next-column (atom 0)
        target (atom :projection-target)]
    (assoc
     (installer/install-approved!
      (backend/memory-backend) manifest
      {:target target
       :observe-columns #(vector {:columns (into {} @observed)
                                  :signal :spans :table "otel_traces"})
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

(defn- span [attributes]
  {:name "typed-span" :kind :internal
   :start-time-unix-nano 1 :end-time-unix-nano 2
   :span-context {:trace-id "11111111111111111111111111111111"
                  :span-id "2222222222222222"}
   :resource {:attributes {}} :scope {:name "typed-test"}
   :attributes attributes :events [] :links [] :status {:code :unset}})

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(defn- exported-row [descriptor-set target attributes]
  (let [statement (atom nil)
        exporter (chdb-export/exporter
                  {:connection target :create-schema? false :signals #{:spans}
                   :typed-span-descriptors descriptor-set})]
    (with-redefs [jdbc/execute!
                  (fn [_ sql] (reset! statement sql) {:count 1})]
      (when-not (export/export-spans! exporter [(span attributes)])
        (throw (chdb-export/last-error exporter))))
    (json/read-str (second (str/split @statement #"FORMAT JSONEachRow\n" 2)))))

(defn run [check]
  (println "confirmed typed span projection")
  (let [installation (installed)
        descriptor-set (:descriptor-set installation)
        target (::target installation)
        projector (projection/span-projector descriptor-set target)
        valid (projector {"checkout.name" "cart"
                          :checkout.complete false
                          "checkout.count" 7})]
    (check "exact scalar values populate typed columns with valid status"
           [["cart" 3] [false 3] [7 3]]
           [(projected-pair valid installation "checkout.name")
            (projected-pair valid installation "checkout.complete")
            (projected-pair valid installation "checkout.count")])

    (let [missing (projector {})
          empty-and-invalid
          (projector {"checkout.name" ""
                      "checkout.complete" "false"
                      "checkout.count" 9223372036854775808})]
      (check "absent values use typed defaults with absent status"
             [["" 1] [false 1] [0 1]]
             [(projected-pair missing installation "checkout.name")
              (projected-pair missing installation "checkout.complete")
              (projected-pair missing installation "checkout.count")])
      (check "empty strings and invalid typed values remain distinguishable"
             [["" 2] [false 4] [0 4]]
             [(projected-pair empty-and-invalid installation "checkout.name")
              (projected-pair empty-and-invalid installation "checkout.complete")
              (projected-pair empty-and-invalid installation "checkout.count")]))

    (let [ambiguous (projector {"checkout.count" 1 :checkout.count 2})]
      (check "duplicate normalized keys fail row-local projection closed"
             [0 4]
             (projected-pair ambiguous installation "checkout.count")))

    (let [row (exported-row descriptor-set target
                            {"checkout.name" "cart"
                             "checkout.complete" true
                             "checkout.count" 7})]
      (check "export adds typed values while retaining the generic map fallback"
             [{"checkout.name" "cart"
               "checkout.complete" "true" "checkout.count" "7"}
              ["cart" 3] [true 3] [7 3]]
             [(get row "SpanAttributes")
              (projected-pair row installation "checkout.name")
              (projected-pair row installation "checkout.complete")
              (projected-pair row installation "checkout.count")]))

    (let [row (exported-row nil :legacy-target {"checkout.count" 7})]
      (check "legacy export without a capability remains generic-map only"
             [{"checkout.count" "7"} false]
             [(get row "SpanAttributes")
              (boolean (some #(str/starts-with? % "av_") (keys row)))]))

    (check "bare descriptors cannot substitute for installer confirmation"
           :otel.exporter.chdb.attribute-registry-installer/unconfirmed-descriptors
           (:type
            (thrown-data
             #(projection/span-projector (:descriptors installation) target))))

    (let [{:keys [descriptors record snapshot]}
          (installer/descriptor-set-data descriptor-set)
          forged
          (new otel.exporter.chdb.attribute_registry_installer.ConfirmedActiveDescriptorSet
               nil target record snapshot descriptors)]
      (check "ordinary public constructor use cannot forge the private issuer"
             :otel.exporter.chdb.attribute-registry-installer/unconfirmed-descriptors
             (:type (thrown-data
                     #(projection/span-projector forged target)))))

    (let [record-validations (atom 0)
          catalog-validations (atom 0)
          validate-record registry/validate-record
          validate-catalog store/validate-catalog
          field-counts
          (with-redefs [registry/validate-record
                        (fn [record]
                          (swap! record-validations inc)
                          (validate-record record))
                        store/validate-catalog
                        (fn [catalog]
                          (swap! catalog-validations inc)
                          (validate-catalog catalog))]
            (mapv (fn [_]
                    (count (projection/confirmed-span-fields
                            descriptor-set target)))
                  (range 3)))]
      (check "an immutable capability reuses its mint-time validation"
             [3 3 3]
             field-counts)
      (check "query-time field access does not revalidate the captured record"
             [0 0]
             [@record-validations @catalog-validations]))

    (check "an honest capability cannot cross exporter connection identity"
           :otel.exporter.chdb.attribute-projection/target-mismatch
           (:type
            (thrown-data
             #(chdb-export/exporter
               {:connection (atom :another-target) :create-schema? false
                :signals #{:spans}
                :typed-span-descriptors descriptor-set}))))

    (check "typed capability cannot use a newly owned db-spec connection"
           :otel.exporter.chdb/typed-descriptors-require-connection
           (:type
            (thrown-data
             #(chdb-export/exporter
               {:db-spec "chdb::memory:" :create-schema? false
                :signals #{:spans}
                :typed-span-descriptors descriptor-set}))))))
