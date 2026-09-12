(ns otel.exporter.chdb.attribute-registry-installer
  "Authorized, span-only installation of persisted typed-attribute columns.

  Invocation is the deployment authorization boundary. Telemetry never enters
  this API. Database execution and observation are explicit injected effects."
  (:require [malli.core :as m]
            [otel.exporter.chdb.attribute-identity :as identity]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-store :as store]))

(def event-values
  "Closed event vocabulary for crash-cut traces and future model validation."
  [:snapshot-loaded :record-persisted :schema-observed
   :ddl-started :ddl-applied :snapshot-confirmed :descriptors-published])

(def ^:private event-set (set event-values))
(def ^:private safe-identifier-pattern #"[a-z][a-z0-9_]{0,62}")
(def ^:private allowed-value-types #{"String" "Bool" "Int64"})
(def ^:private allowed-options
  #{:emit! :execute-ddl! :observe-columns :target})
(def ^:private allowed-acquisition-options
  #{:emit! :observe-columns :target})
(def ^:private selector-keys
  #{:dataset-id :application-id :lineage :version})
(def ^:private selector-value-pattern #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
(def ^:private max-version 9223372036854775807)
(def ^:private descriptor-issuer (atom nil))

(deftype ^:private ConfirmedActiveDescriptorSet
  [issuer target record snapshot descriptors])

(defn- fail! [message type data]
  (throw (ex-info message (assoc data :type type
                                 :attribute-registry-installer/error true))))

(defn- checked-runtime [{:keys [emit! execute-ddl! observe-columns target]
                         :or {emit! (fn [_] nil)}
                         :as runtime}]
  (when-not (and (map? runtime)
                 (every? allowed-options (keys runtime))
                 (some? target) (fn? emit!)
                 (fn? execute-ddl!) (fn? observe-columns))
    (fail! "typed attribute installer requires a closed effect runtime"
           ::invalid-runtime {}))
  {:emit! emit! :execute-ddl! execute-ddl!
   :observe-columns observe-columns :target target})

(defn- checked-acquisition-runtime
  [{:keys [emit! observe-columns target]
    :or {emit! (fn [_] nil)}
    :as runtime}]
  (when-not (and (map? runtime)
                 (every? allowed-acquisition-options (keys runtime))
                 (some? target) (fn? emit!) (fn? observe-columns))
    (fail! "typed descriptor acquisition requires a closed read-only runtime"
           ::invalid-runtime {}))
  {:emit! emit! :observe-columns observe-columns :target target})

(defn- checked-selector
  [{:keys [dataset-id application-id lineage version] :as selector}]
  (when-not (and (map? selector)
                 (= selector-keys (set (keys selector)))
                 (every? #(and (string? %)
                               (boolean (re-matches selector-value-pattern %)))
                         [dataset-id application-id lineage])
                 (integer? version) (pos? version) (<= version max-version))
    (fail! "typed descriptor acquisition requires a closed deployment selector"
           ::invalid-selector {:selector selector}))
  [dataset-id application-id lineage version])

(defn- emit-event! [emit! event]
  (when-not (contains? event-set (:event event))
    (fail! "typed attribute installer generated an unknown trace event"
           ::invalid-event {:event (:event event)}))
  (emit! event))

(defn- expected-operation [column]
  (into (sorted-map)
        (assoc (select-keys column [:field-id :name :role :table :type])
               :op :add-column)))

(defn render-add-column
  "Render one registry-owned additive operation to bounded ClickHouse DDL.

  The operation must exactly match a descriptor owned by `record`; callers
  cannot supply another table, identifier, type, or SQL suffix."
  [record operation]
  (let [record (registry/validate-record record)
        expected (set (map expected-operation (registry/expected-columns record)))
        valid-shape? (m/validate registry/add-column-operation-schema operation)
        value-type? (if (= :status (:role operation))
                      (= "UInt8" (:type operation))
                      (contains? allowed-value-types (:type operation)))]
    (when-not (and valid-shape?
                   (contains? expected operation)
                   (boolean (re-matches safe-identifier-pattern
                                        (:name operation)))
                   value-type?)
      (fail! "add-column operation is not owned by the persisted record"
             ::invalid-operation {:operation operation}))
    (str "ALTER TABLE " (:table operation) " ADD COLUMN IF NOT EXISTS `"
         (:name operation) "` " (:type operation))))

(defn- snapshot-record [snapshot key]
  (or (first (filter #(= key (registry/record-key %))
                     (get-in snapshot [:catalog :records])))
      (fail! "committed catalog omitted the requested registry record"
             ::missing-record {:record-key key})))

(defn- record-target [record]
  (let [targets (->> (get-in record [:manifest :fields])
                     (map identity/target-of)
                     distinct
                     vec)]
    (when-not (= 1 (count targets))
      (fail! "typed attribute record must name exactly one canonical target"
             ::invalid-record-target {:targets targets}))
    (first targets)))

(defn- observe! [observe-columns emit! phase target]
  (let [observed (registry/validate-observed-schema (observe-columns))]
    ;; Prove that this attempt observed the record-owned table before emitting
    ;; evidence that a model adapter may consume.
    (registry/observed-columns-for observed (:table target))
    (emit-event! emit! {:event :schema-observed :phase phase
                        :signal (:signal target) :table (:table target)})
    observed))

(defn descriptor-set-data
  "Return immutable confirmation evidence from an active descriptor capability.

  Capabilities are minted only by `install-approved!` or `acquire-active!`
  after fresh observation and confirmed active persistence. Bare records or
  descriptor vectors are not accepted."
  [descriptor-set]
  (when-not (and (instance? ConfirmedActiveDescriptorSet descriptor-set)
                 (identical? descriptor-issuer (.-issuer descriptor-set)))
    (fail! "typed descriptors require an installer-confirmed active capability"
           ::unconfirmed-descriptors {}))
  {:descriptors (.-descriptors descriptor-set)
   :record (.-record descriptor-set)
   :snapshot (.-snapshot descriptor-set)
   :target (.-target descriptor-set)})

(defn- result [status record snapshot descriptors descriptor-set]
  (sorted-map :descriptor-set descriptor-set :descriptors descriptors
              :record record :snapshot snapshot :status status))

(defn- confirmed-result [target record snapshot]
  ;; Do the complete immutable-evidence check once, before the private
  ;; capability is minted. Query and projection hot paths subsequently need
  ;; only issuer and process-local target identity checks: revalidating this
  ;; captured persistent data cannot make it fresher.
  (let [record (registry/validate-record record)
        catalog (store/validate-catalog (:catalog snapshot))
        persisted (first (filter #(= (registry/record-key record)
                                     (registry/record-key %))
                                 (:records catalog)))
        descriptors (registry/expected-columns record)
        span-fields (get-in record [:manifest :fields])]
    (when-not (and (= :active (:state record))
                   (= record persisted)
                   (= descriptors (registry/expected-columns persisted))
                   (every? #(= identity/span-attribute-target
                               (identity/target-of %))
                           span-fields))
      (fail! "typed descriptor capability has inconsistent active evidence"
             ::invalid-confirmed-evidence {}))
    (result :active record snapshot descriptors
            (ConfirmedActiveDescriptorSet.
             descriptor-issuer target record snapshot descriptors))))

(defn acquire-active!
  "Acquire one persisted active descriptor set without mutation or DDL.

  `selector` is the operator-selected deployment identity. The selected record
  must already be active, its exact table must freshly match every expected
  column, and a post-observation reread must retain the same opaque ETag,
  catalog revision, record generation, and record value. Only then is a
  connection-bound descriptor capability returned."
  [object-backend selector runtime]
  (let [{:keys [emit! observe-columns target]}
        (checked-acquisition-runtime runtime)
        key (checked-selector selector)
        loaded (store/load! object-backend)
        _ (emit-event! emit! {:event :snapshot-loaded :phase :before-schema
                              :revision (get-in loaded [:catalog :revision])})
        record (snapshot-record loaded key)]
    (when-not (= :active (:state record))
      (fail! "typed descriptor acquisition requires a persisted active record"
             ::inactive-record {:record-key key :state (:state record)
                                :generation (:generation record)}))
    (let [owned-target (record-target record)
          observed (observe! observe-columns emit! :acquire owned-target)
          {:keys [conflicts missing]}
          (registry/reconcile-physical-columns
           (registry/expected-columns record) observed)]
      (when (or (seq conflicts) (seq missing))
        (fail! "persisted active typed columns drifted from physical schema"
               ::physical-drift {:record-key key :conflicts conflicts
                                 :missing missing}))
      (let [confirmed (store/load! object-backend)
            latest-record (snapshot-record confirmed key)
            loaded-revision (get-in loaded [:catalog :revision])
            confirmed-revision (get-in confirmed [:catalog :revision])]
        (cond
          (not= (:generation record) (:generation latest-record))
          (fail! "typed descriptor generation changed during acquisition"
                 ::stale-generation
                 {:record-key key :expected (:generation record)
                  :actual (:generation latest-record)})

          (or (not= (:etag loaded) (:etag confirmed))
              (not= loaded-revision confirmed-revision)
              (not= record latest-record))
          (fail! "typed descriptor catalog changed during acquisition"
                 ::stale-snapshot
                 {:record-key key :expected-revision loaded-revision
                  :actual-revision confirmed-revision})

          :else
          (let [confirmation (confirmed-result target latest-record confirmed)]
            (emit-event! emit! {:event :snapshot-confirmed
                                :generation (:generation latest-record)
                                :revision confirmed-revision})
            (emit-event! emit! {:event :descriptors-published
                                :generation (:generation latest-record)})
            confirmation))))))

(defn install-approved!
  "Install one deployment-approved span manifest through crash-safe cuts.

  The sequence is persist preparing, freshly observe, apply only missing
  idempotent additive DDL, freshly observe again, then CAS-persist the resulting
  lifecycle record. Descriptors are returned only after an active record is
  confirmed persisted from the current snapshot."
  [object-backend approved-manifest runtime]
  (let [{:keys [emit! execute-ddl! observe-columns target]}
        (checked-runtime runtime)
        loaded (store/load! object-backend)
        _ (emit-event! emit! {:event :snapshot-loaded
                              :revision (get-in loaded [:catalog :revision])})
        prepared (registry/prepare approved-manifest
                                   (get-in loaded [:catalog :records]))
        record-target (record-target prepared)
        key (registry/record-key prepared)
        prepared-write (store/commit-record! object-backend loaded prepared)
        prepared-snapshot (:snapshot prepared-write)
        persisted (snapshot-record prepared-snapshot key)
        _ (emit-event! emit! {:event :record-persisted
                              :generation (:generation persisted)
                              :state (:state persisted)})
        before (observe! observe-columns emit! :before-ddl record-target)
        initial-plan (registry/reconcile persisted (:generation persisted) before)
        operations (:operations initial-plan)
        planned-record (:record initial-plan)
        authority-write
        (if (and (seq operations) (not= persisted planned-record))
          (store/commit-record! object-backend prepared-snapshot planned-record)
          {:snapshot prepared-snapshot})
        authority-snapshot (:snapshot authority-write)
        authority-record (snapshot-record authority-snapshot key)
        _ (when (not= persisted authority-record)
            (emit-event! emit! {:event :record-persisted
                                :generation (:generation authority-record)
                                :state (:state authority-record)}))]
    (doseq [[index operation] (map-indexed vector operations)]
      (let [statement (render-add-column authority-record operation)]
        (emit-event! emit! {:event :ddl-started :index index
                            :operation operation})
        (execute-ddl! statement)
        (emit-event! emit! {:event :ddl-applied :index index
                            :operation operation})))
    (let [final-plan (if (seq operations)
                       (registry/reconcile
                        authority-record (:generation authority-record)
                        (observe! observe-columns emit! :after-ddl record-target))
                       initial-plan)
          final-record (:record final-plan)
          final-write (store/commit-record! object-backend authority-snapshot
                                             final-record)
          final-snapshot (:snapshot final-write)
          persisted-final (snapshot-record final-snapshot key)
          state (:state persisted-final)]
      (emit-event! emit! {:event :record-persisted
                          :generation (:generation persisted-final)
                          :state state})
      (if (= :active state)
        (let [confirmation
              (confirmed-result target persisted-final final-snapshot)]
          (emit-event! emit! {:event :descriptors-published
                              :generation (:generation persisted-final)})
          confirmation)
        (result state persisted-final final-snapshot [] nil)))))
