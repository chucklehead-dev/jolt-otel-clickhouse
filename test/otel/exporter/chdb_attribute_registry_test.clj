(ns otel.exporter.chdb-attribute-registry-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry :as registry]))

(defn- reviewed [entries]
  {:schema manifest/reviewed-fragment-schema
   :authority :advice
   :source "advice/checkout.edn"
   :entries entries})

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

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(defn- observed-schema [record]
  (into {} (map (juxt :name :type) (registry/expected-columns record))))

(defn run [check]
  (println "typed attribute registry lifecycle")
  (let [compiled (compile-app "checkout" 1)
        prepared (registry/prepare compiled)
        columns (registry/expected-columns prepared)
        empty-plan (registry/reconcile prepared 1 {})
        operations (:operations empty-plan)
        exact (observed-schema prepared)
        active-result (registry/reconcile prepared 1 exact)
        active (:record active-result)]
    (check "prepare creates the closed persisted lifecycle record"
           [registry/registry-schema :preparing 1 nil (:checksum compiled)]
           [(:schema prepared) (:state prepared) (:generation prepared)
            (:failure prepared) (get-in prepared [:manifest :checksum])])
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
    (check "a partial physical schema plans only missing columns"
           (mapv :name (rest columns))
           (let [first-column (first columns)]
             (mapv :name
                   (:operations
                    (registry/reconcile
                     prepared 1 {(:name first-column) (:type first-column)})))))
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
          (registry/reconcile prepared 1 {(:name column) "Float64"})
          failed (:record conflict-result)
          retry-result (registry/reconcile failed 2 {})
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
      (check "a corrected failed install retries through preparing"
             [:preparing 3 4 nil]
             [(:state retrying) (:generation retrying)
              (count (:operations retry-result)) (:failure retrying)])
      (check "a retried install activates only after exact observation"
             [:active 4 nil]
             [(:state recovered) (:generation recovered) (:failure recovered)]))
    (let [[first-column second-column] columns
          forward (array-map (:name first-column) "WrongA"
                             (:name second-column) "WrongB")
          reverse-order (array-map (:name second-column) "WrongB"
                                   (:name first-column) "WrongA")]
      (check "schema observation order cannot change a failed transition"
             (registry/reconcile prepared 1 forward)
             (registry/reconcile prepared 1 reverse-order)))

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
    (let [other (registry/prepare (compile-app "billing" 1) [active])]
      (check "applications sharing a dataset receive disjoint projections"
             #{}
             (set/intersection
              (set (map :name columns))
              (set (map :name (registry/expected-columns other))))))
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

  (let [resource-manifest
        (compile-app "checkout" 1
                     [{:location :resource-attributes
                       :key "deployment.environment" :type :string}])]
    (check "signal-ambiguous resource locations fail this span-only seam"
           :otel.exporter.chdb.attribute-registry/unsupported-location
           (:type (thrown-data #(registry/prepare resource-manifest)))))
  (check "telemetry cannot inject a registry lifecycle state"
         :otel.exporter.chdb.attribute-manifest/invalid-input
         (:type
          (thrown-data
           #(manifest/compile-manifest
             {:dataset-id "telemetry-prod" :application-id "checkout"
              :lineage "checkout-v1" :version 1 :fragments []
              :state :active})))))
