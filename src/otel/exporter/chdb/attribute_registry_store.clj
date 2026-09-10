(ns otel.exporter.chdb.attribute-registry-store
  "Crash-recoverable CAS persistence for the typed-attribute registry.

  This namespace persists lifecycle records but deliberately does not inspect
  telemetry, execute SQL, or decide which manifests are authorized."
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [jdbc.chdb.durable.backend :as backend]
            [otel.exporter.chdb.attribute-registry :as registry]))

(def store-schema "jolt-otel-clickhouse.attribute-registry-store/v1")
(def object-key "otel/typed-attribute-registry-v1.edn")

(def ^:private max-revision 9223372036854775807)
(def ^:private max-records 4096)
(def ^:private max-wire-bytes (* 32 1024 1024))

(def catalog-schema
  "Closed persisted catalog envelope. Record semantics are checked separately."
  (m/schema
   [:map {:closed true}
    [:records [:vector {:max max-records} registry/registry-record-schema]]
    [:revision [:int {:min 0 :max max-revision}]]
    [:schema [:= store-schema]]]))

(def empty-catalog
  (sorted-map :records [] :revision 0 :schema store-schema))

(defn- fail! [message type data]
  (throw (ex-info message (assoc data :type type
                                 :attribute-registry-store/error true))))

(declare canonical-value)

(defn- canonical-value [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[key item]] [key (canonical-value item)]))
                       value)
    (vector? value) (mapv canonical-value value)
    (sequential? value) (mapv canonical-value value)
    :else value))

(defn validate-catalog
  "Validate a closed store envelope and every registry record in it."
  [catalog]
  (when-not (m/validate catalog-schema catalog)
    (fail! "attribute registry store catalog must use the closed v1 envelope"
           ::invalid-catalog {:explain (m/explain catalog-schema catalog)}))
  (assoc catalog :records (registry/validate-catalog (:records catalog))))

(defn render
  "Render a validated catalog to its canonical, clock-free EDN wire form."
  [catalog]
  (str (pr-str (canonical-value (validate-catalog catalog))) "\n"))

(defn- checked-wire-size! [bytes]
  (when (> (alength bytes) max-wire-bytes)
    (fail! "attribute registry store catalog exceeds its wire bound"
           ::catalog-too-large {:byte-count (alength bytes)}))
  bytes)

(defn- encode [catalog]
  (checked-wire-size! (.getBytes (render catalog) "UTF-8")))

(defn- decode [bytes]
  (when-not (bytes? bytes)
    (fail! "attribute registry store returned a non-byte value"
           ::invalid-wire {}))
  (checked-wire-size! bytes)
  (try
    (let [text (String. bytes "UTF-8")
          catalog (validate-catalog (edn/read-string text))]
      (when-not (= text (render catalog))
        (fail! "attribute registry store catalog is not canonical"
               ::noncanonical-wire {}))
      catalog)
    (catch Throwable error
      (if (:attribute-registry-store/error (ex-data error))
        (throw error)
        (fail! "attribute registry store catalog cannot be decoded"
               ::invalid-wire {:cause-class (str (class error))})))))

(defn load!
  "Load one immutable snapshot. Its opaque ETag is the only CAS authority."
  [store]
  (when-not (satisfies? backend/ObjectBackend store)
    (fail! "attribute registry store requires an ObjectBackend"
           ::invalid-backend {}))
  (if-let [{:keys [bytes etag]} (backend/get-with-etag store object-key)]
    {:catalog (decode bytes) :etag etag}
    {:catalog empty-catalog :etag nil}))

