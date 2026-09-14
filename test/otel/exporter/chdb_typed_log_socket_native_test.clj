(ns otel.exporter.chdb-typed-log-socket-native-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [db.jdbc]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [jolt.http.body :as http-body]
            [jolt.http.server :as http-server]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb.typed-log-explorer :as log-explorer]
            [otel.exporter.otlp :as otlp-export]
            [otel.otlp.http-receiver :as receiver]
            [otel.resource :as resource]
            [otel.sdk.logs :as sdk-logs]))

(def ^:private max-body-bytes (* 1024 1024))
(def ^:private exact-int64 9007199254740993)

(defn- concat-chunks [chunks total]
  (let [result (byte-array total)]
    (loop [remaining chunks offset 0]
      (when-let [chunk (first remaining)]
        (System/arraycopy chunk 0 result offset (alength chunk))
        (recur (next remaining) (+ offset (alength chunk)))))
    result))

(defn- bounded-body [body limit]
  (loop [chunks [] total 0]
    (if-let [chunk (http-body/body-recv body)]
      (let [actual (+ total (alength chunk))]
        (when (> actual limit)
          (throw (receiver/body-too-large limit actual)))
        (recur (conj chunks chunk) actual))
      (concat-chunks chunks total))))

(defn- parse-json-body [request limit]
  (let [encoded (bounded-body (:body request) limit)]
    {:value (json/read-str (String. encoded "UTF-8"))
     :encoded-bytes (alength encoded)}))

(defn- compiled []
  (manifest/compile-manifest
   {:dataset-id "telemetry-prod" :application-id "log-socket"
    :lineage "log-socket-v1" :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema
      :authority :advice :source "advice/log-socket.edn"
      :entries
      [{:signal :logs :table "otel_logs" :location :log-attributes
        :key "job.name" :type :string}
       {:signal :logs :table "otel_logs" :location :log-attributes
        :key "job.complete" :type :boolean}
       {:signal :logs :table "otel_logs" :location :log-attributes
        :key "job.attempt" :type :int64}]}]}))

(defn- observe-columns [connection]
  [{:columns (into {} (map (juxt :name :type))
                   (jdbc/fetch connection "DESCRIBE TABLE otel_logs"))
    :signal :logs :table "otel_logs"}])

(defn- record [event-name]
  {:timestamp-unix-nano 1700000000000000000
   :observed-time-unix-nano 1700000000000000001
   :trace-id "11111111111111111111111111111111"
   :span-id "2222222222222222" :trace-flags 1
   :severity-number 9 :severity-text "INFO"
   :body {"job" "archive"} :event-name event-name
   :resource (resource/resource {:service.name "log-socket"})
   :scope {:name "typed-log-socket" :version "1"}
   :attributes {"job.name" "archive" "job.complete" false
                "job.attempt" exact-int64 "fallback" "retained"}})

(defn- schema-binding [field]
  {:attribute-key (:key field) :attribute-location (:location field)
   :attribute-type (:type field) :field-id (:id field)
   :manifest-version (get-in field [:identity :version])})

(defn- filter-request [field operator value]
  {:attribute-key (:key field) :attribute-location :log-attributes
   :schema-binding (schema-binding field) :signal :logs
   :start-unix-nano 1699999999999999999
   :end-unix-nano 1700000000000000001
   :operator operator :value value :limit 10 :max-text-length 64})

(defn- rows [connection event-name]
  (jdbc/fetch connection
              ["select * from otel_logs where EventName=? order by Timestamp"
               event-name]))

(defn- caught [f]
  (try (f) nil (catch Throwable error error)))

(defn- check! [label expected actual]
  (if (= expected actual)
    (println "  ok  " label)
    (throw (ex-info label {:expected expected :actual actual}))))

