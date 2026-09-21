(ns otel.exporter.chdb.attribute-projection
  "Pure row projection from installer-confirmed typed descriptors."
  (:require [otel.exporter.chdb.attribute-identity :as identity]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.attribute-registry-installer :as installer]))

(def ^:private int64-min -9223372036854775808)
(def ^:private int64-max 9223372036854775807)

(defn- fail! [message type data]
  (throw (ex-info message (assoc data :type type
                                 :attribute-projection/error true))))

(defn- key-string [key]
  (cond (string? key) key
        (keyword? key) (subs (str key) 1)
        :else (str key)))

(defn- confirmed-fields [descriptor-set actual-target]
  (let [{:keys [record] :as evidence}
        (installer/descriptor-set-data descriptor-set)
        issued-target (:target evidence)]
    (when-not (identical? actual-target issued-target)
      (fail! "typed descriptor capability belongs to another export target"
             ::target-mismatch {}))
    (get-in record [:manifest :fields])))

(defn- default-value [type]
  (case type :string "" :boolean false :int64 0))

(defn- valid-value? [type value]
  (case type
    :string (string? value)
    :boolean (boolean? value)
    :int64 (and (integer? value) (<= int64-min value int64-max))
    false))

(defn- projected-value [type values]
  (let [codes registry/status-codes
        fallback (default-value type)]
    (cond
      (empty? values) [fallback (:absent codes)]
      (not= 1 (count values)) [fallback (:invalid codes)]
      (and (= :string type) (= "" (first values)))
      ["" (:present-empty codes)]
      (valid-value? type (first values)) [(first values) (:valid codes)]
      :else [fallback (:invalid codes)])))

(defn confirmed-span-fields
  "Return trace manifest fields after issuer and target identity confirmation."
  [descriptor-set target]
  (let [fields (confirmed-fields descriptor-set target)]
    (when-not (every? identity/trace-attribute-target? fields)
      (fail! "typed trace projection requires a trace-table capability"
             ::signal-mismatch {}))
    fields))

(defn confirmed-log-fields
  "Return log-record manifest fields after capability and target confirmation."
  [descriptor-set target]
  (let [fields (confirmed-fields descriptor-set target)]
    (when-not (every? #(= identity/log-attribute-target
                          (identity/target-of %))
                      fields)
      (fail! "typed log projection requires a log-attribute capability"
             ::signal-mismatch {}))
    fields))

(defn confirmed-gauge-fields
  "Return gauge fields after capability and target confirmation.

  Gauge point, resource, and instrumentation-scope attributes share one
  physical table and one capability."
  [descriptor-set target]
  (let [fields (confirmed-fields descriptor-set target)]
    (when-not (every? #(contains? identity/gauge-attribute-targets
                                   (identity/target-of %))
                      fields)
      (fail! "typed gauge projection requires a gauge-table capability"
             ::signal-mismatch {}))
    fields))

(defn confirmed-sum-fields
  "Return sum fields after capability and target confirmation.

  Sum point, resource, and instrumentation-scope attributes share one
  physical table and one capability."
  [descriptor-set target]
  (let [fields (confirmed-fields descriptor-set target)]
    (when-not (every? #(contains? identity/sum-attribute-targets
                                   (identity/target-of %))
                      fields)
      (fail! "typed sum projection requires a sum-table capability"
             ::signal-mismatch {}))
    fields))

(defn confirmed-histogram-fields
  "Return explicit-histogram fields after capability and target confirmation."
  [descriptor-set target]
  (let [fields (confirmed-fields descriptor-set target)]
    (when-not (every? #(contains? identity/histogram-attribute-targets
                                   (identity/target-of %))
                      fields)
      (fail! "typed histogram projection requires a histogram-table capability"
             ::signal-mismatch {}))
    fields))

(defn- attributes-at [span location]
  (case location
    :resource-attributes (get-in span [:resource :attributes])
    :scope-attributes (get-in span [:scope :attributes])
    :span-attributes (:attributes span)
    nil))

(defn- values-by-key [attributes]
  (reduce (fn [values [key value]]
            (update values (key-string key) (fnil conj []) value))
          {}
          (or attributes {})))

(defn- project-fields [fields attributes-for]
  (let [location-values
        (into {} (map (fn [location]
                        [location (values-by-key (attributes-for location))]))
              (distinct (map :location fields)))]
    (reduce
     (fn [row {:keys [key location physical type]}]
       (let [values (get-in location-values [location key] [])
             [value status] (projected-value type values)]
         (assoc row (:value-column physical) value
                (:status-column physical) status)))
     (sorted-map)
     fields)))

(defn trace-projector
  "Compile one confirmed trace capability into a complete span-row projector.

  Resource, instrumentation-scope, and span maps are selected by each field's
  declared location. Equal keys at different locations remain independent."
  [descriptor-set target]
  (let [fields (confirmed-span-fields descriptor-set target)]
    (fn [span]
      (project-fields fields #(attributes-at span %)))))

(defn log-projector
  "Compile one confirmed log-attribute capability into a log-row projector."
  [descriptor-set target]
  (let [fields (confirmed-log-fields descriptor-set target)]
    (fn [record]
      (project-fields fields #(when (= :log-attributes %) (:attributes record))))))

(defn- metric-context [value]
  ;; The public projector is also useful in pure tests. Preserve its previous
  ;; point-only call shape while the exporter supplies the full context.
  (if (and (contains? value :resource)
           (contains? value :scope)
           (contains? value :point))
    value
    {:point value}))

(defn- metric-projector [fields]
  (fn [value]
    (let [{:keys [resource scope point]} (metric-context value)]
      (project-fields
       fields
       (fn [location]
         (case location
           :resource-attributes (:attributes resource)
           :scope-attributes (:attributes scope)
           :metric-attributes (:attributes point)
           nil))))))

(defn gauge-projector
  "Compile one confirmed gauge capability into a full metric row projector."
  [descriptor-set target]
  (let [fields (confirmed-gauge-fields descriptor-set target)]
    (metric-projector fields)))

(defn sum-projector
  "Compile one confirmed sum capability into a full metric row projector."
  [descriptor-set target]
  (let [fields (confirmed-sum-fields descriptor-set target)]
    (metric-projector fields)))

(defn histogram-projector
  "Compile one confirmed explicit-histogram capability into a metric row projector."
  [descriptor-set target]
  (let [fields (confirmed-histogram-fields descriptor-set target)]
    (metric-projector fields)))

(defn span-projector
  "Compile a legacy span-attribute-only capability into an attribute projector.

  This compatibility entry point preserves the pre-location API. Mixed trace
  capabilities must use `trace-projector` so resource and scope values cannot
  be mistaken for span values."
  [descriptor-set target]
  (let [fields (confirmed-span-fields descriptor-set target)]
    (when-not (every? #(= :span-attributes (:location %)) fields)
      (fail! "legacy span projector cannot consume a mixed trace capability"
             ::location-required {}))
    (fn [attributes]
      (project-fields fields #(when (= :span-attributes %) attributes)))))
