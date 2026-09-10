(ns otel.exporter.chdb.attribute-registry
  "Deterministic lifecycle records and column plans for typed attributes.

  This namespace is storage-independent and emits data operations, never SQL.
  A registry store must persist each returned record with a generation CAS."
  (:require [malli.core :as m]
            [otel.exporter.chdb.attribute-manifest :as manifest]))

(def registry-schema "jolt-otel-clickhouse.attribute-registry/v1")

(def state-values
  "Canonical order of the closed persisted lifecycle states."
  [:preparing :active :failed :retired])

(def states (set state-values))

(def status-codes
  "Stable per-row status values reserved for the typed exporter slice."
  (sorted-map :historical-untyped 0
              :absent 1
              :present-empty 2
              :valid 3
              :invalid 4))

(def ^:private span-table "otel_traces")
(def ^:private status-column-type "UInt8")
(def ^:private max-generation 9223372036854775807)
(def ^:private max-catalog-records 4096)
(def ^:private max-observed-columns 131072)
(def ^:private max-observed-name-length 256)
(def ^:private max-observed-type-length 256)

(def generation-schema
  "Positive bounded generation used by registry-store CAS operations."
  (m/schema [:int {:min 1 :max max-generation}]))

(def failure-column-schema
  (m/schema
   [:map {:closed true}
    [:actual-type [:string {:max max-observed-type-length}]]
    [:expected-type :string]
    [:name [:string {:max 63}]]
    [:table [:= span-table]]]))

(def failure-schema
  "Closed persisted registry failure envelope."
  (m/schema
   [:map {:closed true}
    [:code [:= :column-type-conflict]]
    [:columns [:vector {:min 1} failure-column-schema]]]))

(def registry-record-schema
  "Closed structural envelope for persisted registry records.

  `validate-record` additionally checks the embedded manifest, state-dependent
  failure rules, and that every failure names an approved projection."
  (m/schema
   [:map {:closed true}
    [:failure [:maybe failure-schema]]
    [:generation generation-schema]
    [:manifest :map]
    [:schema [:= registry-schema]]
    [:state (into [:enum] state-values)]]))

(def observed-schema
  "Bounded physical `column-name -> ClickHouse-type` observation."
  (m/schema
   [:map-of {:max max-observed-columns}
    [:string {:max max-observed-name-length}]
    [:string {:max max-observed-type-length}]]))

(def add-column-operation-schema
  "Closed data operation emitted to a future separately-authorized installer."
  (m/schema
   [:map {:closed true}
    [:field-id :string]
    [:name [:string {:max 63}]]
    [:op [:= :add-column]]
    [:role [:enum :value :status]]
    [:table [:= span-table]]
    [:type :string]]))

(defn- fail! [message type data]
  (throw (ex-info message (assoc data :type type
                                 :attribute-registry/error true))))

(declare canonical-value)

(defn- canonical-value [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[key item]] [key (canonical-value item)]))
                       value)
    (vector? value) (mapv canonical-value value)
    (sequential? value) (mapv canonical-value value)
    :else value))

(defn- next-generation [generation]
  (when (= max-generation generation)
    (fail! "attribute registry generation is exhausted"
           ::generation-exhausted {:generation generation}))
  (inc generation))

