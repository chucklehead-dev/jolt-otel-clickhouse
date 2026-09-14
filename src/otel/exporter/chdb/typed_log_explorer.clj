(ns otel.exporter.chdb.typed-log-explorer
  "Bounded schema-bound queries over installer-confirmed typed log fields."
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
   :signals [:logs]
   :types [:boolean :int64 :string]})

(def max-filter-value-length 256)

(def ^:private request-keys
  #{:attribute-key :attribute-location :end-unix-nano :limit
    :max-text-length :operator :schema-binding :signal
    :start-unix-nano :value})
(def ^:private coverage-request-keys
  #{:attribute-key :attribute-location :end-unix-nano :schema-binding
    :signal :start-unix-nano})
(def ^:private binding-keys
  #{:attribute-key :attribute-location :attribute-type :field-id
    :manifest-version})
(def ^:private safe-column
  (re-pattern "a[sv]_lg_lg_[a-z0-9_]+_[0-9a-f]{16}"))
(def ^:private int64-min -9223372036854775808)
(def ^:private int64-max 9223372036854775807)
(def ^:private coverage-keys
  #{:absent :historicalfallback :historicalunavailable :invalid
    :presentempty :total :unknownstatus :valid})
(def ^:private row-keys
  #{:attributevalue :body :servicename :severitytext :spanid
    :timestampunixnano :traceid :typedstatus})

(defn supported-typed-log-filters [] filter-capability)

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :attribute-explorer/error true :type type))))

(defn- binding [field]
  {:attribute-key (:key field)
   :attribute-location (:location field)
   :attribute-type (:type field)
   :field-id (:id field)
   :manifest-version (get-in field [:identity :version])})

(defn- valid-binding? [value]
  (and (map? value) (= binding-keys (set (keys value)))
       (string? (:field-id value))
       (re-matches #"attribute_[0-9a-f]{20}" (:field-id value))
       (string? (:attribute-key value))
       (= :log-attributes (:attribute-location value))
       (contains? #{:boolean :int64 :string} (:attribute-type value))
       (integer? (:manifest-version value)) (pos? (:manifest-version value))))

(defn- instant! [parameter value]
  (when-not (and (integer? value) (<= 0 value 9223372036854775807))
    (fail! ::invalid-time "typed log query time is invalid"
           {:parameter parameter}))
  value)

(defn- positive-cap! [parameter value maximum]
  (when-not (and (integer? value) (<= 1 value maximum))
    (fail! ::invalid-bound "typed log query bound is invalid"
           {:parameter parameter :maximum maximum}))
  value)

(defn- column! [value]
  (when-not (and (string? value) (re-matches safe-column value))
    (fail! ::invalid-typed-column "typed log capability contains an invalid column" {}))
  value)

(defn- field! [connection descriptor-set options]
  (when (nil? connection)
    (fail! ::invalid-connection
           "typed log queries require the installed target connection" {}))
  (when-not (= :logs (:signal options))
    (fail! ::unsupported-signal "typed log queries support logs only"
           {:supported-signals [:logs]}))
  (when-not (= :log-attributes (:attribute-location options))
    (fail! ::unsupported-location
           "typed log queries support log-record attributes only"
           {:supported-locations [:log-attributes]}))
  (let [fields (projection/confirmed-log-fields descriptor-set connection)
        field (some #(when (and (= (:attribute-key options) (:key %))
                                (= :log-attributes (:location %))) %)
                    fields)]
    (when-not field
      (fail! ::unknown-field "typed log field is not installer-confirmed" {}))
    (when-not (and (= "otel_logs" (:table field))
                   (= identity/log-attribute-target (identity/target-of field)))
      (fail! ::invalid-target "typed log capability has an invalid target" {}))
    (let [expected (binding field)
          actual (:schema-binding options)]
      (when-not (valid-binding? actual)
        (fail! ::invalid-binding "typed log schema binding is invalid" {}))
      (when-not (= expected actual)
        (fail! ::stale-binding "typed log schema binding is no longer available" {})))
    field))

(defn- base-request! [connection descriptor-set options allowed-keys]
  (when-not (map? options)
    (fail! ::invalid-request "typed log query request must be a map" {}))
  (when-let [unknown (seq (remove allowed-keys (keys options)))]
    (fail! ::unsupported-request-key
           "typed log query request contains unsupported keys"
           {:keys (vec (sort-by str unknown))}))
  (let [field (field! connection descriptor-set options)
        start (instant! :start-unix-nano (:start-unix-nano options))
        end (instant! :end-unix-nano (:end-unix-nano options))]
    (when-not (and (< start end)
                   (<= (- end start) explorer/max-time-range-nanos))
      (fail! ::invalid-time-window "typed log query window is invalid" {}))
    {:end end :field field :start start}))

(defn- request! [connection descriptor-set options]
  (let [{:keys [field] :as base}
        (base-request! connection descriptor-set options request-keys)
        type (:type field)
        operator (:operator options)
        value (:value options)]
    (when-not (contains? (set (get-in filter-capability [:operators type])) operator)
      (fail! ::unsupported-operator "typed log filter operator is unsupported" {}))
    (when-not
     (case type
       :boolean (boolean? value)
       :int64 (and (integer? value) (<= int64-min value int64-max))
       :string (and (string? value) (<= (count value) max-filter-value-length)
                    (or (= :eq operator) (not (empty? value))))
       false)
      (fail! ::invalid-value "typed log filter value is invalid" {}))
    (assoc base
           :limit (positive-cap! :limit (:limit options)
                                 explorer/max-result-limit)
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
             "typed log value and status columns must be distinct" {}))
    {:value value :status status}))

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

