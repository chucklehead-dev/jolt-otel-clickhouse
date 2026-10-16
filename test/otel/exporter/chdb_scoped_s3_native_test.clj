(ns otel.exporter.chdb-scoped-s3-native-test
  "Public SDK/exporter + scoped S3 writer; fresh-process full-row recovery."
  (:require [clojure.edn :as edn]
            [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.s3-curl :as curl]
            [jdbc.chdb.durable.s3-writer :as s3-writer]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-wire-test-config :as wire-config]
            [otel.logs :as logs]
            [otel.metrics :as metrics]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(defn- check! [condition label]
  (when-not condition
    ;; Never attach telemetry, SQL, credentials or arbitrary backend errors.
    (throw (ex-info "Scoped S3 exporter check failed" {:check label}))))

(defn- options [endpoint]
  {:endpoint endpoint :bucket "bucket" :prefix "exporter-scoped"
   :region "us-east-1" :access-key "ACCESS" :secret-key "SECRET"
   :session-token "SESSION" :max-attempts 1
   :connect-timeout-ms 2000 :timeout-ms 15000})

(def ^:private selections
  {:spans "SELECT * FROM otel_traces ORDER BY SpanName, TraceId, SpanId"
   :logs "SELECT * FROM otel_logs ORDER BY Body, TraceId, SpanId, Timestamp"
   :metrics "SELECT * FROM otel_metrics_sum ORDER BY MetricName, TimeUnix, Value"})

(defn- sha256 [text]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes text "UTF-8"))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn- full-row-receipt [connection]
  (into {} (map (fn [[signal sql]]
                  (let [rows (vec (jdbc/fetch connection sql))]
                    [signal {:rows (count rows)
                             :sha256 (sha256 (binding [*print-length* nil *print-level* nil]
                                               (pr-str rows)))}])))
        selections))