(defn- ensure-span-manifest! [value]
  (let [value (manifest/validate-manifest value)
        unsupported (->> (:fields value)
                         (remove #(= :span-attributes (:location %)))
                         (map :location)
                         distinct
                         (sort-by str)
                         vec)]
    (when (seq unsupported)
      (fail! "this registry slice supports span attributes only"
             ::unsupported-location {:locations unsupported}))
    value))

(defn- column-descriptors [manifest]
  (let [columns
        (->> (:fields manifest)
             (mapcat
              (fn [{:keys [id identity clickhouse-type physical]}]
                [(sorted-map :field-id id
                             :field-identity identity
                             :name (:value-column physical)
                             :role :value
                             :table span-table
                             :type clickhouse-type)
                 (sorted-map :field-id id
                             :field-identity identity
                             :name (:status-column physical)
                             :role :status
                             :table span-table
                             :type status-column-type)]))
             (sort-by (juxt :name (comp str :role)))
             vec)
        duplicate (->> columns
                       (group-by (juxt :table :name))
                       (filter (fn [[_ items]] (> (count items) 1)))
                       ffirst)]
    (when duplicate
      (fail! "typed attribute fields collide on a physical column"
             ::physical-collision
             {:table (first duplicate) :column (second duplicate)}))
    columns))

(defn- failure-sort-key [item]
  [(:table item) (:name item) (:expected-type item) (:actual-type item)])

(defn- canonical-conflicts [items]
  (->> items distinct (sort-by failure-sort-key) vec))

(defn- valid-failure? [failure columns]
  (let [expected (into {} (map (juxt :name :type) columns))]
    (and (m/validate failure-schema failure)
         (= (:columns failure) (canonical-conflicts (:columns failure)))
         (every?
          #(and (contains? expected (:name %))
                (= (get expected (:name %)) (:expected-type %))
                (string? (:expected-type %)))
          (:columns failure)))))

(defn validate-record
  "Validate the closed persisted registry record shape and embedded manifest."
  [record]
  (when-not (m/validate registry-record-schema record)
    (fail! "attribute registry record must use the closed v1 envelope"
           ::invalid-record
           {:explain (m/explain registry-record-schema record)}))
  (let [columns (column-descriptors
                 (ensure-span-manifest! (:manifest record)))]
    (if (= :failed (:state record))
      (when-not (valid-failure? (:failure record) columns)
        (fail! "failed registry record requires a canonical bounded failure"
               ::invalid-record {:failure (:failure record)}))
      (when-not (nil? (:failure record))
        (fail! "non-failed registry record cannot retain a failure"
               ::invalid-record {:state (:state record)}))))
  record)

(defn expected-columns
  "Return the sorted, closed physical column contract for a registry record."
  [record]
  (column-descriptors (:manifest (validate-record record))))

(defn- registry-key [manifest]
  [(:dataset-id manifest) (:application-id manifest)
   (:lineage manifest) (:version manifest)])

(defn record-key
  "Return the stable deployment revision identity of a validated record."
  [record]
  (registry-key (:manifest (validate-record record))))

