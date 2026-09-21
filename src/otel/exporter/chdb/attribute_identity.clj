(ns otel.exporter.chdb.attribute-identity
  "Closed logical-signal, physical-table, and attribute-location identities.

  This namespace is the canonical routing vocabulary shared by manifest
  compilation, registry planning, and capability consumers. It contains no
  database effects and accepts no caller SQL.")

(def signals #{:spans :logs :metrics})

(def table-signals
  (sorted-map
   "otel_logs" :logs
   "otel_metrics_gauge" :metrics
   "otel_metrics_histogram" :metrics
   "otel_metrics_sum" :metrics
   "otel_traces" :spans))

(def signal-locations
  (sorted-map
   :logs #{:log-attributes :resource-attributes :scope-attributes}
   :metrics #{:metric-attributes :resource-attributes :scope-attributes}
   :spans #{:span-attributes :resource-attributes :scope-attributes}))

(def locations (set (mapcat identity (vals signal-locations))))

(def target-codes
  (sorted-map
   "otel_logs" "lg"
   "otel_metrics_gauge" "mg"
   "otel_metrics_histogram" "mh"
   "otel_metrics_sum" "ms"
   "otel_traces" "tr"))

(def location-codes
  (sorted-map
   :log-attributes "lg"
   :metric-attributes "mt"
   :resource-attributes "rs"
   :scope-attributes "sc"
   :span-attributes "sp"))

(def span-attribute-target
  (sorted-map :location :span-attributes
              :signal :spans
              :table "otel_traces"))

(def log-attribute-target
  (sorted-map :location :log-attributes
              :signal :logs
              :table "otel_logs"))

(def gauge-attribute-target
  "The gauge point-attribute target."
  (sorted-map :location :metric-attributes
              :signal :metrics
              :table "otel_metrics_gauge"))

(def gauge-resource-attribute-target
  "The gauge resource-attribute target."
  (sorted-map :location :resource-attributes
              :signal :metrics
              :table "otel_metrics_gauge"))

(def gauge-scope-attribute-target
  "The gauge instrumentation-scope-attribute target."
  (sorted-map :location :scope-attributes
              :signal :metrics
              :table "otel_metrics_gauge"))

(def sum-attribute-target
  "The sum point-attribute target."
  (sorted-map :location :metric-attributes
              :signal :metrics
              :table "otel_metrics_sum"))

(def sum-resource-attribute-target
  "The sum resource-attribute target."
  (sorted-map :location :resource-attributes
              :signal :metrics
              :table "otel_metrics_sum"))

(def sum-scope-attribute-target
  "The sum instrumentation-scope-attribute target."
  (sorted-map :location :scope-attributes
              :signal :metrics
              :table "otel_metrics_sum"))

(def histogram-attribute-target
  "The explicit-histogram point-attribute target."
  (sorted-map :location :metric-attributes :signal :metrics
              :table "otel_metrics_histogram"))

(def histogram-resource-attribute-target
  "The explicit-histogram resource-attribute target."
  (sorted-map :location :resource-attributes :signal :metrics
              :table "otel_metrics_histogram"))

(def histogram-scope-attribute-target
  "The explicit-histogram instrumentation-scope-attribute target."
  (sorted-map :location :scope-attributes :signal :metrics
              :table "otel_metrics_histogram"))

(def gauge-attribute-targets
  "The complete, table-qualified gauge target set supported by this slice."
  #{gauge-attribute-target gauge-resource-attribute-target
    gauge-scope-attribute-target})

(def sum-attribute-targets
  "The complete, table-qualified sum target set supported by this slice."
  #{sum-attribute-target sum-resource-attribute-target
    sum-scope-attribute-target})

(def histogram-attribute-targets
  "The complete, table-qualified explicit-histogram target set."
  #{histogram-attribute-target histogram-resource-attribute-target
    histogram-scope-attribute-target})

(defn target
  "Return a canonical target tuple, or nil for an invalid combination."
  [signal table location]
  (when (and (contains? signals signal)
             (= signal (get table-signals table))
             (contains? (get signal-locations signal #{}) location))
    (sorted-map :location location :signal signal :table table)))

(defn target-of [value]
  (target (:signal value) (:table value) (:location value)))

(def trace-attribute-targets
  "The closed location-qualified logical targets owned by the trace table."
  (set (map #(target :spans "otel_traces" %)
            [:resource-attributes :scope-attributes :span-attributes])))

(def trace-table-target
  "The one physical authority shared by all location-qualified trace fields."
  (sorted-map :signal :spans :table "otel_traces"))

(def physically-supported-targets
  "Target tuples with complete installer and exporter support today."
  (into (into (into (conj trace-attribute-targets log-attribute-target)
                    histogram-attribute-targets)
              sum-attribute-targets)
        gauge-attribute-targets))

(defn trace-attribute-target? [value]
  (contains? trace-attribute-targets (target-of value)))

(defn physical-target-of
  "Return the closed signal/table authority for a valid logical target."
  [value]
  (when-let [{:keys [signal table]} (target-of value)]
    (sorted-map :signal signal :table table)))

(defn physically-supported? [value]
  (contains? physically-supported-targets (target-of value)))

(defn target-code [value]
  (when-let [{:keys [location table]} (target-of value)]
    (str (get target-codes table) "_" (get location-codes location))))