(defn -main [& _]
  (println "typed log attributes over a real OTLP socket")
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (schema/ensure-schema! connection)
    (let [installation
          (installer/install-approved!
           (backend/memory-backend) (compiled)
           {:target connection
            :observe-columns #(observe-columns connection)
            :execute-ddl! #(jdbc/execute! connection %)})
          descriptor-set (:descriptor-set installation)
          fields (get-in installation [:record :manifest :fields])
          field (first (filter #(= "job.attempt" (:key %)) fields))
          value-key (keyword (get-in field [:physical :value-column]))
          status-key (keyword (get-in field [:physical :status-column]))
          receiving
          (chdb-export/exporter
           {:connection connection :create-schema? false :signals #{:logs}
            :typed-log-descriptors descriptor-set})
          listener (atom nil)
          client (atom nil)]
      (try
        (let [direct (record "typed.log.socket")]
          (check! "direct typed log export succeeds" true
                  (sdk-logs/export-logs! receiving [direct]))
          (let [server
                (http-server/run-server
                 (receiver/handler
                  {:parse-body parse-json-body :exporter receiving
                   :max-body-bytes max-body-bytes :max-concurrency 1})
                 :port 0 :server-name "127.0.0.1" :reuse-address? true)
                _ (reset! listener server)
                outbound
                (otlp-export/log-exporter
                 {:endpoint (str "http://127.0.0.1:" (:port server))
                  :timeout-ms 5000 :max-retries 0})]
            (reset! client outbound)
            (check! "canonical log crosses the real loopback OTLP socket" true
                    (sdk-logs/export-logs! outbound [direct])))
          (let [stored (rows connection "typed.log.socket")
                first-row (first stored)]
            (check! "direct and socket paths store byte/data-equivalent rows"
                    [2 true]
                    [(count stored) (every? #(= first-row %) stored)])
            (check! "native readback retains exact Int64 status and fallback"
                    [exact-int64 3
                     {"job.name" "archive" "job.complete" "false"
                      "job.attempt" (str exact-int64) "fallback" "retained"}]
                    [(get first-row value-key) (get first-row status-key)
                     (:logattributes first-row)]))
          (let [captured (atom nil)
                mutant-record (record "typed.log.fixed-column-mutant")]
            (with-redefs [jdbc/execute!
                          (fn [_ statement]
                            (reset! captured statement)
                            {:count 1})]
              (check! "typed insert payload can be captured before execution"
                      true
                      (sdk-logs/export-logs! receiving [mutant-record])))
            (let [fixed-prefix
                  (str "insert into otel_logs ("
                       (str/join ", " schema/clickstack-log-insert-columns)
                       ")")
                  mutant
                  (str/replace-first @captured "insert into otel_logs"
                                     fixed-prefix)
                  error (caught #(jdbc/execute! connection mutant))
                  status (when-not error
                           (get (first (rows connection
                                            "typed.log.fixed-column-mutant"))
                                status-key))]
              (check! "legacy fixed-column mutation cannot persist typed status"
                      true
                      (or (some? error) (not= 3 status)))))
          (let [legacy
                (chdb-export/exporter
                 {:connection connection :create-schema? false
                  :signals #{:logs}})]
            (try
              (check! "control log without the capability still exports" true
                      (sdk-logs/export-logs!
                       legacy [(record "typed.log.without-capability")]))
              (let [control (first (rows connection
                                         "typed.log.without-capability"))]
                (check! "removing the capability causally leaves typed status historical"
                        [0 {"job.name" "archive" "job.complete" "false"
                            "job.attempt" (str exact-int64)
                            "fallback" "retained"}]
                        [(get control status-key) (:logattributes control)]))
              (finally (sdk-logs/shutdown-log-exporter! legacy)))))
          (doseq [[key operator value expected]
                  [["job.name" :prefix "arch" "archive"]
                   ["job.complete" :eq false false]
                   ["job.attempt" :gte exact-int64 exact-int64]]]
            (let [query-field (first (filter #(= key (:key %)) fields))
                  result (log-explorer/typed-log-filtered-records
                          connection descriptor-set
                          (filter-request query-field operator value))]
              (check! (str "native typed log filter and coverage: " key)
                      [4 2 2 2 expected]
                      [(get-in result [:coverage :total])
                       (get-in result [:coverage :valid])
                       (get-in result [:coverage
                                       :historical-untyped-fallback])
                       (count (:matches result))
                       (:attribute-value (first (:matches result)))])))
          (let [stale-field (first fields)
                stale (assoc-in (filter-request stale-field :eq "archive")
                                [:schema-binding :manifest-version] 2)]
            (check! "wrong typed log schema binding fails before SQL"
                    :otel.exporter.chdb.typed-log-explorer/stale-binding
                    (:type (ex-data
                            (caught
                             #(log-explorer/typed-log-filtered-records
                               connection descriptor-set stale))))))
        (finally
          (when-let [outbound @client]
            (sdk-logs/shutdown-log-exporter! outbound))
          (when-let [server @listener]
            (http-server/stop-server server))
          (sdk-logs/shutdown-log-exporter! receiving)))))
  (println "all typed log socket/native checks passed"))
