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

(defn- counter-request
  ([] (counter-request {}))
  ([overrides]
   (merge {:metric-kind :sum
           :temporality :cumulative
           :monotonic? true
           :metric-name "requests.total"
           :group-by [:service-name]
           :bucket :none
           :aggregates [:increase :rate]
           :start-unix-nano 1700000000000000000
           :end-unix-nano 1700000060000000000
           :limit 10}
          overrides)))

(defn- counter-row [overrides]
  (merge {:starttimenano 1700000000000000000
          :timenano 1700000010000000000
          :servicename "api"
          :streamservice "api" :streammetricunit "{request}"
          :streamscopename "demo.metrics" :streamscopeversion "1"
          :streamresourceschemaurl "" :streamscopeschemaurl ""
          :streamresourceattributes {} :streamscopeattributes {}
          :streamattributes {}
          :value 10.0 :aggregationtemporality 2 :ismonotonic true}
         overrides))

(defn- histogram-request
  ([] (histogram-request {}))
  ([overrides]
   (merge {:metric-kind :histogram
           :temporality :cumulative
           :metric-name "request.duration"
           :group-by [:service-name]
           :bucket :none
           :aggregates [:count :sum :avg :p50 :p95]
           :start-unix-nano 1700000000000000000
           :end-unix-nano 1700000060000000000
           :limit 10}
          overrides)))

