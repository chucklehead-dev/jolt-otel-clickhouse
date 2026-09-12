(ns otel.exporter.chdb.attribute-projection
  "Pure span-row projection from installer-confirmed typed descriptors."
  (:require [otel.exporter.chdb.attribute-registry :as registry]
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
  "Return manifest fields only after issuer and target identity confirmation."
  [descriptor-set target]
  (confirmed-fields descriptor-set target))

(defn span-projector
  "Compile an installer-confirmed descriptor capability into a row projector.

  The returned function accepts raw span attributes and returns only the
  library-owned physical value/status columns."
  [descriptor-set target]
  (let [fields (confirmed-span-fields descriptor-set target)]
    (fn [attributes]
      (let [values-by-key
            (reduce (fn [values [key value]]
                      (update values (key-string key) (fnil conj []) value))
                    {}
                    (or attributes {}))]
        (reduce
         (fn [row {:keys [key physical type]}]
           (let [values (get values-by-key key [])
                 [value status] (projected-value type values)]
             (assoc row (:value-column physical) value
                    (:status-column physical) status)))
         (sorted-map)
         fields)))))
