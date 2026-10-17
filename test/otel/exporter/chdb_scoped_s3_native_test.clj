(ns otel.exporter.chdb-scoped-s3-native-test
  "Public SDK/exporter + scoped S3 writer; fresh-process full-row recovery."
  (:require [clojure.edn :as edn]
            [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.owned-statement :as owned]
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

(defn- check-sdk! [condition label target handle]
  (when-not condition
    (let [data (ex-data (exporter/last-error target))]
      (throw (ex-info "Scoped S3 SDK operation failed"
                      {:check label :exporter-type (:type data)
                       :metric-worker (select-keys @(:state (:reader handle))
                                                  [:worker-export-failed?
                                                   :shutdown-cancelled?
                                                   :worker-interrupt-count
                                                   :worker-export-active?])
                       :jdbc-sql-error (true? (:jdbc/sql-error data))})))))

(defn- options [endpoint]
  {:endpoint endpoint :bucket "bucket" :prefix "exporter-scoped"
   :region "us-east-1" :access-key "ACCESS" :secret-key "SECRET"
   :session-token "SESSION" :max-attempts 1
   :connect-timeout-ms 2000 :timeout-ms 15000})

(def ^:private selections
  {:spans "SELECT * FROM otel_traces ORDER BY SpanName, TraceId, SpanId"
   :logs "SELECT * FROM otel_logs ORDER BY Body, TraceId, SpanId, Timestamp"
   :metrics "SELECT * FROM otel_metrics_sum ORDER BY MetricName, TimeUnix, Value"
   :gauges "SELECT * FROM otel_metrics_gauge ORDER BY MetricName, TimeUnix, Value"
   :histograms "SELECT * FROM otel_metrics_histogram ORDER BY MetricName, TimeUnix, Count, Sum"})

(defn- sha256 [text]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes text "UTF-8"))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn- full-row-receipt [connection]
  ;; Keep this obligation independent of selections: writer and reader sharing
  ;; an accidentally incomplete table list must not validate each other.
  (check! (= #{:spans :logs :metrics :gauges :histograms} (set (keys selections)))
          :all-physical-table-receipts)
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
                 "SELECT MetricName, Value, Attributes FROM otel_metrics_sum ORDER BY TimeUnix")
        gauges (jdbc/fetch connection "SELECT MetricName, Value, Attributes FROM otel_metrics_gauge")
        histograms (jdbc/fetch connection
                     "SELECT MetricName, Count n, Sum total, BucketCounts buckets, ExplicitBounds bounds, Min lo, Max hi, Attributes FROM otel_metrics_histogram")]
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
            :metric-attributes)
    (check! (seq gauges) :gauge-not-empty)
    (check! (every? #(and (= "work.depth" (:metricname %)) (== 7.5 (:value %))
                          (= {"work.ready" "true"} (:attributes %))) gauges)
            :gauge-values)
    (check! (seq histograms) :histogram-not-empty)
    (check! (every? #(and (= "work.duration" (:metricname %))
                          (= 2 (:n %)) (== 13.5 (:total %))
                          (= [0 1 1 0] (:buckets %))
                          (= [1.0 5.0 10.0] (mapv double (:bounds %)))
                          (== 4.5 (:lo %)) (== 9.0 (:hi %))
                          (= {"work.kind" "sample"} (:attributes %))) histograms)
            :histogram-values)))

(def ^:dynamic ^:private *insert-format* :json-each-row)
(def ^:dynamic ^:private *owned-output?* false)