(defn- histogram-row [overrides]
  (merge {:starttimenano 1700000000000000000
          :timenano 1700000010000000000
          :servicename "api"
          :streamservice "api" :streammetricdescription "request latency"
          :streammetricunit "ms" :streamscopename "demo.metrics"
          :streamscopeversion "1" :streamscopedroppedattrcount 0
          :streamresourceschemaurl ""
          :streamscopeschemaurl "" :streamresourceattributes {}
          :streamscopeattributes {} :streamattributes {}
          :count 4 :sum 20.0 :bucketcounts [1 2 1]
          :explicitbounds [0.0 10.0] :min 0.0 :max 20.0
          :aggregationtemporality 2 :flags 0
          :starttimetype "DateTime" :timetype "DateTime"
          :counttype "UInt64" :sumtype "Float64"
          :bucketcountstype "Array(UInt64)"
          :explicitboundstype "Array(Float64)" :mintype "Float64"
          :maxtype "Float64" :temporalitytype "Int32" :flagstype "UInt32"
          :scopedroppedattrcounttype "UInt32"}
         overrides))

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
  (check "explorer publishes explicit cumulative counter provenance"
         {:metric-kinds [:sum]
          :temporalities [:cumulative]
          :monotonic-values [true]
          :group-by [:service-name :metric-unit :scope-name
                     :deployment-environment]
          :buckets [:none :1m :5m :15m :1h]
          :aggregates [:increase :rate]}
         (explorer/supported-cumulative-counter-series))
  (check "explorer publishes explicit cumulative histogram provenance"
         {:metric-kinds [:histogram]
          :temporalities [:cumulative]
          :group-by [:service-name :metric-unit :scope-name
                     :deployment-environment]
          :buckets [:none :1m :5m :15m :1h]
          :aggregates [:count :sum :avg :p50 :p95 :p99]
          :quantiles {:p50 0.5 :p95 0.95 :p99 0.99}}
         (explorer/supported-cumulative-histogram-series))
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
  (let [calls (atom [])
        rows [(counter-row {})
              (counter-row {:timenano 1700000020000000000 :value 16.0})
              (counter-row {:starttimenano 1700000025000000000
                            :timenano 1700000030000000000 :value 4.0})
              (counter-row {:starttimenano 1700000025000000000
                            :timenano 1700000040000000000 :value 9.0})]]
    (with-redefs [jdbc/fetch
                  (fn [connection sqlvec opts]
                    (swap! calls conj [connection sqlvec opts
                                       (context/instrumentation-suppressed?)])
                    rows)]
      (let [result (explorer/cumulative-counter-series
                    :fake-connection (counter-request))
            [[_ [sql & params] opts suppressed?]] @calls]
        (check "counter series derives reset-aware increase and observed rate"
               [{:service-name "api" :increase 25.0
                 :rate (/ 25.0 35.0)
                 :metric-kind :sum :temporality :cumulative :monotonic? true
                 :interval-count 4 :reset-count 2
                 :observed-duration-nanos 35000000000}]
               result)
        (check "counter source query filters exact stored provenance"
               true
               (and (.contains sql "FROM otel_metrics_sum")
                    (.contains sql "AggregationTemporality = 2")
                    (.contains sql "IsMonotonic = true")
                    (.contains sql "max_result_bytes = 67108864")
                    (.contains sql "max_rows_to_read = 100000")
                    (.contains sql "max_bytes_to_read = 67108864")
                    (.contains sql "max_memory_usage = 134217728")
                    (.contains sql "max_execution_time = 5")
                    (.contains sql "max_threads = 1")
                    (not (.contains sql "requests.total"))))
        (check "counter source query binds data and enforces the source cap"
               [128 1700000000000000000 1700000060000000000
                "requests.total" 10001]
               params)
        (check "counter source fetch is bounded before host differencing"
               {:max-rows 10001} opts)
        (check "counter source query suppresses its own instrumentation"
               true suppressed?))))
  (let [bad-provenance [(dissoc (counter-request) :temporality)
                        (assoc (counter-request) :metric-kind :gauge)
                        (assoc (counter-request) :temporality :delta)
                        (assoc (counter-request) :monotonic? false)
                        (assoc (counter-request) :aggregates [:avg])]
        calls (atom 0)]
    (with-redefs [jdbc/fetch (fn [& _] (swap! calls inc) [])]
      (doseq [bad bad-provenance]
        (check "invalid counter provenance executes no SQL"
               true
               (boolean (:attribute-explorer/error
                         (thrown-data #(explorer/cumulative-counter-series
                                        :fake-connection bad))))))
      (check "all invalid counter provenance failed before query execution"
             0 @calls)))
  (doseq [[label rows expected-type]
          [["duplicate timestamp"
            [(counter-row {}) (counter-row {:value 11.0})]
            :otel.exporter.chdb.explorer/ambiguous-counter-order]
           ["unproven decrease"
            [(counter-row {})
             (counter-row {:timenano 1700000020000000000 :value 9.0})]
            :otel.exporter.chdb.explorer/unproven-counter-reset]
           ["collapsed streams"
            [(counter-row {})
             (counter-row {:timenano 1700000020000000000
                           :streamattributes {"route" "/private"}})]
            :otel.exporter.chdb.explorer/ambiguous-counter-projection]
           ["bucket crossing"
            [(counter-row {:starttimenano 1699999990000000000
                           :timenano 1700000010000000000})
             (counter-row {:starttimenano 1699999990000000000
                           :timenano 1700000041000000000 :value 20.0})]
            :otel.exporter.chdb.explorer/counter-interval-crosses-bucket]]]
    (with-redefs [jdbc/fetch (fn [& _] rows)]
      (let [request (cond-> (counter-request)
                      (= label "bucket crossing") (assoc :bucket :1m))
            data (thrown-data #(explorer/cumulative-counter-series
                                :fake-connection request))]
        (check (str "counter series rejects " label)
               expected-type
               (:type data))
        (check (str "counter failure evidence omits raw data " label)
               true
               (and (not-any? #(contains? data %)
                              [:row :projection :value :previous-value
                               :increase])
                    (not (.contains (pr-str data) "/private")))))))
  (let [calls (atom [])
        rows [(histogram-row {})
              (histogram-row {:timenano 1700000020000000000
                              :count 8 :sum 44.0
                              :bucketcounts [2 4 2]})
              (histogram-row {:starttimenano 1700000025000000000
                              :timenano 1700000030000000000
                              :count 3 :sum 21.0
                              :bucketcounts [0 2 1]
                              :min 2.0 :max 15.0})
              (histogram-row {:starttimenano 1700000025000000000
                              :timenano 1700000040000000000
                              :count 5 :sum 39.0
                              :bucketcounts [0 3 2]
                              :min 2.0 :max 18.0})]]
    (with-redefs [jdbc/fetch
                  (fn [connection sqlvec opts]
                    (swap! calls conj [connection sqlvec opts
                                       (context/instrumentation-suppressed?)])
                    rows)]
      (let [[result] (explorer/cumulative-histogram-series
                      :fake-connection (histogram-request))
            [[_ [sql & params] opts suppressed?]] @calls]
        (check "histogram series reconstructs reset-aware interval totals"
               {:service-name "api" :count 13 :sum 83.0
                :avg (/ 83.0 13.0) :metric-kind :histogram
                :temporality :cumulative :explicit-bounds [0.0 10.0]
                :interval-count 4 :reset-count 2
                :observed-duration-nanos 35000000000}
               (dissoc result :p50 :p95))
        (check "finite quantile exposes interpolation and bucket error"
               {:quantile 0.5 :rank 6.5 :estimate (/ 45.0 7.0)
                :lower-bound 0.0 :upper-bound 10.0
                :lower-inclusive? false :upper-inclusive? true
                :lower-unbounded? false :upper-unbounded? false
                :bucket-observation-count 7
                :absolute-error-bound (/ 45.0 7.0)
                :interpolation :uniform-within-explicit-bucket}
               (:p50 result))
        (check "implicit positive-infinity bucket has no invented estimate"
               {:quantile 0.95 :rank 12.35 :estimate nil
                :lower-bound 10.0 :upper-bound nil
                :lower-inclusive? false :upper-inclusive? false
                :lower-unbounded? false :upper-unbounded? true
                :bucket-observation-count 4 :absolute-error-bound nil
                :interpolation :uniform-within-explicit-bucket}
               (:p95 result))
        (check "histogram source query pins provenance, schema, and hard limits"
               true
               (and (.contains sql "FROM otel_metrics_histogram")
                    (.contains sql "AggregationTemporality = 2")
                    (.contains sql "toTypeName(BucketCounts)")
                    (.contains sql "max_result_bytes = 67108864")
                    (.contains sql "max_rows_to_read = 100000")
                    (.contains sql "max_bytes_to_read = 67108864")
                    (.contains sql "max_memory_usage = 134217728")
                    (.contains sql "max_execution_time = 5")
                    (.contains sql "max_threads = 1")
                    (not (.contains sql "request.duration"))))
        (check "histogram query binds data and caps source rows"
               [128 1700000000000000000 1700000060000000000
                "request.duration" 10001]
               params)
        (check "histogram fetch and instrumentation are bounded"
               [{:max-rows 10001} true] [opts suppressed?]))))
  (let [bad-provenance [(dissoc (histogram-request) :temporality)
                        (assoc (histogram-request) :metric-kind :sum)
                        (assoc (histogram-request) :temporality :delta)
                        (assoc (histogram-request) :aggregates [:rate])
                        (assoc (histogram-request) :sql "SELECT *")]
        calls (atom 0)]
    (with-redefs [jdbc/fetch (fn [& _] (swap! calls inc) [])]
      (doseq [bad bad-provenance]
        (check "invalid histogram provenance/recipe executes no SQL"
               true
               (boolean (:attribute-explorer/error
                         (thrown-data #(explorer/cumulative-histogram-series
                                        :fake-connection bad))))))
      (check "all invalid histogram requests fail before query execution"
             0 @calls)))
  (let [zero-row (histogram-row {:count 0 :sum 0.0
                                 :bucketcounts [0 0 0]})]
    (with-redefs [jdbc/fetch (fn [& _] [zero-row])]
      (check "empty histogram has explicit null average and quantiles"
             [{:count 0 :avg nil :p50 nil :metric-kind :histogram
               :temporality :cumulative :explicit-bounds [0.0 10.0]
               :interval-count 1 :reset-count 1
               :observed-duration-nanos 10000000000}]
             (explorer/cumulative-histogram-series
              :fake-connection
              (histogram-request {:group-by []
                                  :aggregates [:count :avg :p50]})))))
  (doseq [[label rows expected-type]
          [["boundary change"
            [(histogram-row {})
             (histogram-row {:timenano 1700000020000000000
                             :count 6 :sum 30.0 :bucketcounts [2 2 2]
                             :explicitbounds [0.0 20.0]})]
            :otel.exporter.chdb.explorer/histogram-boundary-change]
           ["unproven bucket decrease"
            [(histogram-row {})
             (histogram-row {:timenano 1700000020000000000
                             :count 5 :sum 21.0 :bucketcounts [0 3 2]})]
            :otel.exporter.chdb.explorer/unproven-histogram-reset]
           ["duplicate timestamp"
            [(histogram-row {}) (histogram-row {:sum 21.0})]
            :otel.exporter.chdb.explorer/ambiguous-histogram-order]
           ["collapsed streams"
            [(histogram-row {})
             (histogram-row {:timenano 1700000020000000000
                             :streamattributes {"route" "/secret"}})]
            :otel.exporter.chdb.explorer/ambiguous-histogram-projection]
           ["bucket crossing"
            [(histogram-row {:starttimenano 1699999990000000000})
             (histogram-row {:starttimenano 1699999990000000000
                             :timenano 1700000041000000000
                             :count 8 :sum 44.0 :bucketcounts [2 4 2]})]
            :otel.exporter.chdb.explorer/histogram-interval-crosses-bucket]
           ["schema drift"
            [(histogram-row {:bucketcountstype "Array(Int64)"})]
            :otel.exporter.chdb.explorer/unsupported-histogram-schema]
           ["non-finite data"
            [(histogram-row {:sum ##NaN})]
            :otel.exporter.chdb.explorer/invalid-histogram-row]]]
    (with-redefs [jdbc/fetch (fn [& _] rows)]
      (let [request (cond-> (histogram-request)
                      (= label "bucket crossing") (assoc :bucket :1m))
            data (thrown-data #(explorer/cumulative-histogram-series
                                :fake-connection request))]
        (check (str "histogram series rejects " label)
               expected-type (:type data))
        (check (str "histogram failure evidence omits raw telemetry " label)
               true
               (and (not-any? #(contains? data %)
                              [:row :projection :value :sum :bucket-counts
                               :explicit-bounds :previous-value])
                    (not (.contains (pr-str data) "/secret")))))))
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
                :start-unix-nano start :end-unix-nano end})))))
  (with-open [conn (jdbc/connection "chdb::memory:")]
    (schema/migrate! conn)
    (let [start-second 1700000000
          start (* start-second 1000000000)
          end (+ start (* 60 1000000000))]
      (doseq [[epoch-second time-second value]
              [[start-second (+ start-second 10) 10.0]
               [start-second (+ start-second 20) 16.0]
               [(+ start-second 25) (+ start-second 30) 4.0]
               [(+ start-second 25) (+ start-second 40) 9.0]]]
        (jdbc/execute!
         conn
         ["INSERT INTO otel_metrics_sum
             (TimeUnix, StartTimeUnix, ServiceName, MetricName, MetricUnit,
              ScopeName, Value, AggregationTemporality, IsMonotonic)
           VALUES (fromUnixTimestamp(?), fromUnixTimestamp(?), 'api',
                   'requests.total', '{request}', 'demo.metrics', ?, 2, true)"
          time-second epoch-second value]))
      (doseq [[temporality monotonic value]
              [[1 true 1000.0] [2 false 2000.0]]]
        (jdbc/execute!
         conn
         ["INSERT INTO otel_metrics_sum
             (TimeUnix, StartTimeUnix, ServiceName, MetricName, MetricUnit,
              ScopeName, Value, AggregationTemporality, IsMonotonic)
           VALUES (fromUnixTimestamp(?), fromUnixTimestamp(?), 'ignored',
                   'requests.total', '{request}', 'demo.metrics', ?, ?, ?)"
          (+ start-second 10) start-second value temporality monotonic]))
      (let [rows (explorer/cumulative-counter-series
                  conn (counter-request {:start-unix-nano start
                                         :end-unix-nano end}))]
        (check "native chDB cumulative counter differencing is reset-aware"
               [{:service-name "api" :increase 25.0
                 :rate (/ 25.0 35.0)
                 :metric-kind :sum :temporality :cumulative :monotonic? true
                 :interval-count 4 :reset-count 2
                 :observed-duration-nanos 35000000000}]
               rows))))
  (with-open [conn (jdbc/connection "chdb::memory:")]
    (schema/migrate! conn)
    (let [start-second 1700000000
          start (* start-second 1000000000)
          end (+ start (* 60 1000000000))]
      (doseq [[epoch-second time-second count sum bucket-counts minimum maximum]
              [[start-second (+ start-second 10) 4 -20.0 [1 2 1] -100.0 100.0]
               [start-second (+ start-second 20) 8 -44.0 [2 4 2] -100.0 100.0]
               [(+ start-second 25) (+ start-second 30)
                3 -21.0 [0 2 1] -100.0 100.0]
               [(+ start-second 25) (+ start-second 40)
                5 -39.0 [0 3 2] -100.0 100.0]]]
        (jdbc/execute!
         conn
         ["INSERT INTO otel_metrics_histogram
             (TimeUnix, StartTimeUnix, ServiceName, MetricName,
              MetricDescription, MetricUnit, ScopeName, Count, Sum,
              BucketCounts, ExplicitBounds, Min, Max,
              AggregationTemporality, Flags)
           VALUES (fromUnixTimestamp(?), fromUnixTimestamp(?), 'api',
                   'request.duration', 'request latency', 'ms',
                   'demo.metrics', ?, ?, [?, ?, ?], [0.0, 10.0], ?, ?, 2, 0)"
          time-second epoch-second count sum
          (nth bucket-counts 0) (nth bucket-counts 1) (nth bucket-counts 2)
          minimum maximum]))
      (let [[row] (explorer/cumulative-histogram-series
                   conn (histogram-request {:start-unix-nano start
                                            :end-unix-nano end}))]
        (check "native chDB cumulative histogram reconstruction is reset-aware"
               {:service-name "api" :count 13 :sum -83.0
                :avg (/ -83.0 13.0) :metric-kind :histogram
                :temporality :cumulative :explicit-bounds [0.0 10.0]
                :interval-count 4 :reset-count 2
                :observed-duration-nanos 35000000000}
               (dissoc row :p50 :p95))
        (check "native chDB finite histogram quantile remains explicitly bounded"
               [0.0 10.0 (/ 45.0 7.0) (/ 45.0 7.0)]
               ((juxt :lower-bound :upper-bound :estimate :absolute-error-bound)
                (:p50 row)))
        (check "native chDB infinite-tail quantile has no fabricated estimate"
               [10.0 nil nil true]
               ((juxt :lower-bound :upper-bound :estimate :upper-unbounded?)
                (:p95 row)))))))
