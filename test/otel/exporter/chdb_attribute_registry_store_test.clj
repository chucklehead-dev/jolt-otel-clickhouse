(ns otel.exporter.chdb-attribute-registry-store-test
  (:require [clojure.string :as str]
            [jdbc.chdb.durable.backend :as backend]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-store :as store]))

(defn- compiled []
  (manifest/compile-manifest
   {:dataset-id "telemetry-prod" :application-id "checkout"
    :lineage "checkout-v1" :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema
      :authority :advice :source "advice/checkout.edn"
      :entries [{:signal :spans :table "otel_traces"
                 :location :span-attributes
                 :key "checkout.complete" :type :boolean}]}]}))

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(deftype FaultBackend [delegate fault calls]
  backend/ObjectBackend
  (get-bytes [_ key] (backend/get-bytes delegate key))
  (get-with-etag [_ key] (backend/get-with-etag delegate key))
  (put-file-if-absent! [_ key path]
    (backend/put-file-if-absent! delegate key path))
  (put-bytes-if-absent! [_ key bytes]
    (swap! calls inc)
    (case @fault
      :before (do (reset! fault nil) {:status :ambiguous})
      :after (do (reset! fault nil)
                 (backend/put-bytes-if-absent! delegate key bytes)
                 {:status :ambiguous})
      (backend/put-bytes-if-absent! delegate key bytes)))
  (replace-if-match! [_ key bytes etag]
    (swap! calls inc)
    (case @fault
      :before (do (reset! fault nil) {:status :ambiguous})
      :after (do (reset! fault nil)
                 (backend/replace-if-match! delegate key bytes etag)
                 {:status :ambiguous})
      (backend/replace-if-match! delegate key bytes etag)))
  (download-to-file! [_ key path]
    (backend/download-to-file! delegate key path)))

(defn- fault-backend [delegate fault]
  (let [fault (atom fault)
        calls (atom 0)]
    {:backend (FaultBackend. delegate fault calls) :calls calls :fault fault}))

