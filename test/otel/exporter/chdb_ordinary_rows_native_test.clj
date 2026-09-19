(ns otel.exporter.chdb-ordinary-rows-native-test
  "Explicit tiny native qualification; not auto-discovered by the runner."
  (:require [db.jdbc] [jdbc.core :as jdbc]
            [jdbc.chdb.durable.backend :as backend]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.sdk.export :as export] [otel.sdk.logs :as logs]))

(def ^:private uint64-max 18446744073709551615N)
(def ^:private int64-min -9223372036854775808)
(def ^:private int64-max 9223372036854775807)

(defn- safe-error-summary [error]
  ;; SQLExceptions preserve the driver's ex-info as a cause. Inspect at most
  ;; eight causes privately; never retain/print their messages or raw ex-data.
  (let [causes (vec (take 8 (take-while some? (iterate ex-cause error))))
        categories #{:otel.exporter.chdb/invalid-ordinary-row
                     :otel.exporter.chdb/invalid-timestamp-nanos
                     :jdbc.chdb/invalid-json-rows
                     :otel.exporter.chdb/durable-writer-required}
        category (some #(let [candidate (:type (ex-data %))]
                          (when (contains? categories candidate) candidate)) causes)
        jdbc-error? (boolean (some #(:jdbc/sql-error (ex-data %)) causes))
        ;; jdbc.chdb/with-owned-result supplies this exact owned prefix and
        ;; :jdbc/sql-error flag. Never search arbitrary payload-bearing text.
        code (some #(when (true? (:jdbc/sql-error (ex-data %)))
                      (when-let [match (re-find #"^chDB query failed: Code: ([0-9]{1,6})\."
                                               (or (ex-message %) ""))]
                        (parse-long (second match)))) causes)]
    {:error-class (case (when error (.getName (class error)))
                    "java.sql.SQLException" :sql-exception
                    "clojure.lang.ExceptionInfo" :exception-info
                    "java.lang.IllegalArgumentException" :illegal-argument
                    :other)
     :category (or category (when jdbc-error? :jdbc-sql-error) :unclassified)
     :jdbc-sql-error jdbc-error?
     :engine-error-code code}))

(defn- diagnosed-result [writer result]
  (when-not result
    (println :exporter-safe-error (safe-error-summary (exporter/last-error writer))))
  result)

(defn- install! [connection signal]
  (let [table (case signal :spans "otel_traces" :logs "otel_logs"
                         :metrics "otel_metrics_gauge")
        location (case signal :spans :span-attributes :logs :log-attributes
                            :metrics :metric-attributes)
        approved (manifest/compile-manifest
                  {:dataset-id "ordinary-native" :application-id (name signal)
                   :lineage "native-v1" :version 1
                   :fragments [{:schema manifest/reviewed-fragment-schema
                                :authority :advice :source "advice/native-test.edn"
                                :entries (mapv (fn [[key type]]
                                                 {:signal signal :table table :location location
                                                  :key key :type type})
                                               [["flag" :boolean] ["count" :int64]])}]})
        installation (installer/install-approved!
                      (backend/memory-backend) approved
                      {:target connection
                       :observe-columns
                       #(vector {:signal signal :table table
                                 :columns (into {} (map (juxt :name :type))
                                                (jdbc/fetch connection (str "DESCRIBE TABLE " table)))})
                       :execute-ddl! #(jdbc/execute! connection %)})
        capability (:descriptor-set installation)]
    {:status (:status installation) :capability capability
     :fields ((case signal :spans projection/confirmed-span-fields
                           :logs projection/confirmed-log-fields
                           :metrics projection/confirmed-gauge-fields)
              capability connection)}))

(defn- span [name duration attributes]
  {:name name :kind :internal :start-time-unix-nano 0
   :end-time-unix-nano duration
   :span-context {:trace-id "11111111111111111111111111111111" :span-id "2222222222222222"}
   :resource {:attributes {"service.name" "ordinary-native"}}
   :scope {:name "native"} :attributes attributes :events [] :links [] :status {:code :unset}})

(defn- log-record [name attributes]
  {:timestamp-unix-nano 1700000000000000000 :observed-time-unix-nano 1700000000000000001
   :body name :severity-text "INFO" :severity-number 9
   :resource {:attributes {}} :scope {:name "native"} :attributes attributes})

