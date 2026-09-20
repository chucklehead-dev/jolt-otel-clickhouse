(ns otel.exporter.chdb.typed-metric-explorer
  "Bounded schema-bound queries over installer-confirmed typed gauge fields.

  This is deliberately a query surface for the already-supported gauge table,
  not a second schema installer. Sum and histogram fields are rejected before
  any JDBC work."
  (:require [db.jdbc]
            [jdbc.core :as jdbc]
            [otel.context :as context]
            [otel.exporter.chdb.attribute-identity :as identity]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.explorer :as explorer]))

(def filter-capability
  {:operators {:boolean [:eq]
               :int64 [:eq :gte :lt]
               :string [:eq :prefix :contains]}
   :signals [:metrics]
   :metric-kinds [:gauge]
   :locations [:resource-attributes :scope-attributes :metric-attributes]
   :types [:boolean :int64 :string]})

(def max-filter-value-length 256)

(def ^:private filter-request-keys
  #{:end-unix-nano :limit :max-text-length :metric-kind :operator :schema-binding
    :signal :start-unix-nano :value})
(def ^:private coverage-request-keys
  #{:end-unix-nano :metric-kind :schema-binding :signal :start-unix-nano})
(def ^:private binding-keys
  #{:attribute-key :attribute-location :attribute-type :field-id
    :manifest-version})
(def ^:private gauge-locations
  #{:resource-attributes :scope-attributes :metric-attributes})
(def ^:private safe-column
  (re-pattern "a[sv]_mg_(rs|sc|mt)_[a-z0-9_]+_[0-9a-f]{16}"))
(def ^:private int64-min -9223372036854775808)
(def ^:private int64-max 9223372036854775807)
(def ^:private coverage-keys
  #{:absent :historicalfallback :historicalunavailable :invalid
    :presentempty :total :unknownstatus :valid})
(def ^:private row-keys
  #{:attributevalue :metricname :metricunit :metricvalue :scopename
    :servicename :timestampunixnano :typedstatus})

(defn supported-typed-gauge-filters [] filter-capability)

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :attribute-explorer/error true :type type))))

(defn- binding [field]
  {:attribute-key (:key field)
   :attribute-location (:location field)
   :attribute-type (:type field)
   :field-id (:id field)
   :manifest-version (get-in field [:identity :version])})

