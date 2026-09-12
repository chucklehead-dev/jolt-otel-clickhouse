(ns otel.exporter.chdb-attribute-registry-installer-test
  (:require [jdbc.chdb.durable.backend :as backend]
            [otel.exporter.chdb-attribute-bundle-fixture :as bundle-fixture]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.attribute-registry-store :as store]))

(defn- compiled
  ([] (compiled "checkout"))
  ([application]
   (manifest/compile-manifest
    {:dataset-id "telemetry-prod" :application-id application
     :lineage "checkout-v1" :version 1
     :fragments
     [{:schema manifest/reviewed-fragment-schema
       :authority :advice :source "advice/checkout.edn"
       :entries [{:signal :spans :table "otel_traces"
                  :location :span-attributes
                  :key "checkout.complete" :type :boolean}]}]})))

(defn- approved-bundle []
  (let [fragment
        (bundle-fixture/inferred
         "src/checkout-bundle.clj"
         "(ns checkout-bundle (:require [otel.trace :as trace]))
          (trace/set-attribute! span \"checkout.bundle-complete\" false)")
        item
        {:artifact
         (bundle-fixture/revision-artifact
          "io.github.example/checkout-bundle"
          "https://github.com/example/checkout-bundle"
          "5555555555555555555555555555555555555555")
         :path "META-INF/otel/attribute-schema/checkout-bundle.edn"
         :fragment fragment}]
    (bundle-fixture/discovered-bundle [item])))

(defn- compiled-bundle []
  (manifest/compile-bundle-manifest
   {:dataset-id "telemetry-prod"
    :application-id "checkout-bundle"
    :lineage "checkout-bundle-v1"
    :version 1
    :bundle (approved-bundle)}))

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(deftype WriteCountingBackend [delegate writes]
  backend/ObjectBackend
  (get-bytes [_ key] (backend/get-bytes delegate key))
  (get-with-etag [_ key] (backend/get-with-etag delegate key))
  (put-file-if-absent! [_ key path]
    (swap! writes inc)
    (backend/put-file-if-absent! delegate key path))
  (put-bytes-if-absent! [_ key bytes]
    (swap! writes inc)
    (backend/put-bytes-if-absent! delegate key bytes))
  (replace-if-match! [_ key bytes etag]
    (swap! writes inc)
    (backend/replace-if-match! delegate key bytes etag))
  (download-to-file! [_ key path]
    (backend/download-to-file! delegate key path)))

(defn- selector [manifest]
  (select-keys manifest [:dataset-id :application-id :lineage :version]))

(declare fixture)

(defn- acquisition-fixture []
  (let [{:keys [backend columns manifest runtime schema] :as fixture} (fixture)
        _ (installer/install-approved! backend manifest runtime)
        exact-columns (into {} (map (juxt :name :type)) columns)
        _ (reset! schema exact-columns)
        writes (atom 0)]
    (assoc fixture
           :exact-columns exact-columns
           :reader-backend (WriteCountingBackend. backend writes)
           :writes writes)))

(defn- fixture []
  (let [manifest (compiled)
        record (registry/prepare manifest)
        columns (registry/expected-columns record)
        schema (atom {})
        statements (atom [])
        events (atom [])
        next-column (atom 0)
        target (atom :installer-target)
        runtime
        {:target target
         :emit! #(swap! events conj %)
         :observe-columns #(vector {:columns (into {} @schema)
                                    :signal :spans :table "otel_traces"})
         :execute-ddl!
         (fn [statement]
           (swap! statements conj statement)
           (let [{:keys [name type]} (nth columns @next-column)]
             (swap! next-column inc)
             (swap! schema assoc name type)))}]
    {:backend (backend/memory-backend) :columns columns :events events
     :manifest manifest :next-column next-column :runtime runtime
     :schema schema :statements statements :target target}))