(defn- writer-body! [endpoint root]
  (s3-writer/with-connection!
    (options endpoint)
    {:object-id "telemetry" :owner "sdk-integration" :database "otel"
     :scratch-parent root}
    (fn [connection]
      (let [target (exporter/exporter
                     (cond-> {:connection connection :durable? true :insert-format *insert-format*
                              :signals #{:spans :logs :metrics}}
                       *owned-output?* (assoc :json-backend :native-guarded-byte-batch
                                             :owned-statement-output? true)))
            handle (sdk/init! {:service-name "scoped-s3" :exporter target
                               :processor :simple :metrics? true :logs? true
                               :runtime-metrics? false :metric-interval-ms 600000
                               :metric-reader-options {:shutdown-cancel-grace-ms 2000
                                                       :shutdown-join-bound-ms 3000}
                               :bridge-logging? false})
            primary (atom nil)]
        (try
          (check! (= 2000 (get-in handle [:reader :config :shutdown-cancel-grace-ms]))
                  :metric-policy-installed)
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
          (metrics/set-value! (metrics/gauge (sdk/meter "integration") "work.depth"
                                              {:unit "{work}"})
                              7.5 {"work.ready" true})
          (let [histogram (metrics/histogram
                           (sdk/meter "integration") "work.duration"
                           {:unit "ms" :boundaries [1.0 5.0 10.0]})]
            (metrics/record! histogram 4.5 {"work.kind" "sample"})
            (metrics/record! histogram 9.0 {"work.kind" "sample"}))
          (check-sdk! (sdk/force-flush! handle) :sdk-flush target handle)
          (catch Throwable error (reset! primary error) (throw error))
          (finally
            (try
              (check-sdk! (sdk/shutdown! handle) :sdk-shutdown target handle)
              (catch Throwable error
                (if @primary
                  (println :secondary-sdk-shutdown-failed true)
                  (throw error))))))
        (check! (false? (:shutdown-cancelled? @(:state (:reader handle))))
                :metric-drain-not-cancelled)
        (check! (= :confirmed (:state (durable/persistence-observation connection)))
                :confirmed-after-sdk-shutdown)
        (verify-values! connection)
        (spit (str root "/writer.edn") (pr-str (full-row-receipt connection))))))
  (let [store (backend/object-backend (curl/s3-backend (options endpoint)) "telemetry")]
    (check! (nil? (get-in (:head (control/read-head! store)) ["lease" "owner"]))
            :lease-released))
  nil)

(defn- writer! [endpoint root]
  (let [inits (atom []) cleanups (atom []) performs (atom []) owned-requests (atom 0)
        original-owned durable/execute-owned-and-flush!
        original-init @#'curl/curl-easy-init
        original-cleanup @#'curl/curl-easy-cleanup
        original-perform @#'curl/curl-easy-perform]
    ;; Count the real native calls, never substitute a transport response.
    (with-redefs-fn
      (cond-> {#'curl/curl-easy-init
       (fn [] (let [handle (original-init)] (swap! inits conj handle) handle))
       #'curl/curl-easy-cleanup (fn [h] (swap! cleanups conj h) (original-cleanup h))
       #'curl/curl-easy-perform (fn [h] (swap! performs conj h) (original-perform h))}
        *owned-output?*
        (assoc #'owned/text (fn [_] (throw (ex-info "Unexpected owned SQL text materialization" {:fixture true})))
               #'durable/execute-owned-and-flush!
               (fn [connection statement]
                 (check! (owned/statement? statement) :owned-request-sealed)
                 (swap! owned-requests inc) (original-owned connection statement))))
      #(if (= :json-compact-each-row *insert-format*)
         ;; A real SDK export must use the guarded direct constructors, not
         ;; merely store equivalent rows through the old intermediate maps.
         (with-redefs [exporter/compact-rows
                       (fn [& _] (throw (ex-info "Unexpected map projection" {:fixture true})))]
           (writer-body! endpoint root))
         (writer-body! endpoint root)))
    (when *owned-output?*
      (check! (>= @owned-requests 5) :actual-owned-sdk-requests)
      (prn {:gate :scoped-s3-sdk-owned-requests :requests @owned-requests}))
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
  (let [format (wire-config/parse-format formats)
        mode (System/getenv "JOLT_SCOPED_S3_OWNED_OUTPUT")]
    (when-not (and (or (nil? mode) (#{"text" "owned"} mode))
                   (or (not= mode "owned") (= :json-compact-each-row format)))
      (throw (ex-info "Invalid scoped S3 output mode" {})))
    (binding [*insert-format* format *owned-output?* (= "owned" mode)]
      (case phase "writer" (writer! endpoint root) "reader" (reader! endpoint root)))))
