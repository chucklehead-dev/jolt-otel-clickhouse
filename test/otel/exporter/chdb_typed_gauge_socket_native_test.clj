(ns otel.exporter.chdb-typed-gauge-socket-native-test
  "A real loopback OTLP/JSON boundary for the initially supported typed metric
  surface: gauge point attributes only.  It intentionally does not imply typed
  promotion for sums, histograms, resource, or scope attributes."
  (:require [clojure.data.json :as json]
            [db.jdbc]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [jolt.http.body :as http-body]
            [jolt.http.server :as http-server]
            [otel.any-value :as any]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.otlp :as otlp-export]
            [otel.otlp.http-receiver :as receiver]
            [otel.resource :as resource]
            [otel.sdk.export :as export]))

(def ^:private max-body-bytes (* 1024 1024))
(def ^:private int64-min -9223372036854775808)
(def ^:private int64-max 9223372036854775807)

(defn- concat-chunks [chunks total]
  (let [result (byte-array total)]
    (loop [remaining chunks offset 0]
      (when-let [chunk (first remaining)]
        (System/arraycopy chunk 0 result offset (alength chunk))
        (recur (next remaining) (+ offset (alength chunk)))))
    result))

(defn- parse-json-body [request limit]
  (loop [chunks [] total 0]
    (if-let [chunk (http-body/body-recv (:body request))]
      (let [actual (+ total (alength chunk))]
        (when (> actual limit)
          (throw (receiver/body-too-large limit actual)))
        (recur (conj chunks chunk) actual))
      (let [encoded (concat-chunks chunks total)]
        {:value (json/read-str (String. encoded "UTF-8"))
         :encoded-bytes (alength encoded)}))))

(defn- compiled []
  (manifest/compile-manifest
   {:dataset-id "telemetry-prod" :application-id "gauge-socket"
    :lineage "gauge-socket-v1" :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema :authority :advice
      :source "advice/gauge-socket.edn"
      :entries [{:signal :metrics :table "otel_metrics_gauge"
                 :location :metric-attributes :key "queue.ready" :type :boolean}
                {:signal :metrics :table "otel_metrics_gauge"
                 :location :metric-attributes :key "queue.zero" :type :int64}
                {:signal :metrics :table "otel_metrics_gauge"
                 :location :metric-attributes :key "queue.minimum" :type :int64}
                {:signal :metrics :table "otel_metrics_gauge"
                 :location :metric-attributes :key "queue.maximum" :type :int64}
                {:signal :metrics :table "otel_metrics_gauge"
                 :location :metric-attributes :key "queue.label" :type :string}]}]}))

(defn- observe-columns [connection]
  [{:columns (into {} (map (juxt :name :type))
                   (jdbc/fetch connection "DESCRIBE TABLE otel_metrics_gauge"))
    :signal :metrics :table "otel_metrics_gauge"}])

(defn- collected [name]
  [{:scope {:name "typed-gauge-socket" :version "1"
            ;; Deliberately generic: this must not be promoted by the gauge-only
            ;; descriptor capability.
            :attributes {"scope.generic" "kept-generic"}}
    :metrics [{:type :gauge :name name :description "" :unit "{item}"
               :data-points
               [{:value 2.0 :time-unix-nano 1700000000000000000
                 :attributes {"queue.ready" false "queue.zero" 0
                              "queue.minimum" int64-min "queue.maximum" int64-max
                              ;; This is a present empty string, distinct from
                              ;; an absent descriptor key (status 2, not 1).
                              "queue.label" ""
                              "generic.empty-string" ""
                              "generic.bytes" (any/bytes [0 1 2 255])
                              "generic.nested" {"kind" "nested" "count" 2}}}]}]}])

(defn- fields-by-key [installation]
  (into {} (map (juxt :key identity))
        (get-in installation [:record :manifest :fields])))

(defn- rows [connection metric-name]
  (jdbc/fetch connection
              ["select * from otel_metrics_gauge where MetricName=? order by TimeUnix"
               metric-name]))

(defn- check! [label expected actual]
  (if (= expected actual)
    (println "  ok  " label)
    (throw (ex-info label {:expected expected :actual actual}))))

