(ns otel.exporter.chdb-durable-typed-native-test
  "Explicit two-process Durable typed acceptance; never a canonical runner replacement."
  (:require [db.jdbc] [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.local-posix :as local]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.native :as native]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.sdk.export :as export] [otel.sdk.logs :as logs]))

(def last-check (atom :not-started))
(def observed-checks (atom 0))
(defn- check! [label expected actual]
  (reset! last-check label)
  (swap! observed-checks inc)
  (when-not (= expected actual)
    ;; Never retain actual telemetry, SQL, paths or arbitrary exception data.
    (throw (ex-info "Durable typed native assertion failed" {:assertion label})))
  (println :ok label))

(defn- selector [signal]
  {:dataset-id "durable-native" :application-id (name signal)
   :lineage "native-v1" :version 1})

(defn- table [signal]
  (case signal
    :spans "otel_traces"
    :logs "otel_logs"
    :metric-gauge "otel_metrics_gauge"
    :metric-sum "otel_metrics_sum"))
(defn- observe [connection signal]
  [{:signal (if (#{:metric-gauge :metric-sum} signal) :metrics signal)
    :table (table signal)
    :columns (into {} (map (juxt :name :type))
                   (jdbc/fetch connection (str "DESCRIBE TABLE " (table signal))))}])

(defn- approved [signal]
  (manifest/compile-manifest
    (assoc (selector signal)
           :fragments [{:schema manifest/reviewed-fragment-schema
                        :authority :advice :source "advice/native-test.edn"
                        :entries (mapv (fn [[key type]]
                                         {:signal signal :table (table signal)
                                          :location (if (= signal :spans) :span-attributes :log-attributes)
                                          :key key :type type})
                                       [["flag" :boolean] ["count" :int64]])}])))

(defn- metric-approved [signal entries]
  (manifest/compile-manifest
   (assoc (selector signal)
          :fragments [{:schema manifest/reviewed-fragment-schema
                       :authority :advice :source "advice/durable-metric-native-test.edn"
                       :entries (mapv (fn [[location key type]]
                                        {:signal :metrics :table (table signal)
                                         :location location :key key :type type})
                                      entries)}])))

(defn- gauge-approved []
  (metric-approved :metric-gauge
                   [[:resource-attributes "resource.ready" :boolean]
                    [:scope-attributes "scope.workers" :int64]
                    [:metric-attributes "queue.ready" :boolean]
                    [:metric-attributes "queue.count" :int64]]))

(defn- sum-approved []
  (metric-approved :metric-sum
                   [[:metric-attributes "request.success" :boolean]
                    [:metric-attributes "request.count" :int64]]))

(def ticks [0 1 1700000000123456789 0])
(def inputs [["a-zero" {"flag" false "count" 0}]
             ["b-min" {"flag" true "count" -9223372036854775808}]
             ["c-max" {"count" 9223372036854775807}]
             ["d-invalid" {"count" 9223372036854775808N}]])

(defn- span-record [index]
  (let [[name attributes] (nth inputs index) tick (nth ticks index)]
    {:name name :kind :internal :start-time-unix-nano tick :end-time-unix-nano (inc tick)
     :span-context {:trace-id "11111111111111111111111111111111" :span-id "2222222222222222"}
     :resource {:attributes {}} :scope {:name "native"} :attributes attributes
     :events (if (zero? index) (mapv #(hash-map :name "tick" :timestamp-unix-nano %) (take 3 ticks)) [])
     :links [] :status {:code :unset}}))

(defn- log-record [index]
  (let [[name attributes] (nth inputs index) tick (nth ticks index)]
    {:timestamp-unix-nano (if (= index 2) 0 tick)
     :observed-time-unix-nano (if (= index 2) tick 0)
     :body name :severity-text "INFO" :severity-number 9
     :resource {:attributes {}} :scope {:name "native"} :attributes attributes}))

(defn- metric-batch []
  [{:scope {:name "durable-native" :attributes {"scope.workers" 7
                                                  "scope.generic" "kept-generic"}}
    :metrics [{:type :gauge :name "durable.typed.gauge" :description "" :unit "1"
               :data-points [{:value 2.0 :time-unix-nano 1700000000000000000
                              :attributes {"queue.ready" false "queue.count" 9223372036854775807
                                           "point.generic" "kept-generic"}}]}
              {:type :sum :name "durable.typed.sum" :description "" :unit "1"
               :temporality :cumulative :monotonic? true
               :data-points [{:value 3.0 :time-unix-nano 1700000000000000000
                              :attributes {"request.success" false "request.count" 9223372036854775807
                                           "point.generic" "kept-generic"}}]}]}])

(defn- counts [connection]
  (mapv #(-> (jdbc/fetch-one connection (str "SELECT count() AS n FROM " %)) :n)
        ["otel_traces" "otel_logs" "otel_metrics_gauge" "otel_metrics_sum" "otel_metrics_histogram"]))

(defn- verify! [connection capability signal]
  (let [fields ((if (= signal :spans) projection/confirmed-span-fields projection/confirmed-log-fields)
                 capability connection)
        physical (fn [key] (:physical (first (filter #(= key (:key %)) fields))))
        c (physical "count") f (physical "flag")
        rows (jdbc/fetch connection
                         (str "SELECT " (if (= signal :spans) "SpanName" "Body") " AS label, "
                              (if (= signal :spans) "SpanAttributes" "LogAttributes") " AS generic, "
                              "toString(toUnixTimestamp64Nano(Timestamp)) AS ticks, "
                              "toString(`" (:value-column c) "`) AS value, "
                              "toTypeName(`" (:value-column c) "`) AS physical_type, "
                              "`" (:status-column c) "` AS status, "
                              "`" (:value-column f) "` AS flag, "
                              "`" (:status-column f) "` AS flag_status FROM " (table signal) " ORDER BY label"))]
    (check! :labels (mapv first inputs) (mapv :label rows))
    (check! :timestamp-nanos (mapv str ticks) (mapv :ticks rows))
    (check! :typed-counts ["0" "-9223372036854775808" "9223372036854775807" "0"] (mapv :value rows))
    (check! :count-types (vec (repeat 4 "Int64")) (mapv :physical_type rows))
    (check! :count-statuses [3 3 3 4] (mapv :status rows))
    (check! :flags [[false 3] [true 3] [false 1] [false 1]] (mapv #(vector (:flag %) (:flag_status %)) rows))
    (check! :generic-maps [{"flag" "false" "count" "0"}
                           {"flag" "true" "count" "-9223372036854775808"}
                           {"count" "9223372036854775807"} {"count" "9223372036854775808N"}]
            (mapv :generic rows))))

(defn- metric-pair [row fields key]
  (let [physical (:physical (first (filter #(= key (:key %)) fields)))]
    [(get row (keyword (:value-column physical)))
     (get row (keyword (:status-column physical)))]))

(defn- verify-metrics! [connection gauge-capability sum-capability]
  (let [gauge-fields (projection/confirmed-gauge-fields gauge-capability connection)
        sum-fields (projection/confirmed-sum-fields sum-capability connection)
        gauge-row (jdbc/fetch-one connection
                                  "SELECT ResourceAttributes AS resource, ScopeAttributes AS scope, Attributes AS attributes, * FROM otel_metrics_gauge WHERE MetricName='durable.typed.gauge'")
        sum-row (jdbc/fetch-one connection
                                "SELECT Attributes AS attributes, * FROM otel_metrics_sum WHERE MetricName='durable.typed.sum'")]
    (check! :metric-gauge-recovered
            [{"resource.ready" "false" "resource.generic" "kept-generic"}
             {"scope.workers" "7" "scope.generic" "kept-generic"}
             {"queue.ready" "false" "queue.count" "9223372036854775807"
              "point.generic" "kept-generic"}
             [[false 3] [7 3] [false 3] [9223372036854775807 3]]]
            [(:resource gauge-row) (:scope gauge-row) (:attributes gauge-row)
             [(metric-pair gauge-row gauge-fields "resource.ready")
              (metric-pair gauge-row gauge-fields "scope.workers")
              (metric-pair gauge-row gauge-fields "queue.ready")
              (metric-pair gauge-row gauge-fields "queue.count")]])
    (check! :metric-sum-recovered
            [{"request.success" "false" "request.count" "9223372036854775807"
              "point.generic" "kept-generic"}
             [[false 3] [9223372036854775807 3]]]
            [(:attributes sum-row)
             [(metric-pair sum-row sum-fields "request.success")
              (metric-pair sum-row sum-fields "request.count")]])))

(defn- wait-file! [path timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond (.isFile (java.io.File. path)) true
            (>= (System/nanoTime) deadline)
            (throw (ex-info "Bounded reader handshake expired" {:assertion :handshake-timeout}))
            :else (do (Thread/sleep 25) (recur))))))

(defn run! [phase root]
  (native/ensure-loaded!)
  (check! :actual-package "26.7.3" (native/chdb-version))
  (let [namespace (local/local-backend (str root "/objects"))
        telemetry (backend/object-backend namespace "telemetry")
        catalog (backend/object-backend namespace "typed-catalog")
        snapshot #(control/read-head-read-only! telemetry)
        options {:namespace-backend namespace :object-id "telemetry"
                 :scratch-parent (str root "/scratch-" phase)}]
    (case phase
      "writer"
      (with-open [connection (jdbc/connection
                               (durable/writer-dbspec
                                 (merge options {:owner "typed-native" :instance "writer-1" :database "otel"
                                                 :lease-ttl-ms 900000 :heartbeat-interval-ms 300000})))]
        (schema/ensure-schema! connection)
        (let [install (fn [signal declaration]
                        (let [result (installer/install-approved! catalog declaration
                                        {:target connection :observe-columns #(observe connection signal)
                                         :execute-ddl! #(jdbc/execute! connection %)})]
                          (check! :installation-active :active (:status result))
                          (:descriptor-set result)))
              spans (install :spans (approved :spans))
              records (install :logs (approved :logs))
              gauges (install :metric-gauge (gauge-approved))
              sums (install :metric-sum (sum-approved))]
          (check! :schema-checkpoint :committed (:status (durable/checkpoint! connection)))
          (let [primary (atom nil)
                writer (exporter/exporter {:connection connection :durable? true :create-schema? false
                                          :signals #{:spans :logs :metrics}
                                          :typed-span-descriptors spans
                                          :typed-log-descriptors records
                                          :typed-gauge-descriptors gauges
                                          :typed-sum-descriptors sums})]
            (try
              (check! :spans-accepted true (export/export-spans! writer (mapv span-record (range 4))))
              (check! :logs-accepted true (logs/export-logs! writer (mapv log-record (range 4))))
              (check! :metrics-accepted true
                      (export/export-metrics! writer
                                              {:attributes {"resource.ready" false
                                                            "resource.generic" "kept-generic"}}
                                              (metric-batch)))
              (check! :writer-counts [4 4 1 1 0] (counts connection))
              (let [head (:head (snapshot))]
                (check! :base-present true (some? (get-in head ["manifest" "base"])))
                (check! :wal-present true (boolean (seq (get-in head ["manifest" "wal"])))))
              (doseq [invalid [-1 9223372036854775808N]]
                (let [before (snapshot) before-counts (counts connection)]
                  (check! :later-bad-span false (export/export-spans! writer [(span-record 0) (assoc (span-record 1) :start-time-unix-nano invalid)]))
                  (check! :later-bad-observed false (logs/export-logs! writer [(log-record 0) (assoc (log-record 1) :timestamp-unix-nano 0 :observed-time-unix-nano invalid)]))
                  (check! :later-bad-event false (export/export-spans! writer [(span-record 0) (assoc (span-record 1) :events [{:name "invalid" :timestamp-unix-nano invalid}])]))
                  (check! :invalid-counts-unchanged before-counts (counts connection))
                  (check! :invalid-head-and-etag-unchanged before (snapshot))))
              (spit (str root "/wal-ready") "ready\n")
              (println :wal-ready)
              (wait-file! (str root "/reader-done") 120000)
              (catch Throwable error (reset! primary error) (throw error))
              (finally
                ;; Independent shutdown attempts; an exception cannot skip the other facade.
                (let [a (try (export/shutdown-exporter! writer) (catch Throwable _ false))
                      b (try (logs/shutdown-log-exporter! writer) (catch Throwable _ false))
                      c (try (export/shutdown-metric-exporter! writer) (catch Throwable _ false))]
                  (if @primary
                    (println :secondary-cleanup-confirmed (= [true true true] [a b c]))
                    (check! :signal-shutdowns [true true true] [a b c]))))))))
      "reader"
      (do
       (with-open [connection (jdbc/connection (durable/snapshot-dbspec options))]
        (let [head (:head (snapshot))]
          (check! :reader-base-present true (some? (get-in head ["manifest" "base"])))
          (check! :reader-wal-present true (boolean (seq (get-in head ["manifest" "wal"])))))
        (check! :reader-counts [4 4 1 1 0] (counts connection))
        (doseq [signal [:spans :logs]]
          (let [result (installer/acquire-active! catalog (selector signal)
                         {:target connection :observe-columns #(observe connection signal)})]
            (check! :reacquisition-active :active (:status result))
            (verify! connection (:descriptor-set result) signal)))
        (let [gauges (installer/acquire-active! catalog (selector :metric-gauge)
                                                {:target connection :observe-columns #(observe connection :metric-gauge)})
              sums (installer/acquire-active! catalog (selector :metric-sum)
                                              {:target connection :observe-columns #(observe connection :metric-sum)})]
          (check! :metric-reacquisition-active [:active :active]
                  [(:status gauges) (:status sums)])
          (verify-metrics! connection (:descriptor-set gauges) (:descriptor-set sums)))
        (check! :event-nanoseconds ["0" "1" "1700000000123456789"]
                (:ticks (jdbc/fetch-one connection "SELECT arrayMap(x -> toString(toUnixTimestamp64Nano(x)), `Events.Timestamp`) AS ticks FROM otel_traces WHERE notEmpty(`Events.Timestamp`)"))))
        ;; Acknowledge only after the reader connection has retired.
        (spit (str root "/reader-done") "done\n")
        (println :reader-verified)))))

(defn -main [phase root]
  (try (run! phase root)
       (println :observed-checks @observed-checks)
       (catch Throwable _
         ;; Values are assigned exclusively by internal fixed-label checks.
         (println :durable-native-failed :last-check @last-check
                  :observed-checks @observed-checks)
         (System/exit 1))))