(defn- checked-snapshot [snapshot]
  (when-not (and (map? snapshot)
                 (= #{:catalog :etag} (set (keys snapshot))))
    (fail! "attribute registry CAS requires a loaded snapshot"
           ::invalid-snapshot {}))
  (assoc snapshot :catalog (validate-catalog (:catalog snapshot))))

(defn- record-map [records]
  (into {} (map (juxt registry/record-key identity) records)))

(defn- validate-evolution! [before after]
  (let [old-records (record-map before)
        new-records (record-map after)
        removed (seq (remove #(contains? new-records %) (keys old-records)))]
    (when removed
      (fail! "attribute registry records cannot be deleted; retire them"
             ::record-deletion {:record-keys (vec (sort removed))}))
    (doseq [[key record] new-records]
      (if-let [old (get old-records key)]
        (when-not (= old record)
          (when (= :retired (:state old))
            (fail! "retired registry records are immutable"
                   ::retired-record-change {:record-key key}))
          (when-not (= (:manifest old) (:manifest record))
            (fail! "a persisted registry revision cannot change its manifest"
                   ::manifest-change {:record-key key}))
          (when-not (= (inc (:generation old)) (:generation record))
            (fail! "registry persistence requires the next record generation"
                   ::stale-generation
                   {:record-key key :expected (inc (:generation old))
                    :actual (:generation record)})))
        (when-not (= 1 (:generation record))
          (fail! "a new registry record must begin at generation one"
                 ::stale-generation
                 {:record-key key :expected 1
                  :actual (:generation record)}))))))

(defn- intended-catalog [snapshot records]
  (let [before (get-in snapshot [:catalog :records])
        records (registry/validate-catalog records)
        revision (get-in snapshot [:catalog :revision])]
    (validate-evolution! before records)
    (if (= before records)
      (:catalog snapshot)
      (do
        (when (= max-revision revision)
          (fail! "attribute registry store revision is exhausted"
                 ::revision-exhausted {:revision revision}))
        (sorted-map :records records :revision (inc revision)
                    :schema store-schema)))))

(defn- reconcile-result [store intended ambiguous?]
  (let [latest (load! store)]
    (cond
      (= intended (:catalog latest))
      {:status :reconciled :snapshot latest}

      ambiguous?
      (fail! "attribute registry CAS result could not be proved by reread"
             ::commit-ambiguous {})

      :else
      (fail! "attribute registry snapshot lost its CAS race"
             ::stale-snapshot
             {:actual-revision (get-in latest [:catalog :revision])}))))

(defn- confirm-unchanged [store snapshot]
  (let [latest (load! store)]
    (cond
      (= (:etag snapshot) (:etag latest))
      {:status :unchanged :snapshot latest}

      (= (:catalog snapshot) (:catalog latest))
      {:status :reconciled :snapshot latest}

      :else
      (fail! "attribute registry snapshot changed before the no-op commit"
             ::stale-snapshot
             {:actual-revision (get-in latest [:catalog :revision])}))))

(defn commit!
  "CAS-persist a complete record vector against a snapshot returned by `load!`.

  The ETag remains opaque. Ambiguous writes are accepted only when a canonical
  reread equals the intended catalog. Identical records do not write again."
  [store snapshot records]
  (let [snapshot (checked-snapshot snapshot)
        intended (intended-catalog snapshot records)]
    (if (= intended (:catalog snapshot))
      (confirm-unchanged store snapshot)
      (let [bytes (encode intended)
            result (if (nil? (:etag snapshot))
                     (backend/put-bytes-if-absent! store object-key bytes)
                     (backend/replace-if-match! store object-key bytes
                                                (:etag snapshot)))]
        (case (:status result)
          :created {:status :committed
                    :snapshot {:catalog intended :etag (:etag result)}}
          :replaced {:status :committed
                     :snapshot {:catalog intended :etag (:etag result)}}
          :precondition-failed (reconcile-result store intended false)
          :ambiguous (reconcile-result store intended true)
          (fail! "attribute registry backend returned an unsupported CAS result"
                 ::backend-contract {:status (:status result)}))))))

(defn commit-record!
  "CAS-persist one lifecycle record while retaining the complete catalog."
  [store snapshot record]
  (let [record (registry/validate-record record)
        key (registry/record-key record)
        records (get-in (checked-snapshot snapshot) [:catalog :records])
        retained (filterv #(not= key (registry/record-key %)) records)]
    (commit! store snapshot (conj retained record))))
