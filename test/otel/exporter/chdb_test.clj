(ns otel.exporter.chdb-test
  (:require [db.jdbc]
            [jdbc.chdb]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb-property-test :as property]
            [otel.logs :as logs]
            [otel.metrics :as metrics]
            [otel.resource :as resource]
            [otel.sdk :as sdk]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as sdk-logs]
            [otel.sdk.metrics :as sdk-metrics]
            [otel.trace :as trace]))

(def failures (atom 0))

(defn- check [label expected actual]
  (if (= expected actual)
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "- expected" (pr-str expected) "got" (pr-str actual)))))

(defn -main [& _]
  (reset! failures 0)
  (println "embedded chDB OTel exporter")
  (with-open [conn (jdbc/connection "chdb::memory:")]
    (let [exporter (chdb-export/exporter {:connection conn})
          handle (sdk/init! {:service-name "ring-demo"
                             :exporter exporter
                             :processor :batch
                             :metrics? false
                             :logs? true
                             :bridge-logging? false})]
      (try
        (trace/with-span [outer (sdk/tracer "demo.http") "GET /outbound"
                          {:kind :server :attributes {:http.route "/outbound"}}]
          (logs/emit! (sdk/logger "demo.http")
                      {:body "calling upstream" :severity :info
                       :attributes {:http.method "GET"}})
          (trace/with-span [inner (sdk/tracer "demo.client") "GET example"
                            {:kind :client}]
            (trace/set-status! inner :ok)))
        (let [r (resource/resource {:service.name "ring-demo"})
              provider (sdk-metrics/meter-provider {:resource r})
              meter (sdk-metrics/get-meter provider {:name "demo.metrics"})]
          (metrics/add! (metrics/counter meter "requests") 2 {:route "/work"})
          (metrics/set-value! (metrics/gauge meter "queue.depth") 3)
          (metrics/record! (metrics/histogram meter "latency" {:boundaries [10.0 100.0]}) 42)
          (check "metric export call succeeds" true
                 (export/export-metrics! exporter r (sdk-metrics/collect! provider))))
        ;; sdk/shutdown! reaches the log and span pipelines separately. Both
        ;; batch queues must drain even after the first signal shuts down.
        (sdk/shutdown! handle)
        (let [spans (jdbc/fetch conn
                                "select TraceId, SpanId, ParentSpanId, SpanName, ServiceName, SpanAttributes from otel_traces order by Timestamp")
              log (jdbc/fetch-one conn
                                  "select TraceId, SpanId, Body, ServiceName, SeverityText from otel_logs")]
          (check "parent and child spans persisted" 2 (count spans))
          (check "ClickStack service column" #{"ring-demo"}
                 (set (map :servicename spans)))
          (check "child points at parent span" true
                 (= (:spanid (first spans)) (:parentspanid (second spans))))
          (check "log body persisted" "calling upstream" (:body log))
          (check "log/span trace correlation" (:traceid (first spans)) (:traceid log))
          (check "severity uses ClickStack column" "INFO" (:severitytext log)))
        (check "ClickStack gauge table" 1
               (:n (jdbc/fetch-one conn "select count() as n from otel_metrics_gauge")))
        (check "ClickStack sum table" 1
               (:n (jdbc/fetch-one conn "select count() as n from otel_metrics_sum")))
        (check "ClickStack histogram table" 1
               (:n (jdbc/fetch-one conn "select count() as n from otel_metrics_histogram")))
        (finally (sdk/shutdown! handle)))))
  (let [exporter (chdb-export/exporter {:db-spec "chdb::memory:"
                                        :signals #{:spans}})]
    (check "undeclared signal export is rejected" false
           (sdk-logs/export-logs! exporter []))
    (check "undeclared signal failure is diagnosable" :logs
           (:signal (ex-data (chdb-export/last-error exporter))))
    (check "declared signal still owns shutdown" true
           (export/shutdown-exporter! exporter)))
  (doseq [{:keys [label result]} (property/run-properties!)]
    (println "  hegel" label "seed" (:seed result))
    (check (str "Hegel " label) true (:passed? result))
    (check (str "Hegel " label " is deterministic") false (:flaky? result)))
  (if (zero? @failures)
    (println "all checks passed")
    (throw (ex-info (str @failures " checks failed") {:failures @failures}))))
