(ns otel.exporter.chdb-explorer-test
  (:require [db.jdbc]
            [jdbc.chdb]
            [jdbc.core :as jdbc]
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

(defn run [check]
  (check "explorer publishes a closed span field allowlist"
         [:service-name :span-name :span-kind :status-code :scope-name
          :http-request-method :http-response-status-code
          :deployment-environment]
         (explorer/supported-fields :spans))
  (let [calls (atom [])]
    (with-redefs [jdbc/fetch
                  (fn [conn sqlvec opts]
                    (swap! calls conj [conn sqlvec opts])
                    [{:value "api" :count 3}])]
      (check "explorer returns host-neutral rows tagged with signal and field"
             [{:value "api" :count 3 :signal :spans :field :service-name}
              {:value "api" :count 3 :signal :spans :field :http-request-method}]
             (explorer/top-values
              :fake-connection
              (request {:fields [:service-name :http-request-method]
                        :limit 7 :max-text-length 42})))
      (let [[_ [service-sql & service-params] service-opts] (first @calls)
            [_ [http-sql & http-params] http-opts] (second @calls)]
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
                    :limit 1})))))
