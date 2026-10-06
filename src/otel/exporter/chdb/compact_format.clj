(ns otel.exporter.chdb.compact-format
  "Startup-only physical schema confirmation for explicitly requested compact input.
  Not a DDL lock or a schema-freshness proof after startup."
  (:require [clojure.string :as str]
            [jdbc.core :as jdbc]
            [otel.context :as context]))

(def ^:private tables
  #{"otel_traces" "otel_logs" "otel_metrics_gauge" "otel_metrics_sum"
    "otel_metrics_histogram"})

(deftype ConfirmedPlan [connection table columns types]
  Object
  (toString [_] "#<confirmed compact schema>"))

(defn- fail! []
  ;; Neither the failed provider's exception nor observed names/types are kept.
  (throw (ex-info "Compact telemetry schema was not confirmed"
                  {:type ::schema-not-confirmed})))

(defn typed-types [fields]
  (reduce (fn [types {:keys [physical type]}]
            (assoc types
                   (:value-column physical) (case type
                                             :boolean "Bool" :int64 "Int64"
                                             :string "String")
                   (:status-column physical) "UInt8"))
          {} fields))

(defn- normalized-type [type]
  ;; All expected types are closed schema/descriptor spellings without quoted
  ;; text parameters. This only normalizes formatting, not aliases/coercions.
  (when (string? type) (str/replace type #"\s+" "")))

(defn confirm! [connection table columns types]
  (try
    (when-not (and (contains? tables table) (vector? columns) (seq columns)
                   (every? string? columns) (= (count columns) (count (set columns)))
                   (every? #(string? (get types %)) columns))
      (fail!))
    (let [observed (context/with-instrumentation-suppressed
                     (vec (jdbc/fetch connection (str "DESCRIBE TABLE " table))))
          names (mapv :name observed)
          actual (into {} (map (juxt :name :type)) observed)]
      (when-not (and (every? string? names)
                     (= (count names) (count (set names)))
                     (every? (fn [column]
                               (= (normalized-type (get types column))
                                  (normalized-type (get actual column)))) columns))
        (fail!))
      ;; Bind the explicit SQL order, not physical DESCRIBE/map iteration order.
      ;; Additional physical columns are allowed and retain their native defaults.
      ;; Keep the connection capability out of ordinary map printing/encoding.
      (ConfirmedPlan. connection table columns (select-keys types columns)))
    (catch Throwable _ (fail!))))

(defn require-order! [plans connection table columns]
  (let [plan (get plans table)]
    (when-not (and (instance? ConfirmedPlan plan)
                   (identical? connection (.-connection ^ConfirmedPlan plan))
                   (= table (.-table ^ConfirmedPlan plan))
                   (vector? columns) (seq columns)
                   (= columns (.-columns ^ConfirmedPlan plan)))
      (fail!))))