(defn- verify-values! [connection]
  (let [spans (jdbc/fetch connection
                "SELECT SpanName, ServiceName, SpanAttributes, TraceId, SpanId, ParentSpanId FROM otel_traces ORDER BY SpanName")
        records (jdbc/fetch connection
                  "SELECT Body, ServiceName, LogAttributes, TraceId, SpanId FROM otel_logs ORDER BY Body")
        points (jdbc/fetch connection
                 "SELECT MetricName, Value, Attributes FROM otel_metrics_sum ORDER BY TimeUnix")]
    (check! (= ["inner" "outer"] (mapv :spanname spans)) :span-names)
    (check! (= ["scoped-s3" "scoped-s3"] (mapv :servicename spans)) :span-service)
    (check! (= [{"work.count" "2"} {"work.ready" "true"}]
               (mapv :spanattributes spans)) :span-attributes)
    (check! (= ["captured é😀"] (mapv :body records)) :log-body)
    (check! (= ["scoped-s3"] (mapv :servicename records)) :log-service)
    (check! (= [{"log.ready" "false"}] (mapv :logattributes records)) :log-attributes)
    (check! (= (:spanid (second spans)) (:parentspanid (first spans))) :parent-child)
    (check! (and (not-empty (:traceid (first spans)))
                 (= (:traceid (first spans)) (:traceid (second spans))
                    (:traceid (first records)))) :trace-correlation)
    (check! (= (:spanid (second spans)) (:spanid (first records))) :log-span-correlation)
    (check! (seq points) :metric-not-empty)
    (check! (every? #(= "work.completed" (:metricname %)) points) :metric-name)
    ;; Native JSON can spell the Float64 value 3 without a decimal suffix.
    ;; Check numeric value, not the JSON reader's integral/floating wrapper.
    (check! (every? #(and (number? (:value %)) (== 3 (:value %))) points) :metric-value)
    (check! (every? #(= {"work.ready" "true"} (:attributes %)) points)
            :metric-attributes)))

(def ^:dynamic ^:private *insert-format* :json-each-row)

(defn- writer-body! [endpoint root]
  (s3-writer/with-connection!
    (options endpoint)
    {:object-id "telemetry" :owner "sdk-integration" :database "otel"
     :scratch-parent root}
    (fn [connection]
      (let [target (exporter/exporter
                     {:connection connection :durable? true :insert-format *insert-format*
                      :signals #{:spans :logs :metrics}})
            handle (sdk/init! {:service-name "scoped-s3" :exporter target
                               :processor :simple :metrics? true :logs? true
                               :runtime-metrics? false :metric-interval-ms 600000
                               :bridge-logging? false})]
        (try
          (trace/with-span [outer (sdk/tracer "integration") "outer"
                           {:attributes {"work.ready" true}}]
            (logs/emit! (sdk/logger "integration")
                        {:body "captured é😀" :severity :info
                         :attributes {"log.ready" false}})
            (trace/with-span [inner (sdk/tracer "integration") "inner"
                             {:attributes {"work.count" 2}}]
              (trace/set-status! inner :ok)))
          (metrics/add! (metrics/counter (sdk/meter "integration") "work.completed"
                                         {:unit "{work}"})
                        3 {"work.ready" true})
          (check! (sdk/force-flush! handle) :sdk-flush)
          (finally (check! (sdk/shutdown! handle) :sdk-shutdown)))
        (check! (= :confirmed (:state (durable/persistence-observation connection)))
                :confirmed-after-sdk-shutdown)
        (verify-values! connection)
        (spit (str root "/writer.edn") (pr-str (full-row-receipt connection))))))
  (let [store (backend/object-backend (curl/s3-backend (options endpoint)) "telemetry")]
    (check! (nil? (get-in (:head (control/read-head! store)) ["lease" "owner"]))
            :lease-released))
  nil)

(defn- writer! [endpoint root]
  (let [inits (atom []) cleanups (atom []) performs (atom [])
        original-init @#'curl/curl-easy-init
        original-cleanup @#'curl/curl-easy-cleanup
        original-perform @#'curl/curl-easy-perform]
    ;; Count the real native calls, never substitute a transport response.
    (with-redefs-fn
      {#'curl/curl-easy-init
       (fn [] (let [handle (original-init)] (swap! inits conj handle) handle))
       #'curl/curl-easy-cleanup (fn [h] (swap! cleanups conj h) (original-cleanup h))
       #'curl/curl-easy-perform (fn [h] (swap! performs conj h) (original-perform h))}
      #(writer-body! endpoint root))
    ;; Two persistent owners, then one independent lease-release readback.
    (check! (= 3 (count @inits) (count @cleanups)) :exact-owner-settlement)
    (check! (every? (fn [h] (= 1 (count (filter #(identical? h %) @cleanups)))) @inits)
            :each-owner-cleaned-once)
    (let [requests (mapv (fn [h] (count (filter #(identical? h %) @performs))) @inits)]
      (check! (and (> (nth requests 0) 1) (> (nth requests 1) 1)
                   (= 1 (nth requests 2))
                   (= (reduce + requests) (count @performs))) :real-reused-http)
      ;; Counts may vary with legitimate heartbeat/reconciliation scheduling;
      ;; the invariant is which owned handle performs each actual request.
      (prn {:gate :scoped-s3-sdk-native-owners :inits (count @inits)
            :cleanups (count @cleanups) :requests (count @performs)
            :per-owner-requests requests})))
  (println "scoped-s3-sdk-writer-qualified"))

(defn- reader! [endpoint root]
  (with-open [connection (jdbc/connection
                          (durable/snapshot-dbspec
                            {:namespace-backend (curl/s3-backend (options endpoint))
                             :object-id "telemetry" :scratch-parent root}))]
    (verify-values! connection)
    (check! (= (edn/read-string (slurp (str root "/writer.edn")))
               (full-row-receipt connection)) :all-physical-rows-recovered))
  (println "scoped-s3-sdk-reader-qualified"))

(defn -main [phase endpoint root & formats]
  (let [format (wire-config/parse-format formats)]
    (binding [*insert-format* format]
      (case phase "writer" (writer! endpoint root) "reader" (reader! endpoint root)))))