(defn- valid-binding? [value]
  (and (map? value)
       (= binding-keys (set (keys value)))
       (string? (:field-id value))
       (re-matches #"attribute_[0-9a-f]{20}" (:field-id value))
       (string? (:attribute-key value))
       (contains? gauge-locations (:attribute-location value))
       (contains? #{:boolean :int64 :string} (:attribute-type value))
       (integer? (:manifest-version value))
       (pos? (:manifest-version value))))

(defn- instant! [parameter value]
  (when-not (and (integer? value) (<= 0 value 9223372036854775807))
    (fail! ::invalid-time "typed gauge query time is invalid" {:parameter parameter}))
  value)

(defn- positive-cap! [parameter value maximum]
  (when-not (and (integer? value) (<= 1 value maximum))
    (fail! ::invalid-bound "typed gauge query bound is invalid"
           {:parameter parameter :maximum maximum}))
  value)

(defn- column! [value]
  (when-not (and (string? value) (re-matches safe-column value))
    (fail! ::invalid-typed-column
           "typed gauge capability contains an invalid column" {}))
  value)

(defn- gauge-fields! [connection descriptor-set]
  (let [fields (projection/confirmed-gauge-fields descriptor-set connection)]
    (when-not (every? #(contains? identity/gauge-attribute-targets
                                   (identity/target-of %))
                      fields)
      (fail! ::invalid-target "typed gauge capability has an invalid target" {}))
    fields))

(defn typed-gauge-fields
  "Discover the exact queryable schema bindings in a confirmed gauge capability.

  Returned bindings deliberately omit physical column names. They are the only
  bindings accepted by the filter and coverage operations, so callers can save
  a schema selection without acquiring SQL authority."
  [connection descriptor-set]
  (when (nil? connection)
    (fail! ::invalid-connection
           "typed gauge discovery requires the installed target connection" {}))
  (mapv (fn [field]
          {:signal :metrics :metric-kind :gauge
           :schema-binding (binding field)
           :operators (get-in filter-capability [:operators (:type field)])})
        (gauge-fields! connection descriptor-set)))

(defn- field! [connection descriptor-set options]
  (when (nil? connection)
    (fail! ::invalid-connection
           "typed gauge queries require the installed target connection" {}))
  (when-not (= :metrics (:signal options))
    (fail! ::unsupported-signal "typed gauge queries support metrics only"
           {:supported-signals [:metrics]}))
  (when-not (= :gauge (:metric-kind options))
    (fail! ::unsupported-metric-kind "typed metric queries support gauges only"
           {:supported-metric-kinds [:gauge]}))
  (let [actual (:schema-binding options)]
    (when-not (valid-binding? actual)
      (fail! ::invalid-binding "typed gauge schema binding is invalid" {}))
    (let [field (some #(when (= actual (binding %)) %) (gauge-fields! connection descriptor-set))]
      (when-not field
        (fail! ::stale-binding
               "typed gauge schema binding is no longer available" {}))
      field)))

(defn- base-request! [connection descriptor-set options allowed-keys]
  (when-not (map? options)
    (fail! ::invalid-request "typed gauge query request must be a map" {}))
  (when-let [unknown (seq (remove allowed-keys (keys options)))]
    (fail! ::unsupported-request-key
           "typed gauge query request contains unsupported keys"
           {:keys (vec (sort-by str unknown))}))
  (let [field (field! connection descriptor-set options)
        start (instant! :start-unix-nano (:start-unix-nano options))
        end (instant! :end-unix-nano (:end-unix-nano options))]
    (when-not (and (< start end) (<= (- end start) explorer/max-time-range-nanos))
      (fail! ::invalid-time-window "typed gauge query window is invalid" {}))
    {:end end :field field :start start}))

(defn- request! [connection descriptor-set options]
  (let [{:keys [field] :as base}
        (base-request! connection descriptor-set options filter-request-keys)
        type (:type field)
        operator (:operator options)
        value (:value options)]
    (when-not (contains? (set (get-in filter-capability [:operators type])) operator)
      (fail! ::unsupported-operator "typed gauge filter operator is unsupported" {}))
    (when-not
     (case type
       :boolean (boolean? value)
       :int64 (and (integer? value) (<= int64-min value int64-max))
       :string (and (string? value) (<= (count value) max-filter-value-length)
                    (or (= :eq operator) (not (empty? value))))
       false)
      (fail! ::invalid-value "typed gauge filter value is invalid" {}))
    (assoc base
           :limit (positive-cap! :limit (:limit options) explorer/max-result-limit)
           :operator operator
           :text-length (positive-cap! :max-text-length
                                       (get options :max-text-length
                                            explorer/default-text-length)
                                       explorer/max-text-length)
           :value value)))

(defn- columns! [field]
  (let [value (column! (get-in field [:physical :value-column]))
        status (column! (get-in field [:physical :status-column]))]
    (when (= value status)
      (fail! ::invalid-typed-column
             "typed gauge value and status columns must be distinct" {}))
    {:value value :status status}))

(defn- generic-map-column [field]
  (case (:location field)
    :resource-attributes "ResourceAttributes"
    :scope-attributes "ScopeAttributes"
    :metric-attributes "Attributes"
    (fail! ::invalid-target "typed gauge field has an invalid location" {})))

(defn- predicate [{:keys [field operator]} value-column]
  (case (:type field)
    :boolean (str value-column " = ?")
    :int64 (case operator
             :eq (str value-column " = ?")
             :gte (str value-column " >= ?")
             :lt (str value-column " < ?"))
    :string (case operator
              :eq (str value-column " = ?")
              :prefix (str "startsWith(" value-column ", ?)")
              :contains (str "positionUTF8(" value-column ", ?) > 0"))))

(def ^:private metric-time-nanos "toInt64(toUnixTimestamp(TimeUnix)) * 1000000000")

(defn- match-sql [{:keys [field] :as request}]
  (let [{:keys [value status]} (columns! field)
        string? (= :string (:type field))]
    (str "SELECT " metric-time-nanos " AS timestampunixnano,\n"
         "       leftUTF8(MetricName, ?) AS metricname,\n"
         "       leftUTF8(MetricUnit, ?) AS metricunit,\n"
         "       leftUTF8(ServiceName, ?) AS servicename,\n"
         "       leftUTF8(ScopeName, ?) AS scopename,\n"
         "       Value AS metricvalue,\n"
         "       " (if string? (str "leftUTF8(" value ", ?)") value)
         " AS attributevalue, " status " AS typedstatus\n"
         "FROM otel_metrics_gauge\n"
         "WHERE " metric-time-nanos " >= ?\n"
         "  AND " metric-time-nanos " < ?\n"
         "  AND " status (if string? " IN (2, 3)\n" " = 3\n")
         "  AND " (predicate request value) "\n"
         "ORDER BY TimeUnix DESC, MetricName ASC, ServiceName ASC, ScopeName ASC, MetricUnit ASC\n"
         "LIMIT ?\nSETTINGS max_result_bytes = " explorer/max-typed-int64-result-bytes
         ", max_rows_to_read = " explorer/max-typed-int64-scan-rows
         ", max_bytes_to_read = " explorer/max-typed-int64-scan-bytes
         ", max_memory_usage = " explorer/max-typed-int64-memory-bytes
         ", max_execution_time = " explorer/max-typed-int64-query-seconds
         ", max_threads = 1")))

(defn- match-params [{:keys [end field limit start text-length value]}]
  (vec (concat (repeat (if (= :string (:type field)) 5 4) text-length)
               [start end value limit])))

(defn- coverage-sql [field]
  (let [{:keys [status]} (columns! field)
        generic-map (generic-map-column field)]
    (str "SELECT countIf(typedstatus = 3) AS valid,\n"
         " countIf(typedstatus = 2) AS presentempty,\n"
         " countIf(typedstatus = 1) AS absent,\n"
         " countIf(typedstatus = 4) AS invalid,\n"
         " countIf(typedstatus = 0 AND hasfallback) AS historicalfallback,\n"
         " countIf(typedstatus = 0 AND NOT hasfallback) AS historicalunavailable,\n"
         " countIf(typedstatus NOT IN (0,1,2,3,4)) AS unknownstatus, count() AS total\n"
         "FROM (SELECT " status " AS typedstatus, mapContains(" generic-map ", ?) AS hasfallback\n"
         " FROM otel_metrics_gauge WHERE " metric-time-nanos " >= ?\n"
         " AND " metric-time-nanos " < ?)\n"
         "SETTINGS max_result_bytes = " explorer/max-typed-int64-result-bytes
         ", max_rows_to_read = " explorer/max-typed-int64-scan-rows
         ", max_bytes_to_read = " explorer/max-typed-int64-scan-bytes
         ", max_memory_usage = " explorer/max-typed-int64-memory-bytes
         ", max_execution_time = " explorer/max-typed-int64-query-seconds
         ", max_threads = 1")))

(defn- coverage! [connection {:keys [field start end]}]
  (let [rows (jdbc/fetch connection
                         [(coverage-sql field) (:key field) start end]
                         {:max-rows 1})]
    (when-not (and (vector? rows) (= 1 (count rows))
                   (= coverage-keys (set (keys (first rows)))))
      (fail! ::invalid-result "typed gauge coverage has an invalid shape" {}))
    (let [{:keys [absent historicalfallback historicalunavailable invalid
                  presentempty total unknownstatus valid]} (first rows)
          counts [valid presentempty absent invalid historicalfallback
                  historicalunavailable unknownstatus]]
      (when-not (and (every? #(and (integer? %) (not (neg? %))) counts)
                     (integer? total)
                     (<= 0 total explorer/max-typed-int64-scan-rows)
                     (= total (reduce + 0 counts)) (zero? unknownstatus))
        (fail! ::invalid-result "typed gauge coverage contains invalid statuses" {}))
      {:valid valid :present-empty presentempty :absent absent :invalid invalid
       :historical-untyped-fallback historicalfallback
       :historical-untyped-unavailable historicalunavailable :total total})))

(defn typed-gauge-coverage
  "Return six-way bounded coverage for one exact typed gauge schema binding."
  [connection descriptor-set options]
  (let [{:keys [field] :as request}
        (base-request! connection descriptor-set options coverage-request-keys)]
    (context/with-instrumentation-suppressed
      (merge (binding field)
             {:signal :metrics :metric-kind :gauge
              :coverage (coverage! connection request)}))))

(defn- finite-number? [value]
  (and (number? value)
       (let [n (double value)]
         (and (= n n) (not= n ##Inf) (not= n ##-Inf)))))

(defn- row! [{:keys [field text-length]} row]
  (when-not (and (map? row) (= row-keys (set (keys row))))
    (fail! ::invalid-result "typed gauge match row has an invalid shape" {}))
  (let [{:keys [attributevalue metricname metricunit metricvalue scopename
                servicename timestampunixnano typedstatus]} row
        type (:type field)
        value? (case type
                 :boolean (and (= 3 typedstatus) (boolean? attributevalue))
                 :int64 (and (= 3 typedstatus) (integer? attributevalue)
                             (<= int64-min attributevalue int64-max))
                 :string (and (contains? #{2 3} typedstatus)
                              (string? attributevalue)
                              (<= (count attributevalue) text-length)
                              (= (= 2 typedstatus) (empty? attributevalue))))]
    (when-not (and (integer? timestampunixnano)
                   (<= 0 timestampunixnano 9223372036854775807)
                   (every? string? [metricname metricunit scopename servicename])
                   (every? #(<= (count %) text-length)
                           [metricname metricunit scopename servicename])
                   (finite-number? metricvalue) value?)
      (fail! ::invalid-result "typed gauge match row contains an invalid value" {}))
    (merge (binding field)
           {:attribute-value attributevalue :metric-name metricname
            :metric-unit metricunit :metric-value metricvalue
            :scope-name scopename :service-name servicename
            :signal :metrics :metric-kind :gauge :source :typed
            :timestamp-unix-nano timestampunixnano :typed-status typedstatus})))

(defn typed-gauge-filtered-points
  "Return bounded typed gauge matches plus availability coverage.

  Gauge storage has second-resolution timestamps and no point identifier, so
  rows are ordered observations rather than a claim of unique point identity."
  [connection descriptor-set options]
  (let [{:keys [field limit] :as request} (request! connection descriptor-set options)]
    (context/with-instrumentation-suppressed
      (let [coverage (coverage! connection request)
            rows (jdbc/fetch connection
                             (into [(match-sql request)] (match-params request))
                             {:max-rows limit})]
        (when-not (and (vector? rows) (<= (count rows) limit))
          (fail! ::invalid-result "typed gauge result exceeds its bound" {}))
        (merge (binding field)
               {:coverage coverage
                :filter {:operator (:operator request) :value (:value request)}
                :matches (mapv #(row! request %) rows)
                :signal :metrics :metric-kind :gauge})))))
