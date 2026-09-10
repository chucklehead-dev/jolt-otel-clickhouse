(ns otel.exporter.chdb.attribute-registry
  "Deterministic lifecycle records and column plans for typed attributes.

  This namespace is storage-independent and emits data operations, never SQL.
  A registry store must persist each returned record with a generation CAS."
  (:require [otel.exporter.chdb.attribute-manifest :as manifest]))

(def registry-schema "jolt-otel-clickhouse.attribute-registry/v1")

(def states
  "Closed persisted lifecycle states."
  #{:preparing :active :failed :retired})

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
(def ^:private max-observed-type-length 256)

(defn- fail! [message type data]
  (throw (ex-info message (assoc data :type type
                                 :attribute-registry/error true))))

(defn- exact-keys? [value expected]
  (and (map? value) (= expected (set (keys value)))))

(declare canonical-value)

(defn- canonical-value [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[key item]] [key (canonical-value item)]))
                       value)
    (vector? value) (mapv canonical-value value)
    (sequential? value) (mapv canonical-value value)
    :else value))

(defn- valid-generation? [value]
  (and (integer? value) (pos? value) (<= value max-generation)))

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
    (and (exact-keys? failure #{:code :columns})
         (= :column-type-conflict (:code failure))
         (vector? (:columns failure))
         (not (empty? (:columns failure)))
         (= (:columns failure) (canonical-conflicts (:columns failure)))
         (every?
          #(and (exact-keys? % #{:actual-type :expected-type :name :table})
                (= span-table (:table %))
                (string? (:name %)) (<= (count (:name %)) 63)
                (contains? expected (:name %))
                (= (get expected (:name %)) (:expected-type %))
                (string? (:actual-type %))
                (<= (count (:actual-type %)) max-observed-type-length))
          (:columns failure)))))

(defn validate-record
  "Validate the closed persisted registry record shape and embedded manifest."
  [record]
  (when-not (exact-keys? record #{:failure :generation :manifest :schema :state})
    (fail! "attribute registry record must use the closed v1 envelope"
           ::invalid-record {:record record}))
  (when-not (and (= registry-schema (:schema record))
                 (contains? states (:state record))
                 (valid-generation? (:generation record)))
    (fail! "attribute registry record has invalid lifecycle metadata"
           ::invalid-record
           {:schema (:schema record) :state (:state record)
            :generation (:generation record)}))
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
                  (let [dataset-id (get-in record [:manifest :dataset-id])]
                    (map (fn [column]
                           [[dataset-id (:table column) (:name column)]
                            [(:field-identity column) (:role column)]])
                         (expected-columns record)))))
               (group-by first)
               (filter (fn [[_ entries]]
                         (> (count (distinct (map second entries))) 1)))
               ffirst)]
      (when collision
        (fail! "persisted registry records collide on a physical column"
               ::physical-collision
               {:dataset-id (first collision)
                :table (second collision) :column (nth collision 2)})))
    records))

(defn- assert-no-collision! [candidate records]
  (let [candidate-key (registry-key candidate)
        existing-columns
        (into {}
              (mapcat
               (fn [record]
                 (when (= (:dataset-id candidate)
                          (get-in record [:manifest :dataset-id]))
                   (map (fn [column]
                          [[(:table column) (:name column)] column])
                        (expected-columns record))))
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
  (when-not (and (map? observed) (<= (count observed) max-observed-columns)
                 (every? (fn [[name type]]
                           (and (string? name) (string? type)
                                (<= (count type) max-observed-type-length)))
                         observed))
    (fail! "observed physical schema must be a bounded string map"
           ::invalid-observed-schema {}))
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
        (sorted-map
         :operations
         (mapv #(into (sorted-map)
                      (assoc (select-keys % [:field-id :name :role :table :type])
                             :op :add-column))
               missing)
         :record (transition record :preparing nil))

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
