(ns otel.exporter.chdb-typed-histogram-socket-native-test
  "Direct and loopback OTLP/JSON qualification for typed explicit histograms.

  This deliberately proves only bounded schema-bound discovery/filter/readback:
  Boolean resource, Int64 scope, and String point fields. It does not add
  histogram aggregation, bucket reconstruction, DDL ownership changes, or a
  cross-process capability protocol."
  (:require [clojure.data.json :as json]
            [db.jdbc]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [jolt.http.body :as http-body]
            [jolt.http.server :as http-server]
            [otel.exporter.chdb :as chdb]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb.typed-metric-explorer :as explorer]
            [otel.exporter.otlp :as otlp]
            [otel.otlp.http-receiver :as receiver]
            [otel.resource :as resource]
            [otel.sdk.export :as export]))

(def ^:private checks (atom 0))
(def ^:private max-body-bytes (* 1024 1024))

(defn- check! [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "  ok  " label)
    (throw (ex-info label {:expected expected :actual actual}))))

(defn- concat-chunks [chunks total]
  (let [result (byte-array total)]
    (loop [chunks chunks offset 0]
      (if-let [chunk (first chunks)]
        (do (System/arraycopy chunk 0 result offset (alength chunk))
            (recur (next chunks) (+ offset (alength chunk))))
        result))))

(defn- parse-json-body [request limit]
  (loop [chunks [] total 0]
    (if-let [chunk (http-body/body-recv (:body request))]
      (let [total (unchecked-add total (alength chunk))]
        (when (> total limit) (throw (receiver/body-too-large limit total)))
        (recur (conj chunks chunk) total))
      {:value (json/read-str (String. (concat-chunks chunks total) "UTF-8"))
       :encoded-bytes total})))

(defn- compiled []
  (manifest/compile-manifest
   {:dataset-id "typed-histogram-native" :application-id "histogram-socket"
    :lineage "histogram-socket-v1" :version 1
    :fragments [{:schema manifest/reviewed-fragment-schema :authority :advice
                 :source "advice/histogram-socket.edn"
                 :entries [{:signal :metrics :table "otel_metrics_histogram"
                            :location :resource-attributes :key "histogram.enabled" :type :boolean}
                           {:signal :metrics :table "otel_metrics_histogram"
                            :location :scope-attributes :key "histogram.workers" :type :int64}
                           {:signal :metrics :table "otel_metrics_histogram"
                            :location :metric-attributes :key "histogram.tier" :type :string}]}]}))

