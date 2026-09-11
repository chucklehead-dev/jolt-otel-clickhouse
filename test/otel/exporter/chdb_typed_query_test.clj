(ns otel.exporter.chdb-typed-query-test
  (:require [db.jdbc]
            [clojure.string :as str]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [otel.context :as context]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.explorer :as explorer]))

(def malicious-key "checkout.count') OR 1=1 --")
(def malicious-string-key "checkout.note') OR 1=1 --")
(def malicious-string-value "ready') OR 1=1 --")

(defn- installed []
  (let [compiled
        (manifest/compile-manifest
         {:dataset-id "telemetry-prod" :application-id "checkout"
          :lineage "checkout-v1" :version 1
          :fragments
          [{:schema manifest/reviewed-fragment-schema
            :authority :advice :source "advice/checkout.edn"
            :entries
            [{:signal :spans :table "otel_traces"
              :location :span-attributes
              :key malicious-key :type :int64}
             {:signal :spans :table "otel_traces"
              :location :span-attributes
              :key "checkout.complete" :type :boolean}
             {:signal :spans :table "otel_traces"
              :location :span-attributes
              :key malicious-string-key :type :string}]}]})
        columns (registry/expected-columns (registry/prepare compiled))
        observed (atom {})
        next-column (atom 0)
        target (atom :query-target)
        installation
        (installer/install-approved!
         (backend/memory-backend) compiled
         {:target target
          :observe-columns #(vector {:columns (into {} @observed)
                                     :signal :spans :table "otel_traces"})
          :execute-ddl!
          (fn [_]
            (let [{:keys [name type]} (nth columns @next-column)]
              (swap! next-column inc)
              (swap! observed assoc name type)))})]
    {:descriptor-set (:descriptor-set installation)
     :descriptors (:descriptors installation)
     :installation installation :target target}))

(defn- request
  ([keys] (request keys {}))
  ([keys overrides]
   (merge {:signal :spans :keys keys
           :start-unix-nano 1700000000000000000
           :end-unix-nano 1700000001000000000
           :limit 7 :max-text-length 42}
          overrides)))

(defn- aggregate-request
  ([attribute-key] (aggregate-request attribute-key {}))
  ([attribute-key overrides]
   (merge {:signal :spans :attribute-key attribute-key
           :predicate {:gte -10 :lt 20}
           :group-by [:service-name]
           :aggregates [:count :min :max :avg]
           :start-unix-nano 1700000000000000000
           :end-unix-nano 1700000001000000000
           :limit 7 :max-text-length 42}
          overrides)))

(defn- filter-request
  ([attribute-key operator value]
   (filter-request attribute-key operator value {}))
  ([attribute-key operator value overrides]
   (merge {:signal :spans :attribute-key attribute-key
           :operator operator :value value
           :start-unix-nano 1700000000000000000
           :end-unix-nano 1700000001000000000
           :limit 7 :max-text-length 42}
          overrides)))

(def ^:private coverage-row
  {:valid 2 :presentempty 1 :absent 3 :invalid 4
   :historicalfallback 5 :historicalunavailable 6
   :unknownstatus 0 :total 21})

(defn- thrown-data [f]
  (try (f) nil (catch Throwable error (ex-data error))))

