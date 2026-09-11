(ns otel.exporter.chdb-attribute-registry-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [otel.exporter.chdb-attribute-bundle-fixture :as bundle-fixture]
            [otel.exporter.chdb.attribute-identity :as identity]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry :as registry]))

(defn- reviewed [entries]
  {:schema manifest/reviewed-fragment-schema
   :authority :advice
   :source "advice/checkout.edn"
   :entries (mapv #(merge {:signal :spans :table "otel_traces"} %) entries)})

(defn- compile-app
  ([application-id version]
   (compile-app application-id version
                [{:location :span-attributes
                  :key "checkout.remaining" :type :int64}
                 {:location :span-attributes
                  :key "checkout.complete" :type :boolean}]))
  ([application-id version entries]
   (manifest/compile-manifest
    {:dataset-id "telemetry-prod"
     :application-id application-id
     :lineage "checkout-v1"
     :version version
     :fragments [(reviewed entries)]})))

(defn- compile-bundle-app [revision version]
  (let [fragment
        (bundle-fixture/inferred
         "src/checkout.clj"
         "(ns checkout (:require [otel.trace :as trace]))
          (trace/set-attribute! span \"checkout.bundle-count\" (long value))")
        bundle
        (bundle-fixture/discovered-bundle
         [{:artifact
           (bundle-fixture/revision-artifact
            "io.github.example/checkout"
            "https://github.com/example/checkout"
            revision)
           :path "META-INF/otel/attribute-schema/checkout.edn"
           :fragment fragment}])]
    (manifest/compile-bundle-manifest
     {:dataset-id "telemetry-prod"
      :application-id "checkout-bundle"
      :lineage "checkout-bundle-v1"
      :version version
      :bundle bundle})))

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(defn- table-observation [table columns]
  [{:columns columns
    :signal (get identity/table-signals table)
    :table table}])

(defn- observed-schema
  ([record]
   (observed-schema record
                    (into {} (map (juxt :name :type)
                                  (registry/expected-columns record)))))
  ([record columns]
   (table-observation (:table (first (registry/expected-columns record)))
                      columns)))

(defn run [check]
  (println "typed attribute registry lifecycle")
  (let [compiled (compile-app "checkout" 1)
        prepared (registry/prepare compiled)
        columns (registry/expected-columns prepared)
        empty-plan (registry/reconcile prepared 1 (observed-schema prepared {}))
        operations (:operations empty-plan)
        exact (observed-schema prepared)
        active-result (registry/reconcile prepared 1 exact)
        active (:record active-result)]
    (check "prepare creates the closed persisted lifecycle record"
           [registry/registry-schema :preparing 1 nil (:checksum compiled)]
           [(:schema prepared) (:state prepared) (:generation prepared)
            (:failure prepared) (get-in prepared [:manifest :checksum])])
    (check "public Malli record schema describes the prepared envelope"
           [true false]
           [(m/validate registry/registry-record-schema prepared)
            (m/validate registry/registry-record-schema
                        (assoc prepared :telemetry-state :active))])
    (check "public observed schema requires table-qualified closed evidence"
           [true false false]
           [(m/validate registry/observed-schema exact)
            (m/validate registry/observed-schema
                        (get-in exact [0 :columns]))
            (m/validate registry/observed-schema
                        [(assoc (first exact) :telemetry-table "otel_logs")])])
    (check "span fields reserve value and status columns"
           [4 #{:value :status} #{"otel_traces"} #{"Int64" "Bool" "UInt8"}]
           [(count columns) (set (map :role columns)) (set (map :table columns))
            (set (map :type columns))])
    (check "missing columns produce sorted closed data operations"
           [true #{:add-column} #{:value :status} #{"otel_traces"}]
           [(= (mapv :name operations) (vec (sort (map :name operations))))
            (set (map :op operations)) (set (map :role operations))
            (set (map :table operations))])
    (check "column plans contain no caller SQL"
           true
           (let [rendered (pr-str operations)]
             (and (not (str/includes? rendered "ALTER"))
                  (not (str/includes? rendered ":sql")))))
    (check "every planned operation satisfies the public closed Malli schema"
           true
           (every? #(m/validate registry/add-column-operation-schema %)
                   operations))
    (check "a partial physical schema plans only missing columns"
           (mapv :name (rest columns))
           (let [first-column (first columns)]
             (mapv :name
                   (:operations
                    (registry/reconcile
                     prepared 1
                     (observed-schema
                      prepared {(:name first-column) (:type first-column)}))))))
    (check "preparing stays stable while the same work remains"
           prepared (:record empty-plan))
    (check "exact physical presence activates once"
           [:active 2 []]
           [(:state active) (:generation active) (:operations active-result)])
    (check "active reconciliation is idempotent"
           active
           (:record (registry/reconcile active 2 exact)))
    (check "idempotent restart recovers the persisted active record"
           active (registry/prepare compiled [active]))
    (check "duplicate persisted revisions fail before planning"
           :otel.exporter.chdb.attribute-registry/duplicate-record
           (:type
            (thrown-data #(registry/prepare compiled [active active]))))

    (let [column (first columns)
          conflict-result
          (registry/reconcile
           prepared 1 (observed-schema prepared {(:name column) "Float64"}))
          failed (:record conflict-result)
          retry-result (registry/reconcile failed 2 (observed-schema failed {}))
          retrying (:record retry-result)
          recovered
          (:record (registry/reconcile retrying 3 (observed-schema retrying)))]
      (check "wrong existing type fails closed without operations"
             [:failed 2 [] :column-type-conflict
              [{:actual-type "Float64" :expected-type (:type column)
                :name (:name column) :table "otel_traces"}]]
             [(:state failed) (:generation failed)
              (:operations conflict-result) (get-in failed [:failure :code])
              (get-in failed [:failure :columns])])
      (let [status-column (first (filter #(= :status (:role %)) columns))
            status-conflict
            (registry/reconcile
             prepared 1
             (observed-schema prepared {(:name status-column) "String"}))]
        (check "wrong status-column type also fails closed without operations"
               [:failed [] "UInt8"]
               [(get-in status-conflict [:record :state])
                (:operations status-conflict)
                (get-in status-conflict
                        [:record :failure :columns 0 :expected-type])]))
      (check "persisted failures cannot forge an unknown projected column"
             :otel.exporter.chdb.attribute-registry/invalid-record
             (:type
              (thrown-data
               #(registry/validate-record
                 (assoc failed :failure
                        {:code :column-type-conflict
                         :columns
                         [{:actual-type "String" :expected-type nil
                           :name "not_a_manifest_projection"
                           :table "otel_traces"}]})))))
      (check "a corrected failed install retries through preparing"
             [:preparing 3 4 nil]
             [(:state retrying) (:generation retrying)
              (count (:operations retry-result)) (:failure retrying)])
      (check "a retried install activates only after exact observation"
             [:active 4 nil]
             [(:state recovered) (:generation recovered) (:failure recovered)]))
    (let [[first-column second-column] columns
          forward (observed-schema
                   prepared (array-map (:name first-column) "WrongA"
                                       (:name second-column) "WrongB"))
          reverse-order (observed-schema
                         prepared (array-map (:name second-column) "WrongB"
                                             (:name first-column) "WrongA"))]
      (check "schema observation order cannot change a failed transition"
             (registry/reconcile prepared 1 forward)
             (registry/reconcile prepared 1 reverse-order)))

    (doseq [[table signal] identity/table-signals]
      (let [columns {"shared_column" "String"}
            evidence [{:columns columns :signal signal :table table}]
            expected [{:name "shared_column" :table table :type "String"}]]
        (check (str "pure reconciliation is exact for " table)
               {:conflicts [] :missing []}
               (registry/reconcile-physical-columns expected evidence))))
    (let [same-column "shared_column"
          all-tables
          (mapv (fn [[table signal]]
                  {:columns {same-column table} :signal signal :table table})
                identity/table-signals)]
      (check "same-name evidence from all five tables remains table-qualified"
             (into (sorted-map)
                   (map (fn [[table _]]
                          [table {same-column table}]))
                   identity/table-signals)
             (into (sorted-map)
                   (map (fn [[table _]]
                          [table (registry/observed-columns-for all-tables table)]))
                   identity/table-signals)))

    (doseq [[label evidence expected-type]
            [["missing target table" []
              :otel.exporter.chdb.attribute-registry/invalid-observed-schema]
             ["duplicate target table"
              (into exact exact)
              :otel.exporter.chdb.attribute-registry/invalid-observed-schema]
             ["unknown observed table"
              [{:columns {} :signal :spans :table "otel_unknown"}]
              :otel.exporter.chdb.attribute-registry/invalid-observed-schema]
             ["cross-signal observed table"
              [(assoc (first exact) :signal :logs)]
              :otel.exporter.chdb.attribute-registry/invalid-observed-schema]
             ["wrong-table evidence"
              [{:columns (get-in exact [0 :columns])
                :signal :logs :table "otel_logs"}]
              :otel.exporter.chdb.attribute-registry/missing-table-observation]]]
      (check (str label " fails closed before planning")
             expected-type
             (:type (thrown-data #(registry/reconcile prepared 1 evidence)))))

    (check "stale generation cannot transition registry state"
           :otel.exporter.chdb.attribute-registry/stale-generation
           (:type (thrown-data #(registry/reconcile active 1 exact))))
    (let [retired (registry/retire active 2)]
      (check "retirement is generation-checked and idempotent"
             [[:retired 3] retired]
             [[(:state retired) (:generation retired)]
              (registry/retire retired 3)])
      (check "retired descriptors cannot become active again"
             :otel.exporter.chdb.attribute-registry/invalid-transition
             (:type
              (thrown-data #(registry/reconcile retired 3 exact)))))

    (let [changed (compile-app
                   "checkout" 1
                   [{:location :span-attributes
                     :key "checkout.changed" :type :string}])]
      (check "one application revision cannot change its manifest"
             :otel.exporter.chdb.attribute-registry/revision-conflict
             (:type (thrown-data #(registry/prepare changed [active])))))
    (let [next-manifest (compile-app "checkout" 2)
          next-record (registry/prepare next-manifest [active])]
      (check "a new version receives a prospective physical projection"
             [2 true]
             [(get-in next-record [:manifest :version])
              (not= (set (map :name columns))
                    (set (map :name (registry/expected-columns next-record))))]))
    (let [first-manifest
          (compile-bundle-app "1111111111111111111111111111111111111111" 1)
          prepared-bundle (registry/prepare first-manifest)
          active-bundle
          (:record
           (registry/reconcile prepared-bundle 1
                               (observed-schema prepared-bundle)))
          drifted
          (compile-bundle-app "2222222222222222222222222222222222222222" 1)
          next-version
          (compile-bundle-app "2222222222222222222222222222222222222222" 2)
          next-record (registry/prepare next-version [active-bundle])]
      (check "bundle identity drift cannot rewrite one registry revision"
             :otel.exporter.chdb.attribute-registry/revision-conflict
             (:type
              (thrown-data #(registry/prepare drifted [active-bundle]))))
      (check "bundle promotion at a new version remains prospective"
             [manifest/bundle-manifest-schema 2 true]
             [(get-in next-record [:manifest :schema])
              (get-in next-record [:manifest :version])
              (not= (set (map :name
                              (registry/expected-columns active-bundle)))
                    (set (map :name
                              (registry/expected-columns next-record))))]))
    (let [other (registry/prepare (compile-app "billing" 1) [active])]
      (check "applications sharing a dataset receive disjoint projections"
             #{}
             (set/intersection
              (set (map :name columns))
              (set (map :name (registry/expected-columns other))))))
    (let [descriptor
          (fn [manifest]
            [{:field-id "forced-collision"
              :field-identity
              {:application-id (:application-id manifest)}
              :name "av_forced_collision"
              :role :value :table "otel_traces" :type "Int64"}])]
      (check "catalog-wide physical ownership rejects a forced digest collision"
             :otel.exporter.chdb.attribute-registry/physical-collision
             (:type
              (thrown-data
               #(with-redefs-fn
                  {(resolve
                    'otel.exporter.chdb.attribute-registry/column-descriptors)
                   descriptor}
                  (fn []
                    (registry/prepare
                     (compile-app "billing" 1) [active])))))))
    (check "record rendering is deterministic and clock-free"
           true
           (let [rendered (registry/render prepared)
                 unordered (into {} (reverse (seq prepared)))]
             (and (= rendered (registry/render (registry/prepare compiled)))
                  (= rendered (registry/render unordered))
                  (str/ends-with? rendered "\n")
                  (not (str/includes? rendered ":timestamp")))))
    (check "status meanings remain closed and stable"
           {:historical-untyped 0 :absent 1 :present-empty 2
            :valid 3 :invalid 4}
           registry/status-codes))

  (doseq [location [:resource-attributes :scope-attributes]]
    (let [ambiguous-manifest
          (compile-app "checkout" 1
                       [{:location location
                         :key "deployment.environment" :type :string}])]
      (check (str "signal-ambiguous " (name location)
                  " fail this span-only seam")
             :otel.exporter.chdb.attribute-registry/unsupported-target
             (:type (thrown-data #(registry/prepare ambiguous-manifest))))))
  (check "telemetry cannot inject a registry lifecycle state"
         :otel.exporter.chdb.attribute-manifest/invalid-input
         (:type
          (thrown-data
           #(manifest/compile-manifest
             {:dataset-id "telemetry-prod" :application-id "checkout"
              :lineage "checkout-v1" :version 1 :fragments []
              :state :active})))))
