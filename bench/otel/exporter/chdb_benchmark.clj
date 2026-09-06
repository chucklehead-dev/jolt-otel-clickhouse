(ns otel.exporter.chdb-benchmark
  "Synthetic ClickStack-compatible exporter and query baseline for Jolt/chDB."
  (:require [db.jdbc]
            [jdbc.chdb]
            [jdbc.core :as jdbc]
            [jolt.host :as host]
            [otel.exporter.chdb :as chdb]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(def ^:private base-time-nanos 1700000000000000000)

(defn- padded-hex [width n]
  (let [value (format "%x" n)]
    (str (apply str (repeat (- width (count value)) "0")) value)))

(defn- resource [service]
  {:attributes {:service.name service
                :deployment.environment.name "benchmark"}
   :schema-url "https://opentelemetry.io/schemas/1.27.0"})

(defn- span [service index]
  (let [start (+ base-time-nanos (* index 1000000))]
    {:name (str "GET /synthetic/" (mod index 8))
     :kind (if (zero? (mod index 3)) :server :internal)
     :start-time-unix-nano start
     :end-time-unix-nano (+ start (* 1000000 (inc (mod index 100))))
     :span-context {:trace-id (padded-hex 32 (inc index))
                    :span-id (padded-hex 16 (+ 1000000 index))
                    :sampled? true}
     :parent-span-id ""
     :resource (resource service)
     :scope {:name "oscope.benchmark" :version "1.0"}
     :attributes {:http.request.method "GET"
                  :http.response.status_code (if (zero? (mod index 20)) 500 200)
                  :benchmark.bucket (mod index 16)}
     :events []
     :links []
     :status {:code (if (zero? (mod index 20)) :error :ok)}}))

(defn- log-record [service index]
  {:timestamp-unix-nano (+ base-time-nanos (* index 1000000))
   :observed-time-unix-nano (+ base-time-nanos (* index 1000000) 1000)
   :trace-id (padded-hex 32 (inc index))
   :span-id (padded-hex 16 (+ 1000000 index))
   :trace-flags 1
   :severity-text (if (zero? (mod index 20)) "ERROR" "INFO")
   :severity-number (if (zero? (mod index 20)) 17 9)
   :body (str "synthetic request " index)
   :event-name "benchmark.request"
   :resource (resource service)
   :scope {:name "oscope.benchmark" :version "1.0"}
   :attributes {:benchmark.bucket (mod index 16)}})

(defn- scalar-point [index]
  {:start-time-unix-nano base-time-nanos
   :time-unix-nano (+ base-time-nanos (* index 1000000))
   :attributes {:route (str "/synthetic/" (mod index 8))}
   :value (double (inc (mod index 100)))})

(defn- histogram-point [index]
  (let [value (double (inc (mod index 100)))]
    {:start-time-unix-nano base-time-nanos
     :time-unix-nano (+ base-time-nanos (* index 1000000))
     :attributes {:route (str "/synthetic/" (mod index 8))}
     :count 1
     :sum value
     :bucket-counts [0 1 0]
     :min value
     :max value}))

(defn- metrics [start items]
  (let [indexes (range start (+ start items))]
    [{:scope {:name "oscope.benchmark" :version "1.0"}
      :metrics [{:name "synthetic.queue.depth"
                 :description "synthetic queue depth"
                 :unit "{item}"
                 :type :gauge
                 :data-points (mapv scalar-point indexes)}
                {:name "synthetic.requests"
                 :description "synthetic accepted requests"
                 :unit "{request}"
                 :type :sum
                 :temporality :cumulative
                 :monotonic? true
                 :data-points (mapv scalar-point indexes)}
                {:name "synthetic.duration"
                 :description "synthetic request duration"
                 :unit "ms"
                 :type :histogram
                 :temporality :delta
                 :explicit-bounds [10.0 100.0]
                 :data-points (mapv histogram-point indexes)}]}]))

(defn- snapshot []
  ;; These are independent host snapshots. Retain raw values so collector and
  ;; scheduler effects stay visible instead of disappearing into one ratio.
  {:nano-time (System/nanoTime)
   :cpu-nanos (host/cpu-nanos)
   :real-nanos (host/real-nanos)
   :gc-count (host/gc-count)
   :gc-cpu-nanos (host/gc-cpu-nanos)
   :gc-real-nanos (host/gc-real-nanos)
   :gc-bytes (host/gc-bytes)
   :live-heap-bytes (host/bytes-allocated)
   :current-memory-bytes (host/current-memory-bytes)
   :maximum-memory-bytes (host/maximum-memory-bytes)})

(defn- counter-delta [before after]
  (let [counter-keys [:nano-time :cpu-nanos :real-nanos :gc-count
                      :gc-cpu-nanos :gc-real-nanos :gc-bytes
                      :live-heap-bytes :current-memory-bytes
                      :maximum-memory-bytes]
        deltas (into {}
                     (map (fn [key]
                            [key (- (get after key) (get before key))]))
                     counter-keys)]
    (assoc deltas :allocation-proxy-bytes
           (- (+ (:live-heap-bytes after) (:gc-bytes after))
              (+ (:live-heap-bytes before) (:gc-bytes before))))))

(defn- timed-nanos [f]
  (let [start (System/nanoTime)
        value (f)]
    [value (- (System/nanoTime) start)]))

(defn- percentile [ordered fraction]
  (let [rank (max 0 (dec (long (Math/ceil (* fraction (count ordered))))))]
    (nth ordered rank)))

(defn- milliseconds [nanos]
  (/ (double nanos) 1000000.0))

(defn- latency-summary [samples]
  (let [ordered (vec (sort samples))]
    {:count (count ordered)
     :total-ms (milliseconds (reduce + 0 ordered))
     :p50-ms (milliseconds (percentile ordered 0.50))
     :p95-ms (milliseconds (percentile ordered 0.95))
     :p99-ms (milliseconds (percentile ordered 0.99))
     :max-ms (milliseconds (peek ordered))}))

(defn- require-success! [signal ok]
  (when-not ok
    (throw (ex-info "ClickStack benchmark export failed" {:signal signal}))))

(defn- export-batch! [exporter service start items samples]
  (let [indexes (range start (+ start items))
        [span-ok span-ns]
        (timed-nanos #(export/export-spans!
                       exporter (mapv (partial span service) indexes)))
        [log-ok log-ns]
        (timed-nanos #(logs/export-logs!
                       exporter (mapv (partial log-record service) indexes)))
        [metric-ok metric-ns]
        (timed-nanos #(export/export-metrics!
                       exporter (resource service) (metrics start items)))]
    (require-success! :spans span-ok)
    (require-success! :logs log-ok)
    (require-success! :metrics metric-ok)
    (-> samples
        (update :spans conj span-ns)
        (update :logs conj log-ns)
        (update :metrics conj metric-ns))))

