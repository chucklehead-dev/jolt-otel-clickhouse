(ns otel.exporter.chdb-typed-gauge-socket-native-test
  "Real loopback OTLP/JSON evidence for the bounded typed metric surface.

  It covers gauge point/resource/scope and sum point descriptors. Sum
  resource/scope, histograms, and Durable recovery remain outside this test."
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
            [otel.exporter.chdb.typed-metric-explorer :as metric-explorer]
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

(def ^:private observed-checks (atom 0))

(defn- gauge-compiled []
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
                 :location :metric-attributes :key "queue.label" :type :string}
                {:signal :metrics :table "otel_metrics_gauge"
                 :location :resource-attributes :key "resource.ready" :type :boolean}
                {:signal :metrics :table "otel_metrics_gauge"
                 :location :scope-attributes :key "scope.workers" :type :int64}]}]}))

(defn- sum-compiled []
  (manifest/compile-manifest
   {:dataset-id "telemetry-prod" :application-id "sum-socket"
    :lineage "sum-socket-v1" :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema :authority :advice
      :source "advice/sum-socket.edn"
      :entries [{:signal :metrics :table "otel_metrics_sum"
                 :location :metric-attributes :key "request.success" :type :boolean}
                {:signal :metrics :table "otel_metrics_sum"
                 :location :metric-attributes :key "request.count" :type :int64}]}]}))

(defn- observe-columns [connection table]
  [{:columns (into {} (map (juxt :name :type))
                   (jdbc/fetch connection (str "DESCRIBE TABLE " table)))
    :signal :metrics :table table}])

(defn- collected [gauge-name sum-name]
  [{:scope {:name "typed-gauge-socket" :version "1"
            :attributes {"scope.workers" 7 "scope.generic" "kept-generic"}}
    :metrics [{:type :gauge :name gauge-name :description "" :unit "{item}"
               :data-points
               [{:value 2.0 :time-unix-nano 1700000000000000000
                 :attributes {"queue.ready" false "queue.zero" 0
                              "queue.minimum" int64-min "queue.maximum" int64-max
                              ;; This is a present empty string, distinct from
                              ;; an absent descriptor key (status 2, not 1).
                              "queue.label" ""
                              "generic.empty-string" ""
                              "generic.bytes" (any/bytes [0 1 2 255])
                              "generic.nested" {"kind" "nested" "count" 2}}}]}
              {:type :sum :name sum-name :description "" :unit "1"
               :temporality :cumulative :monotonic? true
               :data-points
               [{:value 3.0 :time-unix-nano 1700000000000000000
                 :attributes {"request.success" false "request.count" int64-max
                              "generic.bytes" (any/bytes [0 1 2 255])
                              "generic.nested" {"kind" "nested" "count" 2}}}]}]}])

(defn- fields-by-key [installation]
  (into {} (map (juxt :key identity))
        (get-in installation [:record :manifest :fields])))

(defn- rows [connection table metric-name]
  (jdbc/fetch connection
              [(str "select * from " table " where MetricName=? order by TimeUnix")
               metric-name]))

(defn- check! [label expected actual]
  (swap! observed-checks inc)
  (if (= expected actual)
    (println "  ok  " label)
    (throw (ex-info label {:expected expected :actual actual}))))

(defn- field-pair [row fields key]
  (let [physical (:physical (get fields key))]
    [(get row (keyword (:value-column physical)))
     (get row (keyword (:status-column physical)))]))

(defn- schema-binding [field]
  {:attribute-key (:key field) :attribute-location (:location field)
   :attribute-type (:type field) :field-id (:id field)
   :manifest-version (get-in field [:identity :version])})

(defn- gauge-query [field]
  {:schema-binding (schema-binding field) :signal :metrics :metric-kind :gauge
   :start-unix-nano 1700000000000000000
   :end-unix-nano 1700000001000000000
   :operator :eq :value false :limit 10 :max-text-length 64})

