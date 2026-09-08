(ns otel.exporter.chdb-explorer-test
  (:require [db.jdbc]
            [jdbc.chdb]
            [jdbc.core :as jdbc]
            [otel.context :as context]
            [otel.exporter.chdb.explorer :as explorer]
            [otel.exporter.chdb.schema :as schema]))

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(defn- request
  ([] (request {}))
  ([overrides]
   (merge {:signal :spans
           :fields [:service-name]
           :start-unix-nano 1700000000000000000
           :end-unix-nano 1700000001000000000
           :limit 10}
          overrides)))

(defn- series-request
  ([] (series-request {}))
  ([overrides]
   (merge {:metric-kind :gauge
           :metric-name "queue.depth"
           :group-by [:service-name]
           :bucket :1m
           :aggregates [:count :avg :p95]
           :start-unix-nano 1700000000000000000
           :end-unix-nano 1700000300000000000
           :limit 10}
          overrides)))

(defn run [check]
  (check "explorer publishes a closed span field allowlist"
         [:service-name :span-name :span-kind :status-code :scope-name
          :http-request-method :http-response-status-code
          :deployment-environment]
         (explorer/supported-fields :spans))
  (check "explorer publishes closed metric series choices"
         {:metric-kinds [:gauge :sum :histogram]
          :group-by [:service-name :metric-unit :scope-name
                     :deployment-environment]
          :buckets [:none :1m :5m :15m :1h]
          :aggregates
          {:gauge [:count :sum :min :max :avg :p50 :p95 :p99]
           :sum [:count :sum :min :max :avg :p50 :p95 :p99]
           :histogram [:count :sum :avg]}}
         (explorer/supported-metric-series))
  (let [calls (atom [])]
    (with-redefs [jdbc/fetch
                  (fn [conn sqlvec opts]
                    (swap! calls conj
                           [conn sqlvec opts
                            (context/instrumentation-suppressed?)])
                    [{:value "api" :count 3}])]
      (check "explorer returns host-neutral rows tagged with signal and field"
             [{:value "api" :count 3 :signal :spans :field :service-name}
              {:value "api" :count 3 :signal :spans :field :http-request-method}]
             (explorer/top-values
              :fake-connection
              (request {:fields [:service-name :http-request-method]
                        :limit 7 :max-text-length 42})))
      (let [[_ [service-sql & service-params] service-opts service-suppressed?]
            (first @calls)
            [_ [http-sql & http-params] http-opts http-suppressed?]
            (second @calls)]
        (check "explorer suppresses its own database instrumentation"
               [true true] [service-suppressed? http-suppressed?])
        (check "explorer binds every caller-controlled scalar"
               [42 1700000000000000000 1700000001000000000 7]
               service-params)
        (check "explorer asks the driver for no more than the requested rows"
               [{:max-rows 7} {:max-rows 7}]
               [service-opts http-opts])
        (check "span SQL uses only the fixed table and service expression"
               true
               (and (.contains service-sql "FROM otel_traces")
                    (.contains service-sql "toString(ServiceName)")
                    (not (.contains service-sql "1700000000000000000"))))
        (check "semantic attribute SQL uses its fixed map key"
               true
               (.contains http-sql
                          "SpanAttributes['http.request.method']"))
      (check "each field gets identical bounded parameters"
               service-params http-params))))
  (let [calls (atom [])]
    (with-redefs [jdbc/fetch
                  (fn [conn sqlvec opts]
                    (swap! calls conj
                           [conn sqlvec opts
                            (context/instrumentation-suppressed?)])
                    [{:bucketstart 1699999980000000000
                      :servicename "api" :count 3 :avg 4.5 :p95 8.0}])]
      (check "metric series normalizes bounded server aliases"
             [{:bucket-start-unix-nano 1699999980000000000
               :service-name "api" :count 3 :avg 4.5 :p95 8.0}]
             (explorer/metric-series :fake-connection (series-request)))
      (let [[_ [sql & params] opts suppressed?] (first @calls)
            minute (* 60 1000000000)]
        (check "metric series suppresses its own database instrumentation"
               true suppressed?)
        (check "metric series binds every caller-controlled scalar"
               [minute minute 128 1700000000000000000
                1700000300000000000 "queue.depth" 10]
               params)
        (check "metric series asks the driver for no more than its result cap"
               {:max-rows 10} opts)
        (check "metric series SQL uses only closed gauge expressions"
               true
               (and (.contains sql "FROM otel_metrics_gauge")
                    (.contains sql "intDiv(")
                    (.contains sql "leftUTF8(toString(ServiceName), ?)")
                    (.contains sql "count() AS count")
                    (.contains sql "avg(Value) AS avg")
                    (.contains sql "quantileTDigest(0.95)(Value) AS p95")
                    (not (.contains sql "queue.depth")))))))
  (let [calls (atom 0)
        invalid [(assoc (series-request) :metric-kind :summary)
                 (assoc (series-request) :metric-name "")
                 (assoc (series-request) :group-by '(:service-name))
                 (assoc (series-request) :group-by [:service-name :service-name])
                 (assoc (series-request) :group-by [:attribute-value])
                 (assoc (series-request) :bucket :30s)
                 (assoc (series-request) :aggregates [])
                 (assoc (series-request) :aggregates [:avg :avg])
                 (assoc (series-request) :aggregates [:rate])
                 (assoc (series-request {:metric-kind :histogram})
                        :aggregates [:p95])
                 (assoc (series-request) :limit 101)
                 (assoc (series-request) :end-unix-nano 1700000000000000000)
                 (assoc (series-request) :sql "SELECT *")]]
    (with-redefs [jdbc/fetch (fn [& _] (swap! calls inc) [])]
      (doseq [bad invalid]
        (check (str "invalid metric series request executes no SQL "
                    (pr-str bad))
               true
               (boolean (:attribute-explorer/error
                         (thrown-data #(explorer/metric-series
                                        :fake-connection bad))))))
      (check "invalid metric series requests execute no SQL" 0 @calls)))
  (let [calls (atom [])]
    (with-redefs [jdbc/fetch
                  (fn [_ sqlvec _]
                    (swap! calls conj sqlvec)
                    [])]
      (explorer/top-values
       :fake-connection
       {:signal :metrics :fields [:metric-name]
        :start-unix-nano 1 :end-unix-nano 2 :limit 1})
      (let [sql (ffirst @calls)]
        (check "metrics explorer uses the three fixed metric tables"
               true
               (and (.contains sql "otel_metrics_gauge")
                    (.contains sql "otel_metrics_sum")
                    (.contains sql "otel_metrics_histogram")
                    (.contains sql "toString(MetricName)"))))))
  (let [calls (atom 0)
        invalid [{:label "unknown signal" :request (request {:signal :traces})
                  :type :otel.exporter.chdb.explorer/unsupported-signal}
                 {:label "caller-supplied identifier"
                  :request (request {:fields ["ServiceName; DROP TABLE otel_traces"]})
                  :type :otel.exporter.chdb.explorer/unsupported-field}
                 {:label "non-vector field sequence"
                  :request (request {:fields '(:service-name)})
                  :type :otel.exporter.chdb.explorer/invalid-fields}
                 {:label "empty fields" :request (request {:fields []})
                  :type :otel.exporter.chdb.explorer/invalid-fields}
                 {:label "too many fields"
                  :request (request {:fields [:a :b :c :d :e :f :g :h :i]})
                  :type :otel.exporter.chdb.explorer/invalid-fields}
                 {:label "duplicate fields"
                  :request (request {:fields [:service-name :service-name]})
                  :type :otel.exporter.chdb.explorer/duplicate-fields}
                 {:label "missing start" :request (dissoc (request) :start-unix-nano)
                  :type :otel.exporter.chdb.explorer/invalid-time}
                 {:label "empty time range"
                  :request (request {:end-unix-nano 1700000000000000000})
                  :type :otel.exporter.chdb.explorer/invalid-time-window}
                 {:label "oversized time range"
                  :request (request {:start-unix-nano 0
                                     :end-unix-nano (inc explorer/max-time-range-nanos)})
                  :type :otel.exporter.chdb.explorer/time-range-too-large}
                 {:label "zero limit" :request (request {:limit 0})
                  :type :otel.exporter.chdb.explorer/invalid-bound}
                 {:label "oversized limit"
                  :request (request {:limit (inc explorer/max-result-limit)})
                  :type :otel.exporter.chdb.explorer/invalid-bound}
                 {:label "oversized text"
                  :request (request {:max-text-length
                                     (inc explorer/max-text-length)})
                  :type :otel.exporter.chdb.explorer/invalid-bound}]]
    (with-redefs [jdbc/fetch (fn [& _] (swap! calls inc) [])]
      (doseq [{:keys [label request type]} invalid]
        (let [data (thrown-data #(explorer/top-values :fake-connection request))]
          (check (str "explorer rejects " label) type (:type data))
          (check (str "explorer marks " label " as its own error")
                 true (:attribute-explorer/error data))))
      (check "invalid explorer requests execute no SQL" 0 @calls)))
  (with-open [conn (jdbc/connection "chdb::memory:")]
    (schema/migrate! conn)
    (let [start 1700000000000000000]
      (doseq [aggregates [[:count] [:avg] [:p95]]]
        (check (str "native empty ungrouped metric series returns no row "
                    (pr-str aggregates))
               []
               (explorer/metric-series
                conn {:metric-kind :gauge :metric-name "missing.metric"
                      :group-by [] :bucket :none :aggregates aggregates
                      :start-unix-nano start
                      :end-unix-nano (+ start 1000000000)
                      :limit 10})))
      (doseq [[offset service method]
              [[1 "api" "GET"] [2 "api" "POST"] [3 "worker" "GET"]]]
        (jdbc/execute!
         conn
         [(str "INSERT INTO otel_traces "
               "(Timestamp, ServiceName, SpanName, SpanAttributes) "
               "VALUES (fromUnixTimestamp64Nano(?), ?, 'work', "
               "map('http.request.method', ?))")
          (+ start offset) service method]))
      (doseq [timestamp [(- start 1) (+ start 10)]]
        (jdbc/execute!
         conn
         ["INSERT INTO otel_traces (Timestamp, ServiceName, SpanName)
             VALUES (fromUnixTimestamp64Nano(?), 'outside', 'outside')"
          timestamp]))
      (check "native explorer groups and orders bounded distributions"
             [{:value "api" :count 2 :signal :spans :field :service-name}
              {:value "worker" :count 1 :signal :spans :field :service-name}
              {:value "GET" :count 2 :signal :spans :field :http-request-method}
              {:value "POST" :count 1 :signal :spans :field :http-request-method}]
             (explorer/top-values
              conn {:signal :spans
                    :fields [:service-name :http-request-method]
                    :start-unix-nano start :end-unix-nano (+ start 10)
                    :limit 10}))
      (check "native explorer enforces the per-field limit"
             [{:value "api" :count 2 :signal :spans :field :service-name}]
             (explorer/top-values
              conn {:signal :spans :fields [:service-name]
                    :start-unix-nano start :end-unix-nano (+ start 10)
                    :limit 1}))
      (check "native metrics union is valid on the migrated empty tables"
             []
             (explorer/top-values
              conn {:signal :metrics :fields [:metric-name]
                    :start-unix-nano start :end-unix-nano (+ start 10)
                    :limit 1}))))
  (with-open [conn (jdbc/connection "chdb::memory:")]
    (schema/migrate! conn)
    (let [start 1700000000000000000
          end (+ start (* 5 60 1000000000))]
      (doseq [[service value]
              [["api" 2.0] ["api" 4.0] ["worker" 10.0]]]
        (jdbc/execute!
         conn
         ["INSERT INTO otel_metrics_gauge
             (TimeUnix, ServiceName, MetricName, MetricUnit, ScopeName, Value)
           VALUES (fromUnixTimestamp(?), ?, 'queue.depth', '{job}',
                   'demo.metrics', ?)"
          1700000001 service value]))
      (let [rows (explorer/metric-series
                  conn
                  (series-request
                   {:start-unix-nano start :end-unix-nano end
                    :aggregates [:count :sum :min :max :avg :p50 :p95 :p99]}))
            api (first rows)
            worker (second rows)]
        (check "native scalar metric series groups and aggregates"
               [{:service-name "api" :count 2 :sum 6.0 :min 2.0
                 :max 4.0 :avg 3.0}
                {:service-name "worker" :count 1 :sum 10.0 :min 10.0
                 :max 10.0 :avg 10.0}]
               (mapv #(select-keys % [:service-name :count :sum :min :max :avg])
                     rows))
        (check "native scalar percentiles remain inside observed bounds"
               true
               (and (every? #(<= 2.0 (double (get api %)) 4.0)
                            [:p50 :p95 :p99])
                    (every? #(= 10.0 (double (get worker %)))
                            [:p50 :p95 :p99]))))
      (doseq [[count sum]
              [[2 8.0] [3 15.0]]]
        (jdbc/execute!
         conn
         ["INSERT INTO otel_metrics_histogram
             (TimeUnix, ServiceName, MetricName, MetricUnit, ScopeName,
              Count, Sum, BucketCounts, ExplicitBounds, Min, Max,
              AggregationTemporality)
           VALUES (fromUnixTimestamp(?), 'api', 'request.duration', 'ms',
                   'demo.metrics', ?, ?, [], [], 0, 0, 1)"
          1700000001 count sum]))
      (doseq [[count sum]
              [[2 8.0] [3 15.0]]]
        (jdbc/execute!
         conn
         ["INSERT INTO otel_metrics_histogram
             (TimeUnix, ServiceName, MetricName, MetricUnit, ScopeName,
              Count, Sum, BucketCounts, ExplicitBounds, Min, Max,
              AggregationTemporality)
           VALUES (fromUnixTimestamp(?), 'api', 'cumulative.duration', 'ms',
                   'demo.metrics', ?, ?, [], [], 0, 0, 2)"
          1700000001 count sum]))
      (check "native histogram series aggregates delta count and sum"
             [{:count 5 :sum 23.0 :avg 4.6}]
             (explorer/metric-series
              conn
              (series-request
               {:metric-kind :histogram :metric-name "request.duration"
                :group-by [] :bucket :none :aggregates [:count :sum :avg]
                :start-unix-nano start :end-unix-nano end})))
      (check "native histogram series excludes cumulative snapshots"
             []
             (explorer/metric-series
              conn
              (series-request
               {:metric-kind :histogram :metric-name "cumulative.duration"
                :group-by [] :bucket :none :aggregates [:count :sum :avg]
                :start-unix-nano start :end-unix-nano end}))))))
