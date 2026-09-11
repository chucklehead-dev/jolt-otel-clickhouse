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

(def physically-supported-targets
  "Target tuples with complete installer/export/query support today."
  #{span-attribute-target})

(defn target
  "Return a canonical target tuple, or nil for an invalid combination."
  [signal table location]
  (when (and (contains? signals signal)
             (= signal (get table-signals table))
             (contains? (get signal-locations signal #{}) location))
    (sorted-map :location location :signal signal :table table)))

(defn target-of [value]
  (target (:signal value) (:table value) (:location value)))

(defn physically-supported? [value]
  (contains? physically-supported-targets (target-of value)))

(defn target-code [value]
  (when-let [{:keys [location table]} (target-of value)]
    (str (get target-codes table) "_" (get location-codes location))))
