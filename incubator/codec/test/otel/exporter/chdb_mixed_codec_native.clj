(ns otel.exporter.chdb-mixed-codec-native
  "Functional direct-exporter gate; separate writer and fresh reader processes."
  (:require [db.jdbc] [clojure.edn :as edn] [clojure.string :as str]
            [jdbc.core :as jdbc] [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local]
            [jdbc.chdb.native :as native]
            [jdbc.chdb.json-each-row :as row-encoder]
            [jdbc.chdb.owned-statement :as owned]
            [otel.exporter.chdb :as e]
            [otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
            [otel.exporter.chdb-mixed-codec-test :as inputs]
            [otel.sdk.export :as export] [otel.sdk.logs :as logs]))

(defn ensure! [condition label]
  (when-not condition (throw (ex-info "Mixed codec native gate failed" {:label label}))))
(defn options [root phase]
  (let [scratch (str root "/scratch-" phase)]
    (.mkdirs (java.io.File. scratch))
    {:namespace-backend (local/local-backend (str root "/objects"))
     :object-id "mixed-signal-codec" :scratch-parent scratch :database "otel"}))
(defn epoch-nanos [value]
  (if (integer? value) value
    (let [instant (java.time.Instant/parse value)]
      (+ (* (.getEpochSecond instant) 1000000000) (.getNano instant)))))
(defn normalize-expected [table row]
  (if (= table "otel_traces")
    (-> row (update "Timestamp" epoch-nanos)
        (update "Events.Timestamp" #(mapv epoch-nanos %)))
    (if (= table "otel_logs") (update row "Timestamp" epoch-nanos) row)))
(defn quoted [name] (str "`" (str/replace name "`" "``") "`"))
(defn select-column [table name]
  (let [column (quoted name)]
    (str (cond
           (and (contains? #{"otel_traces" "otel_logs"} table) (= name "Timestamp"))
           (str "toUnixTimestamp64Nano(" column ")")
           (and (= table "otel_traces") (= name "Events.Timestamp"))
           (str "arrayMap(x -> toUnixTimestamp64Nano(x), " column ")")
           (and (str/starts-with? table "otel_metrics_") (contains? #{"TimeUnix" "StartTimeUnix"} name))
           (str "toUnixTimestamp(" column ")")
           :else column)
         " AS " column)))
(defn canonical-cell [value]
  ;; JSON has one numeric domain; ClickHouse writes small integral Float64s
  ;; without '.0'. Do not round large integers or erase negative-zero evidence.
  (cond
    (and (float? value) (<= -9007199254740992.0 value 9007199254740992.0)
         (== value (Math/floor value))
         (not (and (zero? value) (str/starts-with? (str value) "-")))) (long value)
    (map? value) (into {} (map (fn [[key cell]] [key (canonical-cell cell)])) value)
    (vector? value) (mapv canonical-cell value)
    :else value))
(defn verify-rows! [connection expected]
  (ensure! (= (canonical-cell 2.0) (canonical-cell 2)) :numeric-spelling-control)
  (ensure! (not= (canonical-cell 9007199254740993) (canonical-cell 9007199254740992.0)) :integer-precision-control)
  (ensure! (not= (canonical-cell false) (canonical-cell "false")) :boolean-shape-control)
  (ensure! (not= (canonical-cell -0.0) (canonical-cell 0.0)) :negative-zero-control)
  (doseq [[table rows] expected]
    (let [columns (vec (sort (keys (first rows))))
          sql (str "SELECT " (str/join ", " (map #(select-column table %) columns))
                   " FROM " table " SETTINGS output_format_json_quote_64bit_integers=0")
          ;; JDBC normalizes result labels to lowercase keywords. Preserve the
          ;; exact physical column names in the oracle without losing values.
          actual (mapv (fn [row]
                         (into {} (map (fn [column]
                                         [column (get row (keyword (str/lower-case column)))]) columns)))
                       (jdbc/fetch connection sql))]
      (ensure! (= (count columns) (count (distinct (map str/lower-case columns)))) :unambiguous-result-labels)
      (when-not (= (frequencies (map canonical-cell rows)) (frequencies (map canonical-cell actual)))
        (println :synthetic-row-shape :expected (count rows) :actual (count actual)
                 :keys (vec (keys (first actual))))
        (println :synthetic-physical-differences table
                 (vec (take 3 (for [key columns
                                    :when (not= (get (first rows) key) (get (first actual) key))]
                                {:column key :expected (get (first rows) key) :actual (get (first actual) key)})))))
      (ensure! (= (frequencies (map canonical-cell rows)) (frequencies (map canonical-cell actual)))
               [:full-physical-rows table]))))

(defn write! [root wire]
  (ensure! (not (.exists (java.io.File. (str root "/objects")))) :fresh-store-required)
  (with-open [connection (jdbc/connection
                          (durable/writer-dbspec
                            (assoc (options root "writer") :owner "mixed" :instance "writer"
                                   :owned-compact-stream? true)))]
    (let [{:keys [capability projector]} (fixture/setup connection)
          spans (with-redefs [fixture/batch-size 8]
                  (mapv (fn [span]
                          (let [event {:name "native-λ😀"
                                       :timestamp-unix-nano (:start-time-unix-nano span)
                                       :attributes (sorted-map "flag" false "count" 9)}]
                            (assoc span :events [event])))
                        (fixture/spans "mixed-native")))
          records [(assoc inputs/log-record :timestamp-unix-nano fixture/base-nanos)]
          resource {:attributes {"service.name" "mixed-native"}}
          expected (with-bindings {#'e/*timestamp-wire* (if (= wire :raw-ticks) :raw-ticks :iso-utc)
                                   #'e/*json-backend* :configured}
                     (merge {"otel_traces" (mapv #(normalize-expected "otel_traces" (#'e/span-row % projector)) spans)
                             "otel_logs" (mapv #(normalize-expected "otel_logs" (#'e/log-row % nil)) records)}
                            (into {} (for [kind [:gauge :sum :histogram]]
                                       [(str "otel_metrics_" (name kind))
                                        (get (#'e/metric-row-groups resource (inputs/collected kind) nil) kind)]))))
          writes (atom []) actual-owned durable/execute-owned-and-flush!
          actual-string durable/execute-and-flush!
          _ (ensure! (= 8 (count spans)) :fixture-span-count)
          _ (ensure! (= 12 (reduce + (map count (vals expected)))) :fixture-total-count)
          owner (e/exporter
                  (merge inputs/options
                         {:connection connection :signals inputs/all-signals :create-schema? false
                          :datetime64-wire wire :typed-span-descriptors capability
                          :experimental-span-payload-cache? true :experimental-span-flat-map-cache? true
                          :experimental-span-nested-fragment-cache? true}))]
      (try
        (with-redefs [row-encoder/open-encoder (fn [& _] (throw (ex-info "Legacy native writer loaded" {})))
                      durable/execute-owned-and-flush!
                      (fn [conn statement]
                        (ensure! (owned/statement? statement) :owned-span)
                        (swap! writes conj :owned) (actual-owned conn statement))
                      durable/execute-and-flush!
                      (fn [conn sql]
                        (ensure! (string? sql) :configured-sql)
                        (swap! writes conj :string) (actual-string conn sql))]
          (ensure! (true? (export/export-spans! owner spans)) :span-result)
          (ensure! (true? (logs/export-logs! owner records)) :log-result)
          (doseq [kind [:gauge :sum :histogram]]
            (ensure! (true? (export/export-metrics! owner resource (inputs/collected kind))) [:metric-result kind])))
        (ensure! (= [:owned :string :string :string :string] @writes) :native-routing)
        (spit (str root "/expected.edn") (pr-str expected))
        (verify-rows! connection expected)
        (spit (str root "/writer.edn")
              (pr-str {:passed true :wire wire :routing @writes :rows (reduce + (map count (vals expected)))
                       :native-version (native/chdb-version) :scope :direct-exporter-not-sdk-or-http}))
        (finally
          (export/shutdown-exporter! owner)
          (logs/shutdown-log-exporter! owner)
          (export/shutdown-metric-exporter! owner))))))
(defn read! [root expected-file]
  (let [expected (edn/read-string (slurp (or expected-file (str root "/expected.edn"))))]
    (with-open [connection (jdbc/connection (durable/snapshot-dbspec (dissoc (options root "reader") :database)))]
      (verify-rows! connection expected)
      (spit (str root "/reader.edn")
            (pr-str {:passed true :rows (reduce + (map count (vals expected)))
                     :full-fields-and-multiplicity true :fresh-process true})))))
(defn -main [mode root & [wire]]
  (native/ensure-loaded!)
  (ensure! (= "26.9.0" (native/chdb-version)) :native-version)
  (case mode "writer" (write! root (keyword wire)) "reader" (read! root wire)
        (throw (ex-info "Unknown native gate mode" {})))
  (println :mixed-native-qualified mode))
