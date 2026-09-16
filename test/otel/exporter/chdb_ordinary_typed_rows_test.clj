(ns otel.exporter.chdb-ordinary-typed-rows-test
  (:require [db.jdbc] [jdbc.core :as jdbc]
            [db.driver :as driver] [db.jdbc-shim :as shim]
            [clojure.data.json :as json] [clojure.string :as str]
            [jdbc.chdb :as chdb] [jdbc.chdb.durable.backend :as backend]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-test-support :as support]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.attribute-registry-store :as store]
            [otel.sdk.export :as export] [otel.sdk.logs :as logs]))

(defn- connection []
  (let [drv (reify driver/Driver
              (descriptor [_] {:id :chdb :aliases #{"typed-row-test"}
                               :uri-prefixes ["typed-row-test:"] :product-name "typed row test"
                               :capabilities {:transactions :none :generated-keys :none}})
              (open-handle [_ _] {:closed? (atom false) :lock (Object.) :connection :mock})
              (close-handle [_ h] (reset! (:closed? h) true))
              (execute-handle [_ _ _ _] (throw (ex-info "Unexpected JDBC execution" {}))))]
    (with-redefs [driver/resolve-driver (fn [_] drv)] (shim/connection "typed-row-test:memory"))))

(defn- installed [signal target]
  (let [table (if (= signal :spans) "otel_traces" "otel_logs")
        location (if (= signal :spans) :span-attributes :log-attributes)
        approved (manifest/compile-manifest
                  {:dataset-id "row-data-test" :application-id (name signal)
                   :lineage "typed-row-v1" :version 1
                   :fragments [{:schema manifest/reviewed-fragment-schema :authority :advice
                                :source "advice/typed-row.edn"
                                :entries (mapv (fn [[key type]]
                                                 {:signal signal :table table :location location
                                                  :key key :type type})
                                               [["flag" :boolean] ["count" :int64]])}]})
        columns (registry/expected-columns (registry/prepare approved))
        observed (atom {}) index (atom 0) object-backend (backend/memory-backend)
        observe #(vector {:signal signal :table table :columns @observed})
        installation (installer/install-approved!
                      object-backend approved
                      {:target target :observe-columns observe
                       :execute-ddl! (fn [_] (let [{:keys [name type]} (nth columns @index)]
                                              (swap! index inc) (swap! observed assoc name type)))})]
    {:installation installation :backend object-backend :observe observe
     :selector (select-keys approved [:dataset-id :application-id :lineage :version])}))

(defn- pair [row fields key]
  (let [physical (:physical (first (filter #(= key (:key %)) fields)))]
    [(get row (:value-column physical)) (get row (:status-column physical))]))

(defn- record [signal attributes]
  (merge {:attributes attributes :resource {:attributes {}} :scope {:name "typed rows"}}
         (if (= signal :spans)
           {:name "typed" :kind :internal :start-time-unix-nano 1 :end-time-unix-nano 2
            :span-context {:trace-id "11111111111111111111111111111111" :span-id "2222222222222222"}
            :status {:code :unset} :events [] :links []}
           {:timestamp-unix-nano 1 :observed-time-unix-nano 2 :body "typed"})))

(defn run [check]
  (doseq [signal [:spans :logs]]
    (with-open [target (connection) foreign (connection)]
      (let [{:keys [installation backend observe selector]} (installed signal target)
            capability (:descriptor-set installation)
            fields ((if (= signal :spans) projection/confirmed-span-fields projection/confirmed-log-fields)
                    capability target)
            typed-columns (vec (mapcat (fn [field] [(get-in field [:physical :value-column])
                                                    (get-in field [:physical :status-column])]) fields))
            base (if (= signal :spans)
                   (into schema/clickstack-trace-insert-columns ["EventsJSON" "LinksJSON"])
                   schema/clickstack-log-insert-columns)
            expected-columns (into base typed-columns)
            calls (atom [])
            options {:connection target :create-schema? false :signals #{signal}
                     (if (= signal :spans) :typed-span-descriptors :typed-log-descriptors) capability}
            send! (if (= signal :spans) export/export-spans! logs/export-logs!)]
        (with-redefs [chdb/insert-json-rows! (fn [conn table columns payload]
                                             (swap! calls conj [conn table columns payload]))
                      jdbc/execute! (fn [& _] (throw (ex-info "Old ordinary SQL transport reached" {})))]
          (let [writer (support/call-with-qualified-native #(exporter/exporter options))]
            (check (str signal " real minted authority is active") :active (:status installation))
            (check (str signal " accepted false/zero and absent rows") true
                   (send! writer [(record signal {"flag" false "count" 0}) (record signal {})]))
            (check (str signal " one batch entry") 1 (count @calls))
            (let [[conn table columns payload] (first @calls)
                  rows (mapv json/read-str (remove str/blank? (str/split-lines payload)))
                  generic (if (= signal :spans) "SpanAttributes" "LogAttributes")]
              (check (str signal " actual target identity") true (identical? target conn))
              (check (str signal " explicit table") (if (= signal :spans) "otel_traces" "otel_logs") table)
              (check (str signal " authority ordered physical columns") expected-columns columns)
              (check (str signal " all rows exact key identity") [true true]
                     (mapv #(= (set expected-columns) (set (keys %))) rows))
              (check (str signal " typed false/zero valid statuses") [[false 3] [0 3]]
                     [(pair (first rows) fields "flag") (pair (first rows) fields "count")])
              (check (str signal " absent typed defaults/status") [[false 1] [0 1]]
                     [(pair (second rows) fields "flag") (pair (second rows) fields "count")])
              (check (str signal " generic readable maps unchanged") [{"flag" "false" "count" "0"} {}]
                     (mapv #(get % generic) rows))
              (check (str signal " historical status code remains reserved") 0
                     (:historical-untyped registry/status-codes)))
            (reset! calls [])
            (check (str signal " foreign capability target rejects")
                   :otel.exporter.chdb.attribute-projection/target-mismatch
                   (try (exporter/exporter (assoc options :connection foreign)) nil
                        (catch Throwable e (:type (ex-data e)))))
            (check (str signal " foreign target has no driver effects") [] @calls))
          ;; Staleness is checked at acquisition, not retroactively on an
          ;; already minted immutable capability. No capability is forged.
          (let [load! store/load! reads (atom 0)]
            (with-redefs [store/load! (fn [b] (let [snapshot (load! b)]
                                               (if (= 2 (swap! reads inc))
                                                 (assoc snapshot :etag "changed-after-observation") snapshot)))]
              (check (str signal " stale acquisition rejects")
                     :otel.exporter.chdb.attribute-registry-installer/stale-snapshot
                     (try (installer/acquire-active! backend selector
                                                    {:target target :observe-columns observe}) nil
                          (catch Throwable e (:type (ex-data e)))))
              (check (str signal " stale observation/reread nonvacuous") 2 @reads)
              (check (str signal " stale acquisition has no insert effects") [] @calls))))))))