(defn- validate-catalog! [records]
  (when-not (and (vector? records) (<= (count records) max-catalog-records))
    (fail! "attribute registry catalog must be a bounded vector"
           ::invalid-catalog {:count (when (vector? records) (count records))}))
  (let [records (mapv validate-record records)
        duplicate-key (->> records
                           (group-by #(registry-key (:manifest %)))
                           (filter (fn [[_ items]] (> (count items) 1)))
                           ffirst)]
    (when duplicate-key
      (fail! "attribute registry contains duplicate revision records"
             ::duplicate-record {:registry-key duplicate-key}))
    (let [collision
          (->> records
               (mapcat
                (fn [record]
                  (map (fn [column]
                         [[(:table column) (:name column)]
                          [(:field-identity column) (:role column)]])
                       (expected-columns record))))
               (group-by first)
               (filter (fn [[_ entries]]
                         (> (count (distinct (map second entries))) 1)))
               ffirst)]
      (when collision
        (fail! "persisted registry records collide on a physical column"
               ::physical-collision
               {:table (first collision) :column (second collision)})))
    records))

(defn validate-catalog
  "Validate and canonically order a complete persisted registry catalog."
  [records]
  (->> (validate-catalog! records)
       (sort-by record-key)
       vec))

(defn- assert-no-collision! [candidate records]
  (let [candidate-key (registry-key candidate)
        existing-columns
        (into {}
              (mapcat
               (fn [record]
                 (map (fn [column]
                        [[(:table column) (:name column)] column])
                      (expected-columns record)))
               records))]
    (doseq [column (column-descriptors candidate)
            :let [existing (get existing-columns
                                [(:table column) (:name column)])]
            :when (and existing
                       (not= [(:field-identity existing) (:role existing)]
                             [(:field-identity column) (:role column)]))]
      (fail! "typed attribute manifests collide in a dataset"
             ::physical-collision
             {:registry-key candidate-key
              :table (:table column) :column (:name column)}))))

(defn prepare
  "Create or recover a preparing record for a deployment-approved manifest.

  `records` is the persisted catalog for collision and idempotence checks. The
  caller must CAS-persist a new record before applying any planned operation."
  ([manifest] (prepare manifest []))
  ([candidate records]
   (let [candidate (ensure-span-manifest! candidate)
         records (validate-catalog! records)
         key (registry-key candidate)
         same-key (filter #(= key (registry-key (:manifest %))) records)]
     (if-let [existing (first same-key)]
       (if (= (:checksum candidate) (get-in existing [:manifest :checksum]))
         existing
         (fail! "a registry revision cannot change its manifest"
                ::revision-conflict {:registry-key key
                                     :existing-checksum
                                     (get-in existing [:manifest :checksum])
                                     :candidate-checksum (:checksum candidate)}))
       (do
         (assert-no-collision! candidate records)
         (sorted-map :failure nil
                     :generation 1
                     :manifest candidate
                     :schema registry-schema
                     :state :preparing))))))

(defn- assert-generation! [record expected-generation]
  (when-not (= (:generation record) expected-generation)
    (fail! "attribute registry transition used a stale generation"
           ::stale-generation {:expected expected-generation
                               :actual (:generation record)})))

(defn- validate-observed! [observed]
  (when-not (m/validate observed-schema observed)
    (fail! "observed physical schema must be a bounded string map"
           ::invalid-observed-schema
           {:explain (m/explain observed-schema observed)}))
  observed)

(defn- transition [record state failure]
  (if (and (= state (:state record)) (= failure (:failure record)))
    record
    (assoc record :state state :failure failure
           :generation (next-generation (:generation record)))))

(defn reconcile
  "Compare a persisted record with an observed `column-name -> type` map.

  Missing columns produce sorted idempotent `:add-column` data operations.
  Exact presence activates the record. A wrong existing type fails closed."
  [record expected-generation observed]
  (let [record (validate-record record)]
    (assert-generation! record expected-generation)
    (when (= :retired (:state record))
      (fail! "retired registry records cannot be reconciled"
             ::invalid-transition {:state :retired}))
    (let [observed (validate-observed! observed)
          columns (expected-columns record)
          conflicts
          (canonical-conflicts
           (keep (fn [{:keys [table name type]}]
                   (when-let [actual (get observed name)]
                     (when (not= type actual)
                       (sorted-map :actual-type actual
                                   :expected-type type
                                   :name name :table table))))
                 columns))
          missing (filterv #(not (contains? observed (:name %))) columns)]
      (cond
        (seq conflicts)
        (sorted-map
         :operations []
         :record (transition record :failed
                             (sorted-map :code :column-type-conflict
                                         :columns conflicts)))

        (seq missing)
        (let [operations
              (mapv #(into (sorted-map)
                           (assoc
                            (select-keys % [:field-id :name :role :table :type])
                            :op :add-column))
                    missing)]
          (when-not (every? #(m/validate add-column-operation-schema %)
                            operations)
            (fail! "attribute registry generated an invalid closed operation"
                   ::invalid-operation {}))
          (sorted-map :operations operations
                      :record (transition record :preparing nil)))

        :else
        (sorted-map :operations []
                    :record (transition record :active nil))))))

(defn retire
  "Retire a registry record at the expected generation. Retire is idempotent."
  [record expected-generation]
  (let [record (validate-record record)]
    (assert-generation! record expected-generation)
    (transition record :retired nil)))

(defn render
  "Render a validated persisted record as deterministic EDN with one newline."
  [record]
  (str (pr-str (canonical-value (validate-record record))) "\n"))