(defn run [check]
  (println "capability-bound typed span queries")
  (check "explorer publishes the closed typed Int64 aggregate vocabulary"
         {:aggregates [:count :min :max :avg]
          :group-by [:service-name]
          :predicate-keys [:gte :lt]
          :signals [:spans]
          :types [:int64]}
         (explorer/supported-typed-span-int64-aggregates))
  (check "explorer publishes closed Boolean, Int64, and string filter operators"
         {:operators {:boolean [:eq]
                      :int64 [:eq :gte :lt]
                      :string [:eq :prefix :contains]}
          :signals [:spans]
          :types [:boolean :int64 :string]}
         (explorer/supported-typed-span-filters))
  (let [{:keys [descriptor-set descriptors installation target]} (installed)
        calls (atom [])]
    (with-redefs
      [jdbc/fetch
       (fn [connection sqlvec options]
         (swap! calls conj [connection sqlvec options
                            (context/instrumentation-suppressed?)])
         (if (= malicious-key (second sqlvec))
           [{:value "7" :typedstatus 3 :count 4}]
           [{:value "not-bool" :typedstatus 4 :count 2}]))]
      (check "approved keys query typed and generic-fallback distributions"
             [{:attribute-key malicious-key :count 4 :signal :spans
               :source :typed :typed-status 3 :value "7"}
              {:attribute-key "checkout.complete" :count 2 :signal :spans
               :source :generic-fallback :typed-status 4
               :value "not-bool"}]
             (explorer/typed-span-values
              target descriptor-set
              (request [malicious-key "checkout.complete"]))))
    (check "typed query runs once per requested key under suppression"
           [2 [true true] [{:max-rows 7} {:max-rows 7}]]
           [(count @calls) (mapv #(nth % 3) @calls)
            (mapv #(nth % 2) @calls)])
    (doseq [[_ [sql key text-length start end limit] _ _] @calls]
      (check "logical key and every caller scalar remain JDBC parameters"
             [42 1700000000000000000 1700000001000000000 7]
             [text-length start end limit])
      (check "typed query uses only fixed table and library-owned columns"
             true
             (and (str/includes? sql "FROM otel_traces")
                  (str/includes? sql "SpanAttributes[?]")
                  (str/includes? sql "IN (2, 3)")
                  (str/includes? sql "IN (0, 4)")
                  (str/includes? sql "notEmpty(value) OR typedstatus = 2")
                  (str/includes? sql "`av_tr_sp_")
                  (str/includes? sql "`as_tr_sp_")
                  (not (str/includes? sql key))
                  (not (str/includes? sql "system.tables")))))

    (let [aggregate-calls (atom [])
          field (first (filter #(= malicious-key (:key %))
                               (get-in installation [:record :manifest :fields])))]
      (with-redefs
        [jdbc/fetch
         (fn [connection sqlvec options]
           (swap! aggregate-calls conj
                  [connection sqlvec options
                   (context/instrumentation-suppressed?)])
           [{:servicename "api" :count 2 :min -3 :max 19 :avg 8.0}])]
        (check "approved Int64 range aggregates preserve field version evidence"
               [{:attribute-key malicious-key
                 :field-id (:id field)
                 :manifest-version 1
                 :service-name "api"
                 :signal :spans :source :typed :typed-status 3
                 :count 2 :min -3 :max 19 :avg 8.0}]
               (explorer/typed-span-int64-aggregates
                target descriptor-set (aggregate-request malicious-key))))
      (let [[_ [sql & params] options suppressed?] (first @aggregate-calls)
            value-column (get-in field [:physical :value-column])
            status-column (get-in field [:physical :status-column])]
        (check "typed Int64 query binds its range window and result limit"
               [42 1700000000000000000 1700000001000000000 -10 20 7]
               params)
        (check "typed Int64 query is capability-owned bounded and map-free"
               true
               (and suppressed?
                    (= {:max-rows 7} options)
                    (str/includes? sql "FROM otel_traces")
                    (str/includes? sql (str "`" value-column "` >= ?"))
                    (str/includes? sql (str "`" value-column "` < ?"))
                    (str/includes? sql (str "`" status-column "` = 3"))
                    (str/includes? sql "min(`av_tr_sp_")
                    (str/includes? sql "max(`av_tr_sp_")
                    (str/includes? sql "avg(`av_tr_sp_")
                    (str/includes? sql "GROUP BY servicename")
                    (str/includes? sql "HAVING count() > 0")
                    (str/includes? sql "max_rows_to_read = 100000")
                    (str/includes? sql "max_bytes_to_read = 67108864")
                    (str/includes? sql "max_memory_usage = 134217728")
                    (str/includes? sql "max_execution_time = 5")
                    (str/includes? sql "max_threads = 1")
                    (not (str/includes? sql "SpanAttributes"))
                    (not (str/includes? sql malicious-key))))))

    (let [filter-calls (atom [])
          row (fn [attribute-value typed-status]
                {:attributevalue attribute-value
                 :parentspanid "0000000000000000"
                 :servicename "checkout"
                 :spanid "1111111111111111"
                 :spanname "checkout.complete"
                 :timestampunixnano 1700000000000000001
                 :traceid "11111111111111111111111111111111"
                 :typedstatus typed-status})]
      (with-redefs
        [jdbc/fetch
         (fn [connection sqlvec options]
           (swap! filter-calls conj
                  [connection sqlvec options
                   (context/instrumentation-suppressed?)])
           (if (str/includes? (first sqlvec) "countIf(typedstatus = 3)")
             [coverage-row]
             [(if (= false (nth sqlvec (- (count sqlvec) 2)))
                (row false 3)
                (row "" 2))]))]
        (let [boolean-result
              (explorer/typed-span-filtered-traces
               target descriptor-set
               (filter-request "checkout.complete" :eq false))
              string-result
              (explorer/typed-span-filtered-traces
               target descriptor-set
               (filter-request malicious-string-key :eq ""))]
          (check "false and present-empty string remain typed filter values"
                 [[false 3 :boolean] ["" 2 :string]]
                 [[(get-in boolean-result [:matches 0 :attribute-value])
                   (get-in boolean-result [:matches 0 :typed-status])
                   (:attribute-type boolean-result)]
                  [(get-in string-result [:matches 0 :attribute-value])
                   (get-in string-result [:matches 0 :typed-status])
                   (:attribute-type string-result)]])
          (check "coverage distinguishes every stored availability class"
                 {:absent 3
                  :historical-untyped-fallback 5
                  :historical-untyped-unavailable 6
                  :invalid 4 :present-empty 1 :total 21 :valid 2}
                 (:coverage string-result))))
      (check "each filter runs one coverage and one match query under suppression"
             [4 [true true true true]
              [{:max-rows 1} {:max-rows 7}
               {:max-rows 1} {:max-rows 7}]]
             [(count @filter-calls) (mapv #(nth % 3) @filter-calls)
              (mapv #(nth % 2) @filter-calls)])
      (let [[_ [boolean-coverage & boolean-coverage-params]]
            (nth @filter-calls 0)
            [_ [boolean-query & boolean-params]] (nth @filter-calls 1)
            [_ [string-coverage & string-coverage-params]]
            (nth @filter-calls 2)
            [_ [string-query & string-params]] (nth @filter-calls 3)]
        (check "coverage binds the logical key and exact time window"
               [["checkout.complete" 1700000000000000000
                 1700000001000000000]
                [malicious-string-key 1700000000000000000
                 1700000001000000000]]
               [boolean-coverage-params string-coverage-params])
        (check "false and empty string are bound predicate values"
               [[42 42 1700000000000000000 1700000001000000000 false 7]
                [42 42 42 1700000000000000000 1700000001000000000 "" 7]]
               [boolean-params string-params])
        (check "filter SQL is capability-owned, bounded, and fallback-free"
               true
               (and (str/includes? boolean-query "FROM otel_traces")
                    (str/includes? boolean-query " = 3")
                    (str/includes? string-query " IN (2, 3)")
                    (str/includes? string-query "ORDER BY Timestamp DESC")
                    (str/includes? string-query "max_rows_to_read = 100000")
                    (str/includes? string-coverage "mapContains(SpanAttributes, ?)")
                    (str/includes? boolean-coverage "unknownstatus")
                    (not (str/includes? string-query "SpanAttributes"))
                    (not (str/includes? string-query malicious-string-key))
                    (not (str/includes? string-query malicious-string-value)))))

      (reset! filter-calls [])
      (with-redefs
        [jdbc/fetch
         (fn [_ sqlvec _]
           (swap! filter-calls conj sqlvec)
           (if (str/includes? (first sqlvec) "countIf(typedstatus = 3)")
             [coverage-row]
             []))]
        (explorer/typed-span-filtered-traces
         target descriptor-set
         (filter-request malicious-string-key :contains malicious-string-value))
        (explorer/typed-span-filtered-traces
         target descriptor-set
         (filter-request malicious-string-key :prefix "ready")))
      (let [[coverage-call match-call _ prefix-call] @filter-calls]
        (check "hostile string key and values remain parameters"
               [malicious-string-key malicious-string-value "ready"]
               [(second coverage-call)
                (nth match-call (- (count match-call) 2))
                (nth prefix-call (- (count prefix-call) 2))])
        (check "contains and prefix come from library grammar, never caller SQL"
               true
               (and (str/includes? (first match-call) "positionUTF8(")
                    (str/includes? (first prefix-call) "startsWith(")
                    (not (str/includes? (first match-call) malicious-string-key))
                    (not (str/includes? (first match-call) malicious-string-value))))))

    (let [filter-calls (atom [])
          exact-int64 9007199254740993]
      (with-redefs
        [jdbc/fetch
         (fn [connection sqlvec options]
           (swap! filter-calls conj
                  [connection sqlvec options
                   (context/instrumentation-suppressed?)])
           (if (str/includes? (first sqlvec) "countIf(typedstatus = 3)")
             [coverage-row]
             [{:attributevalue exact-int64
               :parentspanid "0000000000000000"
               :servicename "checkout"
               :spanid "1111111111111111"
               :spanname "checkout.remaining"
               :timestampunixnano 1700000000000000001
               :traceid "11111111111111111111111111111111"
               :typedstatus 3}]))]
        (let [result
              (explorer/typed-span-filtered-traces
               target descriptor-set
               (filter-request malicious-key :gte exact-int64))
              equal-result
              (explorer/typed-span-filtered-traces
               target descriptor-set
               (filter-request malicious-key :eq exact-int64))
              upper-result
              (explorer/typed-span-filtered-traces
               target descriptor-set
               (filter-request malicious-key :lt exact-int64))]
          (check "typed Int64 filters preserve values above double precision"
                 [exact-int64 :int64 3
                  {:operator :gte :value exact-int64}]
                 [(get-in result [:matches 0 :attribute-value])
                  (:attribute-type result)
                  (get-in result [:matches 0 :typed-status])
                  (:filter result)])
          (check "typed Int64 filters retain honest availability coverage"
                 {:absent 3
                  :historical-untyped-fallback 5
                  :historical-untyped-unavailable 6
                  :invalid 4 :present-empty 1 :total 21 :valid 2}
                 (:coverage result))
          (check "typed Int64 filters expose only the closed numeric grammar"
                 [{:operator :gte :value exact-int64}
                  {:operator :eq :value exact-int64}
                  {:operator :lt :value exact-int64}]
                 (mapv :filter [result equal-result upper-result]))))
      (let [[_ _ _ coverage-suppressed?] (first @filter-calls)
            [_ [sql & params] options match-suppressed?]
            (second @filter-calls)
            [_ [equal-sql & equal-params]] (nth @filter-calls 3)
            [_ [upper-sql & upper-params]] (nth @filter-calls 5)]
        (check "typed Int64 predicate and caller scalars remain parameters"
               [42 42 1700000000000000000 1700000001000000000
                exact-int64 7]
               params)
        (check "typed Int64 filter SQL is status-valid, bounded, and map-free"
               true
               (and coverage-suppressed? match-suppressed?
                    (= {:max-rows 7} options)
                    (str/includes? sql "FROM otel_traces")
                    (str/includes? sql " >= ?")
                    (str/includes? sql " = 3")
                    (str/includes? sql "max_rows_to_read = 100000")
                    (not (str/includes? sql "SpanAttributes"))
                    (not (str/includes? sql malicious-key))))
        (check "typed Int64 equality and upper bounds stay bound"
               true
               (and (str/includes? equal-sql " = ?")
                    (str/includes? upper-sql " < ?")
                    (= exact-int64 (nth equal-params 4))
                    (= exact-int64 (nth upper-params 4))))))

    (doseq [[row label]
            [[{:attributevalue 7
               :parentspanid "0000000000000000"
               :servicename "checkout"
               :spanid "1111111111111111"
               :spanname "checkout.remaining"
               :timestampunixnano 1700000000000000001
               :traceid "11111111111111111111111111111111"
               :typedstatus 0}
              "historical Int64 fallback cannot become a typed match"]
             [{:attributevalue 7.0
               :parentspanid "0000000000000000"
               :servicename "checkout"
               :spanid "1111111111111111"
               :spanname "checkout.remaining"
               :timestampunixnano 1700000000000000001
               :traceid "11111111111111111111111111111111"
               :typedstatus 3}
              "non-integral Int64 results fail closed"]]]
      (with-redefs
        [jdbc/fetch
         (fn [_ sqlvec _]
           (if (str/includes? (first sqlvec) "countIf(typedstatus = 3)")
             [coverage-row]
             [row]))]
        (check label
               :otel.exporter.chdb.explorer/invalid-typed-result
               (:type
                (thrown-data
                 #(explorer/typed-span-filtered-traces
                   target descriptor-set
                   (filter-request malicious-key :eq 7)))))))

    (let [queries (atom 0)
          invalid-filter-requests
          [[(filter-request malicious-key :prefix 1)
            :otel.exporter.chdb.explorer/unsupported-typed-filter-operator]
           [(filter-request malicious-key :eq 1.0)
            :otel.exporter.chdb.explorer/invalid-typed-filter-value]
           [(filter-request malicious-key :eq 9223372036854775808)
            :otel.exporter.chdb.explorer/invalid-typed-filter-value]
           [(filter-request malicious-key :lt -9223372036854775809)
            :otel.exporter.chdb.explorer/invalid-typed-filter-value]
           [(filter-request "unknown.key" :eq 1)
            :otel.exporter.chdb.explorer/unknown-typed-key]
           [(filter-request "checkout.complete" :contains false)
            :otel.exporter.chdb.explorer/unsupported-typed-filter-operator]
           [(filter-request "checkout.complete" :eq "false")
            :otel.exporter.chdb.explorer/invalid-typed-filter-value]
           [(filter-request malicious-string-key :eq false)
            :otel.exporter.chdb.explorer/invalid-typed-filter-value]
           [(filter-request malicious-string-key :prefix "")
            :otel.exporter.chdb.explorer/invalid-typed-filter-value]
           [(filter-request malicious-string-key :matches "ready")
            :otel.exporter.chdb.explorer/unsupported-typed-filter-operator]
           [(filter-request malicious-string-key :eq
                            (apply str (repeat 257 "x")))
            :otel.exporter.chdb.explorer/invalid-typed-filter-value]
           [(filter-request malicious-string-key :eq "ready" {:limit 0})
            :otel.exporter.chdb.explorer/invalid-bound]
           [(filter-request malicious-string-key :eq "ready" {:limit 101})
            :otel.exporter.chdb.explorer/invalid-bound]
           [(assoc (filter-request malicious-string-key :eq "ready")
                   :sql "SELECT * FROM system.tables")
            :otel.exporter.chdb.explorer/unsupported-request-key]
           [(filter-request malicious-string-key :eq "ready" {:signal :logs})
            :otel.exporter.chdb.explorer/unsupported-typed-signal]]]
      (with-redefs [jdbc/fetch (fn [& _] (swap! queries inc) [])]
        (doseq [[bad expected-type] invalid-filter-requests]
          (check "invalid type/value/operator/bound filters fail before JDBC"
                 expected-type
                 (:type
                  (thrown-data
                   #(explorer/typed-span-filtered-traces
                     target descriptor-set bad)))))
        (check "invalid typed filters execute no query" 0 @queries)))

    (check "bare descriptor vectors have no typed filter authority"
           :otel.exporter.chdb.attribute-registry-installer/unconfirmed-descriptors
           (:type
            (thrown-data
             #(explorer/typed-span-filtered-traces
               target descriptors
               (filter-request malicious-string-key :eq "ready")))))
    (check "typed filter capability cannot cross connection identity"
           :otel.exporter.chdb.attribute-projection/target-mismatch
           (:type
            (thrown-data
             #(explorer/typed-span-filtered-traces
               (atom :other-target) descriptor-set
               (filter-request malicious-string-key :eq "ready")))))

    (with-redefs [jdbc/fetch
                  (fn [_ sqlvec _]
                    (if (str/includes? (first sqlvec)
                                       "countIf(typedstatus = 3)")
                      [(assoc coverage-row :unknownstatus 1 :total 22)]
                      []))]
      (check "unknown physical statuses fail closed"
             :otel.exporter.chdb.explorer/invalid-typed-result
             (:type
              (thrown-data
               #(explorer/typed-span-filtered-traces
                 target descriptor-set
                 (filter-request malicious-string-key :eq "ready"))))))

    (let [queries (atom 0)
          invalid
          [[(request ["unknown.key"])
            :otel.exporter.chdb.explorer/unknown-typed-key]
           [(request [malicious-key malicious-key])
            :otel.exporter.chdb.explorer/duplicate-typed-keys]
           [(request [malicious-key] {:signal :logs})
            :otel.exporter.chdb.explorer/unsupported-typed-signal]
           [(assoc (request [malicious-key]) :table "system.tables")
            :otel.exporter.chdb.explorer/unsupported-request-key]]]
      (with-redefs [jdbc/fetch (fn [& _] (swap! queries inc) [])]
        (doseq [[bad expected-type] invalid]
          (check "unknown, duplicate, wrong-signal, and table input fail closed"
                 expected-type
                 (:type
                  (thrown-data
                   #(explorer/typed-span-values target descriptor-set bad)))))
        (check "invalid typed selections execute no query" 0 @queries)))

    (let [queries (atom 0)
          invalid
          [[(aggregate-request "checkout.complete")
            :otel.exporter.chdb.explorer/unsupported-typed-aggregate-type]
           [(aggregate-request "unknown.key")
            :otel.exporter.chdb.explorer/unknown-typed-key]
           [(aggregate-request malicious-key {:predicate {}})
            :otel.exporter.chdb.explorer/invalid-typed-int64-predicate]
           [(aggregate-request malicious-key {:predicate {:gte 1 :lt 1}})
            :otel.exporter.chdb.explorer/invalid-typed-int64-predicate]
           [(aggregate-request malicious-key
                               {:predicate {:gte 9223372036854775808}})
            :otel.exporter.chdb.explorer/invalid-typed-int64-predicate]
           [(aggregate-request malicious-key {:predicate {:gte 1.0}})
            :otel.exporter.chdb.explorer/invalid-typed-int64-predicate]
           [(aggregate-request malicious-key
                               {:group-by [:service-name :service-name]})
            :otel.exporter.chdb.explorer/invalid-series-vector]
           [(aggregate-request malicious-key {:group-by [:span-name]})
            :otel.exporter.chdb.explorer/unsupported-series-choice]
           [(aggregate-request malicious-key {:aggregates []})
            :otel.exporter.chdb.explorer/invalid-series-vector]
           [(aggregate-request malicious-key {:aggregates [:sum]})
            :otel.exporter.chdb.explorer/unsupported-series-choice]
           [(aggregate-request malicious-key {:aggregates [:count :count]})
            :otel.exporter.chdb.explorer/duplicate-series-choice]
           [(assoc (aggregate-request malicious-key) :sql "SELECT *")
            :otel.exporter.chdb.explorer/unsupported-request-key]
           [(aggregate-request malicious-key {:signal :logs})
            :otel.exporter.chdb.explorer/unsupported-typed-signal]]]
      (with-redefs [jdbc/fetch (fn [& _] (swap! queries inc) [])]
        (doseq [[bad expected-type] invalid]
          (check "invalid typed Int64 aggregate requests fail closed"
                 expected-type
                 (:type
                  (thrown-data
                   #(explorer/typed-span-int64-aggregates
                     target descriptor-set bad)))))
        (check "invalid typed Int64 aggregates execute no query" 0 @queries)))

    (check "bare descriptors have no numeric aggregate authority"
           :otel.exporter.chdb.attribute-registry-installer/unconfirmed-descriptors
           (:type
            (thrown-data
             #(explorer/typed-span-int64-aggregates
               target descriptors (aggregate-request malicious-key)))))
    (check "typed numeric capability cannot cross connection identity"
           :otel.exporter.chdb.attribute-projection/target-mismatch
           (:type
            (thrown-data
             #(explorer/typed-span-int64-aggregates
               (atom :other-target) descriptor-set
               (aggregate-request malicious-key)))))
    (with-redefs [jdbc/fetch
                  (fn [& _] [{:count 1 :min 0 :max 0 :avg ##Inf}])]
      (check "non-finite typed aggregate results fail closed"
             :otel.exporter.chdb.explorer/invalid-typed-result
             (:type
              (thrown-data
               #(explorer/typed-span-int64-aggregates
                 target descriptor-set
                 (aggregate-request malicious-key {:group-by []}))))))

    (check "bare descriptor vectors have no query authority"
           :otel.exporter.chdb.attribute-registry-installer/unconfirmed-descriptors
           (:type
            (thrown-data
             #(explorer/typed-span-values
               target descriptors (request [malicious-key])))))
    (check "typed query capability cannot cross connection identity"
           :otel.exporter.chdb.attribute-projection/target-mismatch
           (:type
            (thrown-data
             #(explorer/typed-span-values
               (atom :other-target) descriptor-set
               (request [malicious-key])))))
    (with-redefs [jdbc/fetch
                  (fn [& _] [{:value "corrupt" :typedstatus 9 :count 1}])]
      (check "unknown persisted status fails instead of becoming fallback"
             :otel.exporter.chdb.explorer/invalid-typed-result
             (:type
              (thrown-data
               #(explorer/typed-span-values
                 target descriptor-set (request [malicious-key]))))))
    (check "legacy generic explorer fields remain available without capability"
           true
           (contains? (set (explorer/supported-fields :spans))
                      :http-request-method))))