(defn run [check]
  (println "typed attribute registry CAS store")
  (let [delegate (backend/memory-backend)
        prepared (registry/prepare (compiled))
        exact [{:columns (into {} (map (juxt :name :type)
                                       (registry/expected-columns prepared)))
                :signal :spans :table "otel_traces"}]
        after-ddl (:record (registry/reconcile prepared 1 exact))]
    (let [{faulty :backend calls :calls} (fault-backend delegate :after)
          initial (store/load! faulty)
          committed (store/commit! faulty initial [prepared])]
      (check "a crash after create is reconciled from canonical persisted bytes"
             [:reconciled 1 :preparing 1]
             [(:status committed)
              (get-in committed [:snapshot :catalog :revision])
              (get-in committed [:snapshot :catalog :records 0 :state])
              @calls]))

    (let [{observed :backend calls :calls} (fault-backend delegate nil)
          restart (store/load! observed)
          same (store/commit! observed restart [prepared])]
      (check "restart before DDL recovers preparing and does not rewrite it"
             [:preparing :unchanged 1 0]
             [(get-in restart [:catalog :records 0 :state])
              (:status same) (get-in same [:snapshot :catalog :revision])
              @calls]))

    (let [before-ddl (store/load! delegate)
          active-commit (store/commit! delegate before-ddl [after-ddl])
          restarted (store/load! delegate)]
      (check "restart after DDL but before state persistence converges active"
             [:committed 2 :active 2]
             [(:status active-commit)
              (get-in restarted [:catalog :revision])
              (get-in restarted [:catalog :records 0 :state])
              (get-in restarted [:catalog :records 0 :generation])]))

    (let [base (store/load! delegate)
          changed (registry/retire after-ddl 2)
          column (first (registry/expected-columns after-ddl))
          loser-record
          (:record (registry/reconcile
                    after-ddl 2
                    [{:columns {(:name column) "WrongType"}
                      :signal :spans :table "otel_traces"}]))
          stale (store/load! delegate)
          winner (store/commit! delegate base [changed])
          stale-error
          (thrown-data #(store/commit! delegate stale [loser-record]))]
      (check "opaque ETag CAS rejects a stale competing catalog generation"
             [:committed
              :otel.exporter.chdb.attribute-registry-store/stale-snapshot]
             [(:status winner) (:type stale-error)]))

    (let [fresh (backend/memory-backend)
          prepared-write (store/commit-record! fresh (store/load! fresh)
                                               prepared)
          stale (:snapshot prepared-write)
          active-write (store/commit-record! fresh stale after-ddl)
          stale-no-op
          (thrown-data #(store/commit-record! fresh stale prepared))]
      (check "an apparently unchanged stale snapshot cannot bypass the CAS fence"
             [:committed
              :otel.exporter.chdb.attribute-registry-store/stale-snapshot]
             [(:status active-write) (:type stale-no-op)]))

    (let [fresh-delegate (backend/memory-backend)
          {faulty :backend calls :calls} (fault-backend fresh-delegate :before)
          snapshot (store/load! faulty)
          failure (thrown-data #(store/commit! faulty snapshot [prepared]))
          retry (store/commit! faulty (store/load! faulty) [prepared])]
      (check "a crash before create is not guessed committed and retry is safe"
             [:otel.exporter.chdb.attribute-registry-store/commit-ambiguous
              :committed 2]
             [(:type failure) (:status retry) @calls]))

    (let [fresh-delegate (backend/memory-backend)
          first (store/commit! fresh-delegate (store/load! fresh-delegate)
                               [prepared])
          {faulty :backend} (fault-backend fresh-delegate :after)
          result (store/commit! faulty (:snapshot first) [after-ddl])]
      (check "a crash after replace reconciles the intended next generation"
             [:reconciled 2 :active]
             [(:status result)
              (get-in result [:snapshot :catalog :revision])
              (get-in result [:snapshot :catalog :records 0 :state])]))

    (let [fresh (backend/memory-backend)
          invalid (assoc prepared :generation 2)]
      (check "new records cannot skip their initial generation"
             :otel.exporter.chdb.attribute-registry-store/stale-generation
             (:type (thrown-data
                     #(store/commit! fresh (store/load! fresh) [invalid])))))

    (let [fresh (backend/memory-backend)
          limit-var (ns-resolve
                     'otel.exporter.chdb.attribute-registry-store
                     'max-wire-bytes)
          failure (with-redefs-fn
                    {limit-var 1}
                    #(thrown-data
                      (fn []
                        (store/commit! fresh (store/load! fresh) [prepared]))))]
      (check "an oversized outbound catalog fails before object publication"
             [:otel.exporter.chdb.attribute-registry-store/catalog-too-large
              store/empty-catalog]
             [(:type failure) (:catalog (store/load! fresh))]))

    (check "canonical wire rendering is stable across input map order"
           (store/render store/empty-catalog)
           (store/render (into {} (reverse (seq store/empty-catalog)))))

    (check "persisted legacy manifest records fail closed at catalog validation"
           :otel.exporter.chdb.attribute-manifest/legacy-manifest
           (:type
            (thrown-data
             #(store/validate-catalog
               (assoc store/empty-catalog
                      :records
                      [(assoc-in prepared [:manifest :schema]
                                 manifest/legacy-manifest-schema)])))))

    (let [legacy-backend (backend/memory-backend)
          v2-wire (str (pr-str (assoc store/empty-catalog
                                      :records [prepared])) "\n")
          legacy-wire (str/replace v2-wire manifest/manifest-schema
                                   manifest/legacy-manifest-schema)]
      (backend/put-bytes-if-absent!
       legacy-backend store/object-key (.getBytes legacy-wire "UTF-8"))
      (check "loading persisted v1 data reports the migration boundary"
             :otel.exporter.chdb.attribute-registry-store/legacy-catalog
             (:type (thrown-data #(store/load! legacy-backend)))))))