(defn- published? [events]
  (boolean (some #(= :descriptors-published (:event %)) events)))

(defn- crash-runtime [runtime events predicate]
  (assoc runtime :emit!
         (fn [event]
           (swap! events conj event)
           (when (predicate event)
             (throw (ex-info "injected crash" {:event event}))))))

(defn run [check]
  (println "typed attribute DDL installer")
  (let [{:keys [backend columns events manifest runtime schema statements]}
        (fixture)
        installed (installer/install-approved! backend manifest runtime)]
    (check "authorized install persists active before publishing descriptors"
           [:active 2 2 columns true]
           [(:status installed) (get-in installed [:record :generation])
            (get-in installed [:snapshot :catalog :revision])
            (:descriptors installed) (published? @events)])
    (check "DDL is additive, idempotent, and contains only owned columns"
           (mapv #(installer/render-add-column
                   (registry/prepare manifest) %)
                 (:operations
                  (registry/reconcile
                   (registry/prepare manifest) 1
                   [{:columns {} :signal :spans :table "otel_traces"}])))
           @statements)
    (check "descriptor publication follows fresh observation and active CAS"
           [:schema-observed :record-persisted :descriptors-published]
           (mapv :event (take-last 3 @events)))
    (check "schema trace evidence carries the record-owned signal and table"
           #{[:spans "otel_traces"]}
           (set (map (juxt :signal :table)
                     (filter #(= :schema-observed (:event %)) @events))))

    (let [{:keys [backend events manifest runtime]} (fixture)
          commit-record! store/commit-record!
          failure
          (with-redefs
            [store/commit-record!
             (fn [backend snapshot record]
               (let [result (commit-record! backend snapshot record)]
                 (if (= :active (:state record))
                   ;; A causal negative control for mint-time evidence: the
                   ;; selected active record is present, but the captured
                   ;; catalog is no longer a valid canonical authority.
                   (update-in result [:snapshot :catalog :records]
                              conj record)
                   result)))]
            (thrown-data
             #(installer/install-approved! backend manifest runtime)))]
      (check "tampered catalog evidence cannot mint or publish a capability"
             [:otel.exporter.chdb.attribute-registry/duplicate-record false]
             [(:type failure) (published? @events)]))

    (reset! events [])
    (let [loaded-active-crash
          (thrown-data
           #(installer/install-approved!
             backend manifest
             (assoc runtime :observe-columns
                    (fn [] (throw (ex-info "observe crash" {}))))))]
      (check "merely loading active never publishes without fresh observation"
             [nil false :active]
             [(:type loaded-active-crash) (published? @events)
              (get-in (store/load! backend)
                      [:catalog :records 0 :state])]))

    (let [{:keys [backend events manifest next-column runtime schema statements]}
          (fixture)
          _ (installer/install-approved! backend manifest runtime)
          _ (reset! schema {})
          _ (reset! statements [])
          _ (reset! events [])
          _ (reset! next-column 0)
          crash (crash-runtime runtime events
                               #(= :ddl-started (:event %)))
          _ (thrown-data #(installer/install-approved! backend manifest crash))
          persisted-before-ddl
          (get-in (store/load! backend) [:catalog :records 0])
          statements-before (vec @statements)
          recovered (installer/install-approved! backend manifest runtime)]
      (check "active drift persists a new preparing generation before repair DDL"
             [:preparing 3 [] :active 4]
             [(:state persisted-before-ddl) (:generation persisted-before-ddl)
              statements-before (:status recovered)
              (get-in recovered [:record :generation])]))

    (let [{:keys [backend columns events manifest next-column runtime schema
                  statements]}
          (fixture)
          first-column (first columns)
          _ (swap! schema assoc (:name first-column) "String")
          failed (installer/install-approved! backend manifest runtime)
          _ (reset! schema {})
          _ (reset! statements [])
          _ (reset! events [])
          _ (reset! next-column 0)
          crash (crash-runtime runtime events
                               #(= :ddl-started (:event %)))
          _ (thrown-data #(installer/install-approved! backend manifest crash))
          retrying (get-in (store/load! backend) [:catalog :records 0])
          statements-before (vec @statements)
          recovered (installer/install-approved! backend manifest runtime)]
      (check "failed retry persists preparing before corrective DDL"
             [:failed 2 :preparing 3 [] :active 4]
             [(:state (:record failed)) (:generation (:record failed))
              (:state retrying) (:generation retrying) statements-before
              (:status recovered) (get-in recovered [:record :generation])]))

    (let [{:keys [backend events manifest next-column runtime schema statements]}
          (fixture)
          _ (installer/install-approved! backend manifest runtime)
          _ (reset! schema {})
          _ (reset! statements [])
          _ (reset! events [])
          _ (reset! next-column 0)
          competitor-ran? (atom false)
          observe (:observe-columns runtime)
          stale-runtime
          (assoc runtime :observe-columns
                 (fn []
                   (let [observed (observe)]
                     (when (compare-and-set! competitor-ran? false true)
                       (let [snapshot (store/load! backend)
                             other (registry/prepare
                                    (compiled "billing")
                                    (get-in snapshot [:catalog :records]))]
                         (store/commit-record! backend snapshot other)))
                     observed)))
          failure
          (thrown-data
           #(installer/install-approved! backend manifest stale-runtime))]
      (check "stale repair-transition CAS fails before any DDL"
             [:otel.exporter.chdb.attribute-registry-store/stale-snapshot
              :active [] false]
             [(:type failure)
              (->> (get-in (store/load! backend) [:catalog :records])
                   (filter #(= "checkout"
                               (get-in % [:manifest :application-id])))
                   first :state)
              @statements (published? @events)]))

    (doseq [[label predicate expected-schema-size]
            [["before DDL"
              #(= :ddl-started (:event %)) 0]
             ["after DDL"
              #(and (= :ddl-applied (:event %))
                    (= (dec (count columns)) (:index %)))
              (count columns)]
             ["before final CAS"
              #(and (= :schema-observed (:event %))
                    (= :after-ddl (:phase %)))
              (count columns)]
             ["after final CAS"
              #(and (= :record-persisted (:event %))
                    (= :active (:state %)))
              (count columns)]]]
      (let [{:keys [backend events manifest runtime schema]} (fixture)
            crash (crash-runtime runtime events predicate)
            _ (thrown-data #(installer/install-approved! backend manifest crash))
            state-before (get-in (store/load! backend)
                                 [:catalog :records 0 :state])
            schema-size-before (count @schema)
            published-before (published? @events)
            recovered (installer/install-approved! backend manifest runtime)]
        (check (str "crash/retry converges " label)
               [(if (= label "after final CAS") :active :preparing)
                expected-schema-size false :active true]
               [state-before schema-size-before published-before
                (:status recovered) (boolean (seq (:descriptors recovered)))])))

    (let [{:keys [backend columns events manifest runtime schema statements]}
          (fixture)
          first-column (first columns)]
      (swap! schema assoc (:name first-column) "String")
      (let [failed (installer/install-approved! backend manifest runtime)]
        (check "exact existing type conflict persists failed without DDL or publish"
               [:failed :column-type-conflict [] [] false]
               [(:status failed) (get-in failed [:record :failure :code])
                @statements (:descriptors failed) (published? @events)])))

    (let [{:keys [backend columns events manifest runtime schema statements]}
          (fixture)
          exact-columns (into {} (map (juxt :name :type)) columns)
          _ (reset! schema exact-columns)
          wrong-table-runtime
          (assoc runtime :observe-columns
                 #(vector {:columns exact-columns
                           :signal :logs :table "otel_logs"}))
          failure
          (thrown-data
           #(installer/install-approved! backend manifest wrong-table-runtime))]
      (check "wrong-table mutant preserves the formerly sufficient column map"
             exact-columns
             (get-in ((:observe-columns wrong-table-runtime)) [0 :columns]))
      (check "wrong-table evidence fails before DDL or descriptor publication"
             [:otel.exporter.chdb.attribute-registry/missing-table-observation
              [] false false :preparing]
             [(:type failure) @statements
              (boolean (some #(= :schema-observed (:event %)) @events))
              (published? @events)
              (get-in (store/load! backend) [:catalog :records 0 :state])]))

    (let [{:keys [backend events manifest runtime schema]} (fixture)
          competitor-ran? (atom false)
          runtime
          (assoc runtime :execute-ddl!
                 (let [execute! (:execute-ddl! runtime)]
                   (fn [statement]
                     (execute! statement)
                     (when (compare-and-set! competitor-ran? false true)
                       (let [snapshot (store/load! backend)
                             other (registry/prepare
                                    (compiled "billing")
                                    (get-in snapshot [:catalog :records]))]
                         (store/commit-record! backend snapshot other))))))
          failure (thrown-data
                   #(installer/install-approved! backend manifest runtime))]
      (check "stale final catalog CAS cannot publish an unpersisted active state"
             [:otel.exporter.chdb.attribute-registry-store/stale-snapshot
              :preparing false (count columns)]
             [(:type failure)
              (->> (get-in (store/load! backend) [:catalog :records])
                   (filter #(= "checkout"
                               (get-in % [:manifest :application-id])))
                   first :state)
              (published? @events) (count @schema)]))

    (let [record (registry/prepare manifest)
          operation (first
                     (:operations
                      (registry/reconcile
                       record 1
                       [{:columns {} :signal :spans
                         :table "otel_traces"}])))
          injected (assoc operation :name "owned` String; DROP TABLE x --")]
      (check "renderer rejects caller-controlled identifier or SQL text"
             :otel.exporter.chdb.attribute-registry-installer/invalid-operation
             (:type (thrown-data
                     #(installer/render-add-column record injected)))))

    (let [object-backend (backend/memory-backend)
          ddl-calls (atom 0)
          compiled (compiled-bundle)
          runtime
          {:target (atom :bundle-target)
           :observe-columns
           (fn [] [{:columns {} :signal :spans :table "otel_traces"}])
           :execute-ddl! (fn [_] (swap! ddl-calls inc))}
          raw-bundle-failure
          (thrown-data
           #(installer/install-approved! object-backend (approved-bundle)
                                         runtime))
          telemetry-failure
          (thrown-data
           #(installer/install-approved! object-backend
                                         (assoc compiled :state :active)
                                         runtime))]
      (check "bundle or telemetry metadata cannot authorize installer DDL"
             [:otel.exporter.chdb.attribute-manifest/invalid-manifest
              :otel.exporter.chdb.attribute-manifest/invalid-manifest
              0 store/empty-catalog]
             [(:type raw-bundle-failure) (:type telemetry-failure)
              @ddl-calls (:catalog (store/load! object-backend))]))

    (let [{:keys [backend manifest runtime]} (fixture)]
      (check "installer runtime requires an explicit non-nil target identity"
             :otel.exporter.chdb.attribute-registry-installer/invalid-runtime
             (:type
              (thrown-data
               #(installer/install-approved! backend manifest
                                              (dissoc runtime :target))))))

    (let [{:keys [events exact-columns manifest reader-backend writes]}
          (acquisition-fixture)
          fresh-target (atom :fresh-reader-target)
          _ (reset! events [])
          acquired
          (installer/acquire-active!
           reader-backend (selector manifest)
           {:target fresh-target
            :emit! #(swap! events conj %)
            :observe-columns
            #(vector {:columns exact-columns :signal :spans
                      :table "otel_traces"})})
          data (installer/descriptor-set-data (:descriptor-set acquired))]
      (check "restart reader acquires a freshly confirmed active capability"
             [:active 2 true 0 {:generation 2 :revision 2}
              [:snapshot-loaded :schema-observed :snapshot-confirmed
               :descriptors-published]]
             [(:status acquired) (get-in acquired [:record :generation])
              (identical? fresh-target (:target data)) @writes
              (select-keys
               (first (filter #(= :snapshot-confirmed (:event %)) @events))
               [:generation :revision])
              (mapv :event @events)]))

    (let [{:keys [exact-columns manifest reader-backend writes]}
          (acquisition-fixture)
          ddl-calls (atom 0)
          observes (atom 0)
          failure
          (thrown-data
           #(installer/acquire-active!
             reader-backend (selector manifest)
             {:target (atom :forbidden-ddl-reader)
              :execute-ddl! (fn [_] (swap! ddl-calls inc))
              :observe-columns
              (fn []
                (swap! observes inc)
                [{:columns exact-columns :signal :spans
                  :table "otel_traces"}])}))]
      (check "read-only acquisition rejects a DDL effect before any effect"
             [:otel.exporter.chdb.attribute-registry-installer/invalid-runtime
             0 0 0]
             [(:type failure) @ddl-calls @observes @writes]))

    (let [{:keys [exact-columns manifest reader-backend writes]}
          (acquisition-fixture)
          observes (atom 0)
          missing
          (thrown-data
           #(installer/acquire-active!
             reader-backend (assoc (selector manifest)
                                   :application-id "not-installed")
             {:target (atom :unselected-reader)
              :observe-columns
              (fn []
                (swap! observes inc)
                [{:columns exact-columns :signal :spans
                  :table "otel_traces"}])}))
          malformed
          (thrown-data
           #(installer/acquire-active!
             reader-backend (assoc (selector manifest) :state :active)
             {:target (atom :malformed-selector-reader)
              :observe-columns
              (fn []
                (swap! observes inc)
                [{:columns exact-columns :signal :spans
                  :table "otel_traces"}])}))]
      (check "operator selector is closed and must name a persisted record"
             [:otel.exporter.chdb.attribute-registry-installer/missing-record
              :otel.exporter.chdb.attribute-registry-installer/invalid-selector
              0 0]
             [(:type missing) (:type malformed) @observes @writes]))

    (let [{:keys [backend exact-columns manifest reader-backend writes]}
          (acquisition-fixture)
          snapshot (store/load! backend)
          record (first (get-in snapshot [:catalog :records]))
          retired (registry/retire record (:generation record))
          _ (store/commit-record! backend snapshot retired)
          observes (atom 0)
          failure
          (thrown-data
           #(installer/acquire-active!
             reader-backend (selector manifest)
             {:target (atom :inactive-reader)
              :observe-columns
              (fn []
                (swap! observes inc)
                [{:columns exact-columns :signal :spans
                  :table "otel_traces"}])}))]
      (check "persisted non-active state is rejected before physical access"
             [:otel.exporter.chdb.attribute-registry-installer/inactive-record
              0 0]
             [(:type failure) @observes @writes]))

    (let [{:keys [columns exact-columns manifest reader-backend writes]}
          (acquisition-fixture)
          column (first columns)
          missing-failure
          (thrown-data
           #(installer/acquire-active!
             reader-backend (selector manifest)
             {:target (atom :drift-reader)
              :observe-columns
              #(vector {:columns (dissoc exact-columns (:name column))
                        :signal :spans
                        :table "otel_traces"})}))
          conflict-failure
          (thrown-data
           #(installer/acquire-active!
             reader-backend (selector manifest)
             {:target (atom :type-drift-reader)
              :observe-columns
              #(vector {:columns (assoc exact-columns (:name column)
                                        "WrongType")
                        :signal :spans
                        :table "otel_traces"})}))]
      (check "physical drift cannot reacquire persisted active descriptors"
             [[:otel.exporter.chdb.attribute-registry-installer/physical-drift
               1 0]
              [:otel.exporter.chdb.attribute-registry-installer/physical-drift
               0 1]
              0]
             [[(:type missing-failure) (count (:missing missing-failure))
               (count (:conflicts missing-failure))]
              [(:type conflict-failure) (count (:missing conflict-failure))
               (count (:conflicts conflict-failure))]
              @writes]))

    (let [{:keys [exact-columns manifest reader-backend writes]}
          (acquisition-fixture)
          failure
          (thrown-data
           #(installer/acquire-active!
             reader-backend (selector manifest)
             {:target (atom :wrong-table-reader)
              :observe-columns
              #(vector {:columns exact-columns :signal :logs
                        :table "otel_logs"})}))]
      (check "wrong-table evidence cannot reacquire descriptors"
             [:otel.exporter.chdb.attribute-registry/missing-table-observation 0]
             [(:type failure) @writes]))

    (let [{:keys [backend exact-columns manifest reader-backend writes]}
          (acquisition-fixture)
          changed? (atom false)
          failure
          (thrown-data
           #(installer/acquire-active!
             reader-backend (selector manifest)
             {:target (atom :stale-generation-reader)
              :observe-columns
              (fn []
                (when (compare-and-set! changed? false true)
                  (let [snapshot (store/load! backend)
                        record (first (get-in snapshot [:catalog :records]))]
                    (store/commit-record!
                     backend snapshot
                     (registry/retire record (:generation record)))))
                [{:columns exact-columns :signal :spans
                  :table "otel_traces"}])}))]
      (check "generation change during observation fences descriptor acquisition"
             [:otel.exporter.chdb.attribute-registry-installer/stale-generation
              2 3 0]
             [(:type failure) (:expected failure) (:actual failure) @writes]))

    (let [{:keys [backend exact-columns events manifest reader-backend writes]}
          (acquisition-fixture)
          changed? (atom false)
          _ (reset! events [])
          failure
          (thrown-data
           #(installer/acquire-active!
             reader-backend (selector manifest)
             {:target (atom :stale-catalog-reader)
              :emit! #(swap! events conj %)
              :observe-columns
              (fn []
                (when (compare-and-set! changed? false true)
                  (let [snapshot (store/load! backend)
                        other (registry/prepare
                               (compiled "billing")
                               (get-in snapshot [:catalog :records]))]
                    (store/commit-record! backend snapshot other)))
                [{:columns exact-columns :signal :spans
                  :table "otel_traces"}])}))]
      (check "unrelated catalog change during observation fences publication"
             [:otel.exporter.chdb.attribute-registry-installer/stale-snapshot
              false 0]
             [(:type failure) (published? @events) @writes]))))
