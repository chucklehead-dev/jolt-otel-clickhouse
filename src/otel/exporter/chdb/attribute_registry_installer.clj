(ns otel.exporter.chdb.attribute-registry-installer
  "Authorized, span-only installation of persisted typed-attribute columns.

  Invocation is the deployment authorization boundary. Telemetry never enters
  this API. Database execution and observation are explicit injected effects."
  (:require [malli.core :as m]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-store :as store]))

(def event-values
  "Closed event vocabulary for crash-cut traces and future model validation."
  [:snapshot-loaded :record-persisted :schema-observed
   :ddl-started :ddl-applied :descriptors-published])

(def ^:private event-set (set event-values))
(def ^:private safe-identifier-pattern #"[a-z][a-z0-9_]{0,62}")
(def ^:private allowed-value-types #{"String" "Bool" "Int64"})
(def ^:private allowed-options
  #{:emit! :execute-ddl! :observe-columns :target})
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
    (str "ALTER TABLE otel_traces ADD COLUMN IF NOT EXISTS `"
         (:name operation) "` " (:type operation))))

(defn- snapshot-record [snapshot key]
  (or (first (filter #(= key (registry/record-key %))
                     (get-in snapshot [:catalog :records])))
      (fail! "committed catalog omitted the requested registry record"
             ::missing-record {:record-key key})))

(defn- observe! [observe-columns emit! phase]
  (let [observed (observe-columns)]
    (emit-event! emit! {:event :schema-observed :phase phase})
    observed))

(defn descriptor-set-data
  "Return immutable installation evidence from an active descriptor capability.

  Capabilities are minted only by `install-approved!` after fresh observation
  and confirmed active persistence. Bare records or descriptor vectors are not
  accepted."
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
        key (registry/record-key prepared)
        prepared-write (store/commit-record! object-backend loaded prepared)
        prepared-snapshot (:snapshot prepared-write)
        persisted (snapshot-record prepared-snapshot key)
        _ (emit-event! emit! {:event :record-persisted
                              :generation (:generation persisted)
                              :state (:state persisted)})
        before (observe! observe-columns emit! :before-ddl)
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
                        (observe! observe-columns emit! :after-ddl))
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
        (let [descriptors (registry/expected-columns persisted-final)]
          (emit-event! emit! {:event :descriptors-published
                              :generation (:generation persisted-final)})
          (result :active persisted-final final-snapshot descriptors
                  (ConfirmedActiveDescriptorSet.
                   descriptor-issuer target persisted-final final-snapshot
                   descriptors)))
        (result state persisted-final final-snapshot [] nil)))))