(defn- metric [type name point]
  {:scope {} :metrics [(merge {:type type :name name :description "" :unit ""
                               :temporality :cumulative :monotonic? false
                               :explicit-bounds [1.0] :data-points [point]})]})

(defn- physical [fields key]
  (:physical (first (filter #(= key (:key %)) fields))))

(defn- typed-query [table name-column generic-column fields]
  (let [count-field (physical fields "count") flag-field (physical fields "flag")]
    (str "SELECT " name-column " AS label, " generic-column " AS generic, "
         "toString(toUnixTimestamp64Nano(Timestamp)) AS timestamp_ticks, "
         "toString(`" (:value-column count-field) "`) AS typed_count, "
         "toTypeName(`" (:value-column count-field) "`) AS count_type, "
         "`" (:status-column count-field) "` AS count_status, "
         "`" (:value-column flag-field) "` AS typed_flag, "
         "`" (:status-column flag-field) "` AS flag_status FROM " table
         " ORDER BY label")))

(defn- row-counts [connection]
  (mapv (fn [table] (:n (jdbc/fetch-one connection (str "SELECT count() AS n FROM " table))))
        ["otel_traces" "otel_logs" "otel_metrics_gauge" "otel_metrics_sum" "otel_metrics_histogram"]))

(defn- diagnose-generic! [table rows]
  ;; Fixed candidate booleans only; never print actual generic maps or values.
  (let [table-label (case table "otel_traces" :traces "otel_logs" :logs :other)
        value (get (:generic (nth rows 3 nil)) "count")]
    (println :generic-fallback-diagnostic :table table-label
             :exact-four-rows (= 4 (count rows))
             :literal-bigintN (= "9223372036854775808N" value)
             :plaininteger (= "9223372036854775808" value))))

(defn run-native! [check]
  ;; One actual memory anchor owns all native handles/schema. The installer uses
  ;; real DESCRIBE/ALTER operations; only its object-store backend is in memory.
  ;; No spy or with-redefs suppresses transport or manufactures native counts.
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (schema/ensure-schema! connection)
    (let [spans (install! connection :spans) records (install! connection :logs)
          gauges (install! connection :metrics)
          writer (exporter/exporter {:connection connection :create-schema? false
                                     :signals #{:spans :logs :metrics}
                                     :typed-span-descriptors (:capability spans)
                                     :typed-log-descriptors (:capability records)
                                     :typed-gauge-descriptors (:capability gauges)})
          inputs [["a-zero? é\n\"\\" {"flag" false "count" 0}]
                  ["b-min" {"flag" true "count" int64-min}]
                  ["c-max" {"count" int64-max}]
                  ["d-invalid" {"count" 9223372036854775808N}]]
          ticks [0 1 1700000000123456789 0]]
      (try
        (check "real typed span/log/gauge installations active" [:active :active :active]
               [(:status spans) (:status records) (:status gauges)])
        (check "ordinary native spans accepted" true
               (diagnosed-result writer
                                 (export/export-spans!
                                  writer (mapv (fn [index [name attributes]]
                                                 (let [start (nth ticks index)
                                                       duration (if (zero? index) uint64-max 1)]
                                                   (assoc (span name duration attributes)
                                                          :start-time-unix-nano start
                                                          :end-time-unix-nano (+ start duration)
                                                          :events (if (zero? index)
                                                                    (mapv (fn [n] {:name "exact" :timestamp-unix-nano n
                                                                                   :attributes {}}) (take 3 ticks)) []))))
                                               (range 4) inputs))))
        (check "ordinary native logs accepted" true
               (logs/export-logs! writer
                                  (mapv (fn [index [name attributes]]
                                          (assoc (log-record name attributes)
                                                 :timestamp-unix-nano (if (= index 2) 0 (nth ticks index))
                                                 :observed-time-unix-nano (nth ticks index)))
                                        (range 4) inputs)))
        (doseq [[table name-column generic-column fields]
                [["otel_traces" "SpanName" "SpanAttributes" (:fields spans)]
                 ["otel_logs" "Body" "LogAttributes" (:fields records)]]]
          (let [rows (jdbc/fetch connection (typed-query table name-column generic-column fields))]
            (check (str table " exact Int64 decimal/type readback")
                   [["0" "Int64"] [(str int64-min) "Int64"] [(str int64-max) "Int64"] ["0" "Int64"]]
                   (mapv #(vector (:typed_count %) (:count_type %)) rows))
            (check (str table " valid/invalid projection statuses") [3 3 3 4]
                   (mapv :count_status rows))
            (check (str table " false and absent flag preservation")
                   [[false 3] [true 3] [false 1] [false 1]]
                   (mapv #(vector (:typed_flag %) (:flag_status %)) rows))
            (diagnose-generic! table rows)
            (check (str table " generic values retained")
                   [{"flag" "false" "count" "0"} {"flag" "true" "count" (str int64-min)}
                    {"count" (str int64-max)} {"count" "9223372036854775808N"}]
                   (mapv :generic rows))
            (check (str table " complete literal text retained") (mapv first inputs) (mapv :label rows))))
        (doseq [[table name-column generic-column fields]
                [["otel_traces" "SpanName" "SpanAttributes" (:fields spans)]
                 ["otel_logs" "Body" "LogAttributes" (:fields records)]]]
          (check (str table " exact zero/one/current timestamp nanos including observed fallback")
                 (mapv str ticks)
                 (mapv :timestamp_ticks (jdbc/fetch connection
                                                  (typed-query table name-column generic-column fields)))))
        (check "native event timestamp array exact nanosecond readback"
               ["0" "1" "1700000000123456789"]
               (:event_ticks (jdbc/fetch-one connection
                                            "SELECT arrayMap(x -> toString(toUnixTimestamp64Nano(x)), `Events.Timestamp`) AS event_ticks FROM otel_traces WHERE notEmpty(`Events.Timestamp`)")))
        (let [duration (jdbc/fetch-one connection
                                      "SELECT Duration AS numeric_value, toString(Duration) AS decimal_value, toTypeName(Duration) AS physical_type FROM otel_traces WHERE Duration=toUInt64('18446744073709551615') LIMIT 1")]
          (check "UInt64 maximum Duration exact decimal/type/numeric"
                 [uint64-max (str uint64-max) "UInt64"]
                 [(:numeric_value duration) (:decimal_value duration) (:physical_type duration)]))
        (check "ordinary native all metric types accepted" true
               (export/export-metrics!
                writer {:attributes {}}
                [(metric :gauge "float-max" {:value 1.7976931348623157E308})
                 (metric :gauge "float-subnormal" {:value 4.9E-324})
                 (metric :sum "sum-zero" {:value 0.0})
                 (metric :histogram "hist-max" {:count uint64-max :sum 0.0
                                                :bucket-counts [uint64-max 0]})]))
        (check "ordinary native typed gauge accepted" true
               (export/export-metrics!
                writer {:attributes {}}
                [(metric :gauge "typed-gauge"
                         {:value 3.0 :attributes {"flag" false "count" int64-max}})]))
        (check "all five native tables independently reconcile tiny fixture"
               [4 4 3 1 1] (row-counts connection))
        (let [count-field (physical (:fields gauges) "count")
              flag-field (physical (:fields gauges) "flag")
              row (jdbc/fetch-one
                   connection
                   (str "SELECT Attributes AS generic, toString(`" (:value-column count-field)
                        "`) AS typed_count, toTypeName(`" (:value-column count-field)
                        "`) AS count_type, `" (:status-column count-field)
                        "` AS count_status, `" (:value-column flag-field)
                        "` AS typed_flag, `" (:status-column flag-field)
                        "` AS flag_status FROM otel_metrics_gauge WHERE MetricName = 'typed-gauge'"))]
          (check "typed gauge native exact Int64 and false readback"
                 [{"flag" "false" "count" (str int64-max)} (str int64-max) "Int64" 3 false 3]
                 [(:generic row) (:typed_count row) (:count_type row) (:count_status row)
                  (:typed_flag row) (:flag_status row)]))
        (check "sum retains zero and Float64 type"
               [true "Float64"]
               (let [row (jdbc/fetch-one connection "SELECT Value AS value, toTypeName(Value) AS physical_type FROM otel_metrics_sum")]
                 ;; JSON zero may be an integer token; physical SQL type is
                 ;; independently exact. Never coerce strings into numbers.
                 (println :sum-zero-diagnostic :numeric (number? (:value row))
                          :integer (integer? (:value row)) :floating (float? (:value row))
                          :physical-float64 (= "Float64" (:physical_type row)))
                 [(and (number? (:value row)) (== 0.0 (:value row))) (:physical_type row)]))
        (let [histogram (jdbc/fetch-one connection
                                       "SELECT Count AS numeric_count, toString(Count) AS decimal_count, toTypeName(Count) AS count_type, BucketCounts AS buckets, arrayMap(x -> toString(x), BucketCounts) AS decimal_buckets, toTypeName(BucketCounts) AS buckets_type FROM otel_metrics_histogram")]
          (check "UInt64 maximum Count/BucketCounts exact physical readback"
                 [uint64-max (str uint64-max) "UInt64" [uint64-max 0] [(str uint64-max) "0"] "Array(UInt64)"]
                 [(:numeric_count histogram) (:decimal_count histogram) (:count_type histogram)
                  (:buckets histogram) (:decimal_buckets histogram) (:buckets_type histogram)]))
        (check "finite Float64 maximum and subnormal exact readback"
               [["float-max" 1.7976931348623157E308 "Float64"] ["float-subnormal" 4.9E-324 "Float64"]]
               (mapv #(vector (:label %) (:value %) (:physical_type %))
                     (jdbc/fetch connection "SELECT MetricName AS label, Value AS value, toTypeName(Value) AS physical_type FROM otel_metrics_gauge WHERE MetricName != 'typed-gauge' ORDER BY label")))
        (doseq [invalid [Double/NaN Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY]]
          (let [before (row-counts connection)]
            (check "nonfinite actual metric batch rejected" false
                   (export/export-metrics! writer {:attributes {}}
                                           [(metric :gauge "invalid" {:value invalid})]))
            (check "nonfinite batch inserts no rows" before (row-counts connection))))
        (doseq [invalid [-1 9223372036854775808N]]
          (let [before (row-counts connection)]
            (check "later invalid span timestamp rejects before native effects" false
                   (export/export-spans! writer
                                         [(span "valid-before-invalid-time" 1 {})
                                          (assoc (span "invalid-time" 1 {}) :start-time-unix-nano invalid)]))
            (check "invalid span timestamp leaves all tables unchanged" before (row-counts connection))
            (check "later invalid observed fallback rejects log batch" false
                   (logs/export-logs! writer
                                      [(log-record "valid-before-invalid-log" {})
                                       (assoc (log-record "invalid-observed" {})
                                              :timestamp-unix-nano 0 :observed-time-unix-nano invalid)]))
            (check "invalid observed fallback leaves all tables unchanged" before (row-counts connection))
            (check "later invalid event timestamp rejects span batch" false
                   (export/export-spans! writer
                                         [(span "valid-before-invalid-event" 1 {})
                                          (assoc (span "invalid-event" 1 {})
                                                 :events [{:name "invalid" :timestamp-unix-nano invalid}])]))
            (check "invalid event timestamp leaves all tables unchanged" before (row-counts connection))))
        (doseq [invalid [{:count -1 :bucket-counts [0 0]}
                        {:count 18446744073709551616N :bucket-counts [0 0]}
                        {:count 0 :bucket-counts [-1 0]}
                        {:count 0 :bucket-counts [18446744073709551616N 0]}]]
          (let [before (row-counts connection)]
            (check "later invalid physical UInt64 rejects mixed metric batch" false
                   (export/export-metrics! writer {:attributes {}}
                                           [(metric :gauge "valid-before-invalid" {:value 0.0})
                                            (metric :histogram "invalid-later" (assoc invalid :sum 0.0))]))
            (check "later invalid type leaves every native table unchanged" before (row-counts connection))))
        ;; close-signal! returns true for each application-owned signal facade;
        ;; verify the successful path, without claiming it closes our anchor.
        (check "successful public per-signal shutdowns confirm acceptance"
               [true true true]
               (mapv #(% writer) [export/shutdown-exporter! logs/shutdown-log-exporter!
                                  export/shutdown-metric-exporter!]))
        (check "retired facade rejects all further signal batches"
               [false false false]
               [(export/export-spans! writer []) (logs/export-logs! writer [])
                (export/export-metrics! writer {:attributes {}} [])])
        (finally
          ;; Attempt each public per-signal shutdown; swallowed secondary errors
          ;; are not evidence of facade retirement. with-open closes the shared
          ;; anchor. This is neither closure proof nor native batch rollback proof.
          ;; Independent attempts avoid one secondary shutdown failure skipping
          ;; the other signals or replacing the original qualification failure.
          (doseq [shutdown [export/shutdown-exporter! logs/shutdown-log-exporter!
                            export/shutdown-metric-exporter!]]
            (try (shutdown writer) (catch Throwable _ nil))))))))