(defn- match-sql [{:keys [field] :as request}]
  (let [{:keys [value status]} (columns! field)
        string? (= :string (:type field))]
    (str "SELECT toUnixTimestamp64Nano(Timestamp) AS timestampunixnano,\n"
         "       TraceId AS traceid, SpanId AS spanid,\n"
         "       leftUTF8(ServiceName, ?) AS servicename,\n"
         "       leftUTF8(SeverityText, ?) AS severitytext,\n"
         "       leftUTF8(Body, ?) AS body,\n"
         "       " (if string? (str "leftUTF8(" value ", ?)") value)
         " AS attributevalue, " status " AS typedstatus\n"
         "FROM otel_logs\n"
         "WHERE toUnixTimestamp64Nano(Timestamp) >= ?\n"
         "  AND toUnixTimestamp64Nano(Timestamp) < ?\n"
         "  AND " status (if string? " IN (2, 3)\n" " = 3\n")
         "  AND " (predicate request value) "\n"
         "ORDER BY Timestamp DESC, TraceId ASC, SpanId ASC\n"
         "LIMIT ?\nSETTINGS max_result_bytes = "
         explorer/max-typed-int64-result-bytes
         ", max_rows_to_read = " explorer/max-typed-int64-scan-rows
         ", max_bytes_to_read = " explorer/max-typed-int64-scan-bytes
         ", max_memory_usage = " explorer/max-typed-int64-memory-bytes
         ", max_execution_time = " explorer/max-typed-int64-query-seconds
         ", max_threads = 1")))

(defn- match-params [{:keys [end field limit start text-length value]}]
  (vec (concat (repeat (if (= :string (:type field)) 4 3) text-length)
               [start end value limit])))

(defn- coverage-sql [field]
  (let [{:keys [status]} (columns! field)]
    (str "SELECT countIf(typedstatus = 3) AS valid,\n"
         " countIf(typedstatus = 2) AS presentempty,\n"
         " countIf(typedstatus = 1) AS absent,\n"
         " countIf(typedstatus = 4) AS invalid,\n"
         " countIf(typedstatus = 0 AND hasfallback) AS historicalfallback,\n"
         " countIf(typedstatus = 0 AND NOT hasfallback) AS historicalunavailable,\n"
         " countIf(typedstatus NOT IN (0,1,2,3,4)) AS unknownstatus, count() AS total\n"
         "FROM (SELECT " status " AS typedstatus, mapContains(LogAttributes, ?) AS hasfallback\n"
         " FROM otel_logs WHERE toUnixTimestamp64Nano(Timestamp) >= ?\n"
         " AND toUnixTimestamp64Nano(Timestamp) < ?)\n"
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
      (fail! ::invalid-result "typed log coverage has an invalid shape" {}))
    (let [{:keys [absent historicalfallback historicalunavailable invalid
                  presentempty total unknownstatus valid]} (first rows)
          counts [valid presentempty absent invalid historicalfallback
                  historicalunavailable unknownstatus]]
      (when-not (and (every? #(and (integer? %) (not (neg? %))) counts)
                     (integer? total)
                     (<= 0 total explorer/max-typed-int64-scan-rows)
                     (= total (reduce + 0 counts)) (zero? unknownstatus))
        (fail! ::invalid-result "typed log coverage contains invalid statuses" {}))
      {:valid valid :present-empty presentempty :absent absent :invalid invalid
       :historical-untyped-fallback historicalfallback
       :historical-untyped-unavailable historicalunavailable :total total})))

(defn typed-log-coverage
  "Return six-way bounded coverage for one exact typed log schema binding."
  [connection descriptor-set options]
  (let [{:keys [field] :as request}
        (base-request! connection descriptor-set options coverage-request-keys)]
    (context/with-instrumentation-suppressed
      (merge (binding field) {:signal :logs
                              :coverage (coverage! connection request)}))))

(defn- row! [{:keys [field text-length]} row]
  (when-not (and (map? row) (= row-keys (set (keys row))))
    (fail! ::invalid-result "typed log match row has an invalid shape" {}))
  (let [{:keys [attributevalue body servicename severitytext spanid
                timestampunixnano traceid typedstatus]} row
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
                   (every? string? [traceid spanid servicename severitytext body])
                   (every? #(<= (count %) text-length)
                           [servicename severitytext body]) value?)
      (fail! ::invalid-result "typed log match row contains an invalid value" {}))
    (merge (binding field)
           {:attribute-value attributevalue :body body
            :service-name servicename :severity-text severitytext
            :signal :logs :source :typed :span-id spanid
            :timestamp-unix-nano timestampunixnano :trace-id traceid
            :typed-status typedstatus})))

(defn typed-log-filtered-records
  "Return bounded typed log matches plus availability coverage."
  [connection descriptor-set options]
  (let [{:keys [field limit] :as request}
        (request! connection descriptor-set options)]
    (context/with-instrumentation-suppressed
      (let [coverage (coverage! connection request)
            rows (jdbc/fetch connection
                             (into [(match-sql request)] (match-params request))
                             {:max-rows limit})]
        (when-not (and (vector? rows) (<= (count rows) limit))
          (fail! ::invalid-result "typed log result exceeds its bound" {}))
        (merge (binding field)
               {:coverage coverage
                :filter {:operator (:operator request) :value (:value request)}
                :matches (mapv #(row! request %) rows)
                :signal :logs})))))