(defn- field-pair [row fields key]
  (let [physical (:physical (get fields key))]
    [(get row (keyword (:value-column physical)))
     (get row (keyword (:status-column physical)))]))

(defn -main [& _]
  (println "typed gauge point attributes over a real OTLP socket")
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (schema/ensure-schema! connection)
    (let [installation (installer/install-approved!
                        (backend/memory-backend) (compiled)
                        {:target connection :observe-columns #(observe-columns connection)
                         :execute-ddl! #(jdbc/execute! connection %)})
          descriptor-set (:descriptor-set installation)
          fields (fields-by-key installation)
          receiving (chdb-export/exporter
                     {:connection connection :create-schema? false :signals #{:metrics}
                      :typed-gauge-descriptors descriptor-set})
          listener (atom nil) client (atom nil)]
      (try
        (let [metric-name "typed.gauge.socket"
              r (resource/resource {"service.name" "typed-gauge-socket"
                                     ;; Explicitly generic for this bounded slice.
                                     "resource.generic" "kept-generic"})
              values (collected metric-name)]
          (check! "direct typed gauge export succeeds" true
                  (export/export-metrics! receiving r values))
          (let [server (http-server/run-server
                        (receiver/handler {:parse-body parse-json-body
                                           :metric-exporter receiving
                                           :max-body-bytes max-body-bytes
                                           :max-concurrency 1})
                        :port 0 :server-name "127.0.0.1" :reuse-address? true)
                _ (reset! listener server)
                outbound (otlp-export/metric-exporter
                          {:endpoint (str "http://127.0.0.1:" (:port server))
                           :timeout-ms 5000 :max-retries 0})]
            (reset! client outbound)
            (check! "canonical gauge crosses the real loopback OTLP socket" true
                    (export/export-metrics! outbound r values)))
          (let [stored (rows connection metric-name) first-row (first stored)]
            (check! "direct and socket paths store identical physical gauge rows"
                    [2 true] [(count stored) (every? #(= first-row %) stored)])
            (check! "typed false, zero, signed Int64 boundaries, and present empty retain status"
                    [[false 3] [0 3] [int64-min 3] [int64-max 3] ["" 2]]
                    (mapv #(field-pair first-row fields %)
                          ["queue.ready" "queue.zero" "queue.minimum"
                           "queue.maximum" "queue.label"]))
            (check! "generic empty string, bytes, nested value, resource and scope survive without promotion"
                    [{"queue.ready" "false" "queue.zero" "0"
                      "queue.minimum" (str int64-min) "queue.maximum" (str int64-max)
                      "queue.label" "" "generic.empty-string" ""
                      "generic.bytes" "AAEC/w=="
                      "generic.nested" "{\"count\":2,\"kind\":\"nested\"}"}
                     {"resource.generic" "kept-generic" "service.name" "typed-gauge-socket"}
                     {"scope.generic" "kept-generic"}]
                    [(:attributes first-row) (:resourceattributes first-row)
                     (:scopeattributes first-row)]))
          ;; A capability-free exporter is a mutation/bypass control: its generic
          ;; attributes still persist, but it cannot synthesize typed status 3.
          (let [legacy (chdb-export/exporter {:connection connection :create-schema? false
                                              :signals #{:metrics}})
                control "typed.gauge.without-capability"]
            (try
              (check! "capability-free gauge export succeeds" true
                      (export/export-metrics! legacy (resource/resource {}) (collected control)))
              (let [row (first (rows connection control))]
                (check! "capability bypass leaves every typed column historical"
                        [0 0 0 0 0]
                        (mapv #(second (field-pair row fields %))
                              ["queue.ready" "queue.zero" "queue.minimum"
                               "queue.maximum" "queue.label"]))
                (check! "capability bypass retains generic attributes"
                        "AAEC/w==" (get (:attributes row) "generic.bytes")))
              (finally (export/shutdown-metric-exporter! legacy)))))
        (finally
          (when-let [outbound @client]
            (export/shutdown-metric-exporter! outbound))
          (when-let [server @listener]
            (http-server/stop-server server))
          (export/shutdown-metric-exporter! receiving)))))
  (println "all typed gauge socket/native checks passed"))