(defn- count-table [connection table service]
  (:n (jdbc/fetch-one connection
                      [(str "select count() n from " table
                            " where ServiceName=?") service])))

(defn- stored-counts [connection service]
  {:spans (count-table connection "otel_traces" service)
   :logs (count-table connection "otel_logs" service)
   :gauge (count-table connection "otel_metrics_gauge" service)
   :sum (count-table connection "otel_metrics_sum" service)
   :histogram (count-table connection "otel_metrics_histogram" service)})

(defn- query-samples [connection service iterations expected-rows]
  (let [query #(jdbc/fetch
                connection
                ["select SpanName, count() n,
                         quantile(0.95)(Duration / 1000000.0) p95_ms
                    from otel_traces where ServiceName=?
                   group by SpanName order by n desc limit 8"
                 service])]
    (loop [remaining iterations samples []]
      (if (zero? remaining)
        samples
        (let [[rows elapsed] (timed-nanos query)]
          (when-not (= expected-rows (count rows))
            (throw (ex-info "ClickStack benchmark query returned wrong shape"
                            {:expected-rows expected-rows
                             :actual-rows (count rows)})))
          (recur (dec remaining) (conj samples elapsed)))))))

(defn run!
  [{:keys [db-spec batches items query-iterations]
    :or {db-spec "chdb::memory:" batches 20 items 50 query-iterations 20}}]
  (when-not (and (pos-int? batches) (pos-int? items)
                 (pos-int? query-iterations))
    (throw (ex-info "Benchmark counts must be positive integers" {})))
  (let [service (str "oscope-backend-bench-" (random-uuid))
        warmup-service (str service "-warmup")
        [connection open-ns] (timed-nanos #(jdbc/connection db-spec))]
    (try
      (let [[exporter schema-ns]
            (timed-nanos #(chdb/exporter
                           {:connection connection
                            :signals #{:spans :logs :metrics}}))]
        (export-batch! exporter warmup-service 0 (min items 10)
                       {:spans [] :logs [] :metrics []})
        (System/gc)
        (let [before (snapshot)
              start (System/nanoTime)
              samples
              (loop [batch 0 samples {:spans [] :logs [] :metrics []}]
                (if (= batch batches)
                  samples
                  (recur (inc batch)
                         (export-batch! exporter service (* batch items) items
                                        samples))))
              elapsed (- (System/nanoTime) start)
              after (snapshot)
              expected (* batches items)
              expected-counts {:spans expected :logs expected :gauge expected
                               :sum expected :histogram expected}
              counts (stored-counts connection service)
              query-before (snapshot)
              query-latencies (query-samples connection service query-iterations
                                              (min 8 expected))
              query-after (snapshot)
              stored-items (* 5 expected)]
          (when-not (= expected-counts counts)
            (throw (ex-info "ClickStack benchmark count reconciliation failed"
                            {:expected expected-counts :actual counts})))
          {:schema-version 1
           :runtime {:name :jolt
                     :scheme-version (host/scheme-version)
                     :machine-type (host/machine-type)
                     :os-name (System/getProperty "os.name")
                     :os-arch (System/getProperty "os.arch")}
           :configuration {:db-spec db-spec
                           :batches batches
                           :items-per-batch items
                           :query-iterations query-iterations
                           :warmup-items (min items 10)}
           :workload {:service-name service
                      :stored-items stored-items
                      :expected-counts expected-counts
                      :actual-counts counts}
           :setup {:connection-open-ms (milliseconds open-ns)
                   :schema-ms (milliseconds schema-ns)}
           :ingest {:wall-ms (milliseconds elapsed)
                    :stored-items-per-second
                    (/ (double (* stored-items 1000000000)) elapsed)
                    :batch-latency
                    {:spans (latency-summary (:spans samples))
                     :logs (latency-summary (:logs samples))
                     :metrics (latency-summary (:metrics samples))}
                    :counters-before before
                    :counters-after after
                    :counter-delta (counter-delta before after)}
           :query {:dashboard (latency-summary query-latencies)
                   :counters-before query-before
                   :counters-after query-after
                   :counter-delta (counter-delta query-before query-after)}}))
      (finally
        (.close connection)))))

(defn -main [& [db-spec batches-text items-text query-iterations-text output]]
  (let [report (run! {:db-spec (or db-spec "chdb::memory:")
                      :batches (if batches-text (parse-long batches-text) 20)
                      :items (if items-text (parse-long items-text) 50)
                      :query-iterations (if query-iterations-text
                                          (parse-long query-iterations-text) 20)})
        encoded (pr-str report)]
    (when output (spit output (str encoded "\n")))
    (println encoded)))