(defn -main [& _]
  (reset! observed-checks 0)
  (println "typed metric attributes over a real OTLP socket")
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (schema/ensure-schema! connection)
    (let [store (backend/memory-backend)
          gauges (installer/install-approved!
                  store (gauge-compiled)
                  {:target connection
                   :observe-columns #(observe-columns connection "otel_metrics_gauge")
                   :execute-ddl! #(jdbc/execute! connection %)})
          sums (installer/install-approved!
                store (sum-compiled)
                {:target connection
                 :observe-columns #(observe-columns connection "otel_metrics_sum")
                 :execute-ddl! #(jdbc/execute! connection %)})
          gauge-fields (fields-by-key gauges)
          sum-fields (fields-by-key sums)
          receiving (chdb-export/exporter
                     {:connection connection :create-schema? false :signals #{:metrics}
                      :typed-gauge-descriptors (:descriptor-set gauges)
                      :typed-sum-descriptors (:descriptor-set sums)})
          listener (atom nil) client (atom nil)]
      (try
        (let [gauge-name "typed.gauge.socket" sum-name "typed.sum.socket"
              r (resource/resource {"service.name" "typed-gauge-socket"
                                     "resource.ready" false
                                     "resource.generic" "kept-generic"})
              values (collected gauge-name sum-name)]
          (check! "direct typed gauge and sum export succeeds" true
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
            (check! "canonical gauge and sum cross the real loopback OTLP socket" true
                    (export/export-metrics! outbound r values)))
          (let [gauge-stored (rows connection "otel_metrics_gauge" gauge-name)
                sum-stored (rows connection "otel_metrics_sum" sum-name)
                gauge-row (first gauge-stored) sum-row (first sum-stored)]
            (check! "direct and socket paths store identical physical gauge rows"
                    [2 true] [(count gauge-stored) (every? #(= gauge-row %) gauge-stored)])
            (check! "direct and socket paths store identical physical sum rows"
                    [2 true] [(count sum-stored) (every? #(= sum-row %) sum-stored)])
            (check! "gauge typed point resource scope values and statuses survive socket decode"
                    [[false 3] [7 3] [false 3] [0 3] [int64-min 3] [int64-max 3] ["" 2]]
                    (vec (concat [(field-pair gauge-row gauge-fields "resource.ready")
                                  (field-pair gauge-row gauge-fields "scope.workers")]
                                 (map #(field-pair gauge-row gauge-fields %)
                                      ["queue.ready" "queue.zero" "queue.minimum"
                                       "queue.maximum" "queue.label"]))))
            (check! "sum typed point values and statuses survive socket decode"
                    [[false 3] [int64-max 3]]
                    (mapv #(field-pair sum-row sum-fields %)
                          ["request.success" "request.count"]))
            (check! "generic structured bytes maps and unpromoted resource scope fields survive"
                    [{"queue.ready" "false" "queue.zero" "0"
                      "queue.minimum" (str int64-min) "queue.maximum" (str int64-max)
                      "queue.label" "" "generic.empty-string" ""
                      "generic.bytes" "AAEC/w=="
                      "generic.nested" "{\"count\":2,\"kind\":\"nested\"}"}
                     {"request.success" "false" "request.count" (str int64-max)
                      "generic.bytes" "AAEC/w=="
                      "generic.nested" "{\"count\":2,\"kind\":\"nested\"}"}
                     {"resource.generic" "kept-generic" "resource.ready" "false"
                      "service.name" "typed-gauge-socket"}
                     {"scope.generic" "kept-generic" "scope.workers" "7"}]
                    [(:attributes gauge-row) (:attributes sum-row)
                     (:resourceattributes gauge-row) (:scopeattributes gauge-row)]))
          (let [result (metric-explorer/typed-gauge-filtered-points
                        connection (:descriptor-set gauges)
                        (gauge-query (get gauge-fields "queue.ready")))]
            (check! "receiver-produced gauge rows support typed query and coverage"
                    [{:valid 2 :present-empty 0 :absent 0 :invalid 0
                      :historical-untyped-fallback 0
                      :historical-untyped-unavailable 0 :total 2}
                     [false false]]
                    [(:coverage result)
                     (mapv :attribute-value (:matches result))]))
          ;; A capability-free exporter is a mutation/bypass control: generic
          ;; fields still persist, while every installed typed status is 0.
          (let [legacy (chdb-export/exporter {:connection connection :create-schema? false
                                              :signals #{:metrics}})
                gauge-control "typed.gauge.without-capability"
                sum-control "typed.sum.without-capability"]
            (try
              (check! "capability-free gauge and sum export succeeds" true
                      (export/export-metrics! legacy (resource/resource {})
                                              (collected gauge-control sum-control)))
              (let [gauge-row (first (rows connection "otel_metrics_gauge" gauge-control))
                    sum-row (first (rows connection "otel_metrics_sum" sum-control))]
                (check! "capability bypass leaves gauge resource scope and point columns historical"
                        [0 0 0 0 0 0 0]
                        (mapv #(second (field-pair gauge-row gauge-fields %))
                              ["resource.ready" "scope.workers" "queue.ready" "queue.zero"
                               "queue.minimum" "queue.maximum" "queue.label"]))
                (check! "capability bypass leaves sum point columns historical and retains bytes"
                        [[0 0] "AAEC/w=="]
                        [(mapv #(second (field-pair sum-row sum-fields %))
                               ["request.success" "request.count"])
                         (get (:attributes sum-row) "generic.bytes")]))
              (finally (export/shutdown-metric-exporter! legacy)))))
        (finally
          (when-let [outbound @client]
            (export/shutdown-metric-exporter! outbound))
          (when-let [server @listener]
            (http-server/stop-server server))
          (export/shutdown-metric-exporter! receiving)))))
  (when-not (= 11 @observed-checks)
    (throw (ex-info "typed metric socket check inventory changed"
                    {:expected 11 :actual @observed-checks})))
  (println "typed-metric-socket-qualified :observed-checks" @observed-checks))