(defn- install! [store connection approved]
  (installer/install-approved!
   store approved
   {:target connection
    :observe-columns #(vector {:signal :metrics :table "otel_metrics_histogram"
                               :columns (into {} (map (juxt :name :type))
                                              (jdbc/fetch connection "DESCRIBE TABLE otel_metrics_histogram"))})
    :execute-ddl! #(jdbc/execute! connection %)}))

(defn- fields [installation]
  (into {} (map (juxt :key identity)) (get-in installation [:record :manifest :fields])))

(defn- binding [field]
  {:attribute-key (:key field) :attribute-location (:location field)
   :attribute-type (:type field) :field-id (:id field)
   :manifest-version (get-in field [:identity :version])})

(defn- query [field operator value]
  {:schema-binding (binding field) :signal :metrics :metric-kind :histogram
   :start-unix-nano 1700000000000000000 :end-unix-nano 1700000001000000000
   :operator operator :value value :limit 10 :max-text-length 64})

(defn- coverage-query [field]
  (select-keys (query field :eq false)
               [:schema-binding :signal :metric-kind :start-unix-nano :end-unix-nano]))

(defn- collected [name tier workers]
  [{:scope {:name "typed-histogram-socket" :version "1"
            :attributes {"histogram.workers" workers "scope.generic" "kept"}}
    :metrics [{:type :histogram :name name :description "" :unit "ms"
               :temporality :delta :explicit-bounds [10.0]
               :data-points [{:count 3 :sum 12.0 :bucket-counts [1 2]
                              :min 1.0 :max 7.0
                              :start-time-unix-nano 1699999999000000000
                              :time-unix-nano 1700000000000000000
                              :attributes {"histogram.tier" tier "point.generic" "kept"}}]}]}])

(defn- typed-pair [row field]
  (let [{:keys [value-column status-column]} (:physical field)]
    [(get row (keyword value-column)) (get row (keyword status-column))]))

(defn- rows [connection name]
  (jdbc/fetch connection [(str "SELECT * FROM otel_metrics_histogram WHERE MetricName=? ORDER BY TimeUnix") name]))

(defn -main [& _]
  (reset! checks 0)
  (println "typed histogram direct and socket qualification")
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (schema/ensure-schema! connection)
    (let [installation (install! (backend/memory-backend) connection (compiled))
          descriptors (:descriptor-set installation)
          fields (fields installation)
          receiving (chdb/exporter {:connection connection :create-schema? false :signals #{:metrics}
                                    :typed-histogram-descriptors descriptors})
          server (atom nil) outbound (atom nil)
          r (resource/resource {"service.name" "typed-histogram-socket"
                                 "histogram.enabled" false "resource.generic" "kept"})]
      (try
        (check! "histogram installer activates a table-bound capability" :active (:status installation))
        (let [result (export/export-metrics! receiving r (collected "typed.histogram.direct" "gold" 7))]
          (when-not result
            (throw (ex-info "direct histogram export failed"
                            (select-keys (ex-data (chdb/last-error receiving)) [:type]))))
          (check! "direct histogram export succeeds" true result))
        (let [listener (http-server/run-server
                        (receiver/handler {:parse-body parse-json-body :metric-exporter receiving
                                           :max-body-bytes max-body-bytes :max-concurrency 1})
                        :port 0 :server-name "127.0.0.1" :reuse-address? true)
              client (otlp/metric-exporter {:endpoint (str "http://127.0.0.1:" (:port listener))
                                             :timeout-ms 5000 :max-retries 0})]
          (reset! server listener) (reset! outbound client)
          (check! "canonical histogram crosses the loopback OTLP socket" true
                  (export/export-metrics! client r (collected "typed.histogram.socket" "gold" 7))))
        (let [direct (first (rows connection "typed.histogram.direct"))
              socket (first (rows connection "typed.histogram.socket"))]
          (check! "direct and socket rows retain promoted Boolean Int64 String values"
                  [[false 3] [7 3] ["gold" 3] [false 3] [7 3] ["gold" 3]]
                  (concat (map #(typed-pair direct (get fields %)) ["histogram.enabled" "histogram.workers" "histogram.tier"])
                          (map #(typed-pair socket (get fields %)) ["histogram.enabled" "histogram.workers" "histogram.tier"])))
          (check! "generic histogram maps remain alongside typed columns"
                  [{"histogram.enabled" "false" "resource.generic" "kept"
                    "service.name" "typed-histogram-socket"}
                   {"histogram.workers" "7" "scope.generic" "kept"}
                   {"histogram.tier" "gold" "point.generic" "kept"}]
                  [(:resourceattributes socket) (:scopeattributes socket) (:attributes socket)]))
        (let [q #(explorer/typed-histogram-filtered-points connection descriptors %)
              boolean (q (query (get fields "histogram.enabled") :eq false))
              int64 (q (query (get fields "histogram.workers") :gte 7))
              string (q (query (get fields "histogram.tier") :contains "ol"))]
          (check! "schema-bound histogram discovery exposes no physical columns"
                  #{[:resource-attributes :boolean] [:scope-attributes :int64] [:metric-attributes :string]}
                  (set (map (fn [{:keys [schema-binding]}]
                              [(:attribute-location schema-binding) (:attribute-type schema-binding)])
                            (explorer/typed-histogram-fields connection descriptors))))
          (check! "Boolean resource Int64 scope String point filters read direct and socket rows"
                  [#{false} #{7} #{"gold"}]
                  (mapv #(set (map :attribute-value (:matches %))) [boolean int64 string]))
          (check! "histogram coverage reads real exporter statuses"
                  {:valid 2 :present-empty 0 :absent 0 :invalid 0
                   :historical-untyped-fallback 0 :historical-untyped-unavailable 0 :total 2}
                  (:coverage (explorer/typed-histogram-coverage connection descriptors
                                                                (coverage-query (get fields "histogram.tier"))))))
        (finally
          (when-let [client @outbound] (export/shutdown-metric-exporter! client))
          (when-let [listener @server] (http-server/stop-server listener))
          (export/shutdown-metric-exporter! receiving)))))
  (when-not (= 8 @checks)
    (throw (ex-info "typed histogram native/socket check inventory changed"
                    {:expected 8 :actual @checks})))
  (println "typed-histogram-native-qualified :observed-checks" @checks))
