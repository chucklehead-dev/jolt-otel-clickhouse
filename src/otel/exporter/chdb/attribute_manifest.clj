(ns otel.exporter.chdb.attribute-manifest
  "Pure compilation of reviewed OTel attribute hints into stable storage plans.

  This namespace never connects to a database or observes telemetry. A compiled
  manifest is descriptive input for a later, separately authorized installer."
  (:require [clojure.string :as str]
            [otel.attribute-schema :as attribute-schema])
  (:import [java.security MessageDigest]))

(def manifest-schema "jolt-otel-clickhouse.attribute-manifest/v1")
(def reviewed-fragment-schema "jolt-otel-clickhouse.reviewed-attributes/v1")

(def authorities
  "Closed provenance authorities accepted for explicitly reviewed fragments."
  #{:semantic-convention :advice :runtime-reviewed})

(def locations
  "Closed attribute locations supported by the first manifest format."
  #{:span-attributes :resource-attributes :scope-attributes
    :log-attributes :metric-attributes})

(def clickhouse-types
  "The only application attribute types promotable by the first format."
  {:string "String" :boolean "Bool" :int64 "Int64"})

(def ^:private max-int64 9223372036854775807)
(def ^:private max-fragments 4096)
(def ^:private max-entries 65536)
(def ^:private max-key-length 256)
(def ^:private max-identifier-length 63)
(def ^:private binding-pattern #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
(def ^:private sha256-pattern #"[0-9a-f]{64}")

(def ^:private authority-rank
  {:semantic-convention 0 :advice 1 :source-inference 2 :runtime-reviewed 3})

(def ^:private signal-location
  {:span :span-attributes
   :resource :resource-attributes
   :log :log-attributes
   :metric :metric-attributes})

(def ^:private diagnostic-codes
  #{:conflicting-inference :dynamic-key :invalid-inference
    :unknown-inference :unsupported-type})

(def ^:private inference-kinds
  #{:literal :constructor :cast :dynamic})

(def ^:private location-code
  {:span-attributes "sp"
   :resource-attributes "rs"
   :scope-attributes "sc"
   :log-attributes "lg"
   :metric-attributes "mt"})

(defn- fail! [message type data]
  (throw (ex-info message (assoc data :type type :attribute-manifest/error true))))

(defn- exact-keys? [value expected]
  (and (map? value) (= expected (set (keys value)))))

(declare canonical-value)

(defn- canonical-map [value]
  (into (sorted-map)
        (map (fn [[key item]] [key (canonical-value item)]))
        value))

(defn- canonical-value [value]
  (cond
    (map? value) (canonical-map value)
    (vector? value) (mapv canonical-value value)
    (sequential? value) (mapv canonical-value value)
    :else value))

(defn- canonical-edn [value]
  (str (pr-str (canonical-value value)) "\n"))

(defn- bytes->hex [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn- sha256 [text]
  (bytes->hex
   (.digest (MessageDigest/getInstance "SHA-256")
            (.getBytes text "UTF-8"))))

(defn- valid-binding-string? [value]
  (and (string? value) (boolean (re-matches binding-pattern value))))

(defn- validate-binding! [{:keys [dataset-id application-id lineage version] :as value}]
  (when-not (and (valid-binding-string? dataset-id)
                 (valid-binding-string? application-id)
                 (valid-binding-string? lineage)
                 (integer? version) (pos? version) (<= version max-int64))
    (fail! "attribute manifest has an invalid deployment binding"
           ::invalid-binding
           {:binding (select-keys value [:dataset-id :application-id
                                         :lineage :version])}))
  value)

(defn- valid-key? [value]
  (and (string? value) (not (empty? value))
       (<= (count value) max-key-length)))

(defn- canonical-source? [value]
  (and (string? value)
       (try (= value (attribute-schema/canonical-source value))
            (catch Throwable _ false))))

(defn- provenance-sort-key [item]
  [(get authority-rank (:authority item) 99)
   (:source item) (or (:line item) 0) (str (:kind item))])

(defn- canonical-provenance [items]
  (->> items
       (map canonical-value)
       distinct
       (sort-by provenance-sort-key)
       vec))

(defn- explicit-fragment [fragment]
  (when-not (exact-keys? fragment #{:schema :authority :source :entries})
    (fail! "reviewed attribute fragment must use the closed v1 envelope"
           ::invalid-reviewed-fragment {:fragment fragment}))
  (let [{:keys [schema authority source entries]} fragment]
    (when-not (= reviewed-fragment-schema schema)
      (fail! "reviewed attribute fragment has an unsupported schema"
             ::invalid-reviewed-fragment {:schema schema}))
    (when-not (contains? authorities authority)
      (fail! "reviewed attribute fragment has an unsupported authority"
             ::invalid-authority {:authority authority}))
    (attribute-schema/canonical-source source)
    (when-not (and (vector? entries) (<= (count entries) max-entries))
      (fail! "reviewed attribute fragment entries must be a bounded vector"
             ::invalid-reviewed-fragment {:source source}))
    {:declarations
     (mapv
      (fn [entry]
        (when-not (exact-keys? entry #{:location :key :type})
          (fail! "reviewed attribute entry must use the closed v1 shape"
                 ::invalid-reviewed-entry {:source source :entry entry}))
        (let [{:keys [location key type]} entry]
          (when-not (contains? locations location)
            (fail! "reviewed attribute entry has an unsupported location"
                   ::invalid-location {:source source :location location}))
          (when-not (valid-key? key)
            (fail! "reviewed attribute entry has an invalid key"
                   ::invalid-key {:source source :key key}))
          (when-not (contains? clickhouse-types type)
            (fail! "reviewed attribute entry has an unsupported type"
                   ::invalid-type {:source source :key key :type type}))
          {:location location :key key :type type
           :provenance [(sorted-map :authority authority :source source)]}))
      entries)
     :diagnostics []}))

(defn- inference-provenance [evidence]
  (canonical-provenance
   (map (fn [{:keys [source line kind]}]
          (cond-> (sorted-map :authority :source-inference
                              :kind kind
                              :source source)
            line (assoc :line line)))
        evidence)))

(defn- diagnostic [code location key types provenance]
  (cond-> (sorted-map :code code :location location
                      :provenance provenance)
    key (assoc :key key)
    types (assoc :types (vec types))))

(defn- inferred-entry [{:keys [signal key types unknown? invalid? conflict?
                               evidence]}]
  (let [location (get signal-location signal)
        provenance (inference-provenance evidence)
        code (cond
               conflict? :conflicting-inference
               invalid? :invalid-inference
               unknown? :unknown-inference
               (not= 1 (count types)) :conflicting-inference
               (not (contains? clickhouse-types (first types))) :unsupported-type)]
    (if code
      {:declarations []
       :diagnostics [(diagnostic code location key types provenance)]}
      {:declarations [{:location location :key key :type (first types)
                       :provenance provenance}]
       :diagnostics []})))

(defn- inferred-fragment [fragment]
  (let [{:keys [entries dynamic-keys] :as fragment}
        (attribute-schema/validate fragment)
        converted (map inferred-entry entries)
        dynamic-diagnostics
        (mapv (fn [{:keys [signal evidence]}]
                (diagnostic :dynamic-key (get signal-location signal) nil nil
                            (inference-provenance evidence)))
              dynamic-keys)]
    {:declarations (vec (mapcat :declarations converted))
     :diagnostics (vec (concat (mapcat :diagnostics converted)
                              dynamic-diagnostics))}))

(defn- consume-fragment [fragment]
  (cond
    (= attribute-schema/schema-id (:schema fragment))
    (inferred-fragment fragment)

    (= reviewed-fragment-schema (:schema fragment))
    (explicit-fragment fragment)

    :else
    (fail! "attribute manifest fragment has an unsupported schema"
           ::unsupported-fragment-schema {:schema (:schema fragment)})))

(defn- fragment-item-count [fragment]
  (+ (if (vector? (:entries fragment)) (count (:entries fragment)) 0)
     (if (vector? (:dynamic-keys fragment))
       (count (:dynamic-keys fragment)) 0)))

(defn- sanitize-key [key]
  (let [value (-> key str/lower-case
                  (str/replace #"[^a-z0-9]+" "_")
                  (str/replace #"^_+" "")
                  (str/replace #"_+$" ""))
        value (if (empty? value) "field" value)]
    (subs value 0 (min 28 (count value)))))

(defn- field-identity [binding location key type]
  (sorted-map :application-id (:application-id binding)
              :dataset-id (:dataset-id binding)
              :key key
              :lineage (:lineage binding)
              :location location
              :type type
              :version (:version binding)))

(defn- physical-identifiers [location key digest]
  (let [stem (str (get location-code location) "_" (sanitize-key key) "_"
                  (subs digest 0 16))]
    (sorted-map :status-column (str "as_" stem)
                :value-column (str "av_" stem))))

(defn- compiled-field [binding {:keys [location key type provenance]}]
  (let [identity (field-identity binding location key type)
        digest (sha256 (canonical-edn identity))]
    (sorted-map
     :clickhouse-type (get clickhouse-types type)
     :id (str "attribute_" (subs digest 0 20))
     :identity identity
     :key key
     :location location
     :physical (physical-identifiers location key digest)
     :provenance (canonical-provenance provenance)
     :type type)))

(defn- merge-declarations [binding declarations]
  (->> declarations
       (group-by (juxt :location :key))
       (map
        (fn [[[location key] group]]
          (let [types (->> group (map :type) distinct (sort-by str) vec)]
            (when (> (count types) 1)
              (fail! "reviewed attribute declarations disagree on type"
                     ::type-conflict
                     {:location location :key key :types types
                      :declarations (->> group (map canonical-value)
                                         (sort-by pr-str) vec)}))
            (compiled-field
             binding
             {:location location :key key :type (first types)
              :provenance (mapcat :provenance group)}))))
       (sort-by (juxt (comp str :location) :key (comp str :type)))
       vec))

(defn- diagnostic-sort-key [item]
  [(str (:location item)) (or (:key item) "") (str (:code item)) (pr-str item)])

(defn- manifest-payload [binding fields diagnostics]
  (sorted-map
   :application-id (:application-id binding)
   :dataset-id (:dataset-id binding)
   :diagnostics (->> diagnostics (map canonical-value) distinct
                     (sort-by diagnostic-sort-key) vec)
   :fields fields
   :lineage (:lineage binding)
   :schema manifest-schema
   :version (:version binding)))

(defn compile-manifest
  "Compile reviewed and source-inferred fragments into a canonical storage plan.

  The deployment binding is trusted configuration. Inference with unknown,
  invalid, conflicting, or unsupported types becomes an explicit diagnostic and
  is never promoted. Conflicting reviewed declarations fail closed."
  [{:keys [fragments] :as input}]
  (when-not (exact-keys? input #{:dataset-id :application-id :lineage
                                :version :fragments})
    (fail! "attribute manifest input must use the closed v1 envelope"
           ::invalid-input {:input input}))
  (validate-binding! input)
  (when-not (and (vector? fragments) (<= (count fragments) max-fragments))
    (fail! "attribute manifest fragments must be a bounded vector"
           ::invalid-input {:fragments fragments}))
  (let [item-count (reduce + 0 (map fragment-item-count fragments))]
    (when (> item-count max-entries)
      (fail! "attribute manifest contains too many source declarations"
             ::too-many-entries {:count item-count})))
  (let [parts (mapv consume-fragment fragments)
        declarations (vec (mapcat :declarations parts))]
    (when (> (count declarations) max-entries)
      (fail! "attribute manifest contains too many promoted declarations"
             ::too-many-entries {:count (count declarations)}))
    (let [payload (manifest-payload input
                                    (merge-declarations input declarations)
                                    (mapcat :diagnostics parts))]
      (assoc payload :checksum (sha256 (canonical-edn payload))))))

(defn- valid-provenance? [item]
  (and (map? item)
       (canonical-source? (:source item))
       (if (= :source-inference (:authority item))
         (and (contains? #{#{:authority :kind :source}
                            #{:authority :kind :line :source}}
                          (set (keys item)))
              (contains? inference-kinds (:kind item))
              (or (not (contains? item :line))
                  (and (integer? (:line item)) (pos? (:line item)))))
         (and (exact-keys? item #{:authority :source})
              (contains? authorities (:authority item))))))

(defn- valid-diagnostic? [item]
  (and (map? item)
       (contains? diagnostic-codes (:code item))
       (contains? locations (:location item))
       (vector? (:provenance item))
       (not (empty? (:provenance item)))
       (= (:provenance item) (canonical-provenance (:provenance item)))
       (every? valid-provenance? (:provenance item))
       (if (= :dynamic-key (:code item))
         (exact-keys? item #{:code :location :provenance})
         (and (exact-keys? item #{:code :location :key :provenance :types})
              (valid-key? (:key item))
              (vector? (:types item))
              (not (empty? (:types item)))))))

(defn validate-manifest
  "Validate a compiled manifest's closed envelope, stable identifiers and digest."
  [manifest]
  (when-not (exact-keys? manifest #{:schema :dataset-id :application-id
                                   :lineage :version :fields :diagnostics
                                   :checksum})
    (fail! "compiled attribute manifest must use the closed v1 envelope"
           ::invalid-manifest {:manifest manifest}))
  (validate-binding! manifest)
  (when-not (= manifest-schema (:schema manifest))
    (fail! "compiled attribute manifest has an unsupported schema"
           ::invalid-manifest {:schema (:schema manifest)}))
  (when-not (and (vector? (:fields manifest))
                 (vector? (:diagnostics manifest))
                 (<= (count (:fields manifest)) max-entries)
                 (<= (count (:diagnostics manifest)) max-entries)
                 (string? (:checksum manifest))
                 (re-matches sha256-pattern (:checksum manifest)))
    (fail! "compiled attribute manifest has invalid collections or checksum"
           ::invalid-manifest {}))
  (doseq [{:keys [id identity location key type clickhouse-type physical
                  provenance] :as field}
          (:fields manifest)]
    (let [digest (sha256 (canonical-edn identity))]
      (when-not (and (exact-keys? field #{:clickhouse-type :id :identity :key
                                         :location :physical :provenance :type})
                     (contains? locations location)
                     (valid-key? key)
                     (contains? clickhouse-types type)
                     (= clickhouse-type (get clickhouse-types type))
                     (= identity (field-identity manifest location key type))
                     (= id (str "attribute_" (subs digest 0 20)))
                     (= physical (physical-identifiers location key digest))
                     (every? #(<= (count %) max-identifier-length)
                             (vals physical))
                     (vector? provenance) (not (empty? provenance))
                     (= provenance (canonical-provenance provenance))
                     (every? valid-provenance? provenance))
        (fail! "compiled attribute field is invalid"
               ::invalid-manifest-field {:field field}))))
  (when-not (= (:fields manifest)
               (->> (:fields manifest)
                    distinct
                    (sort-by (juxt (comp str :location) :key (comp str :type)))
                    vec))
    (fail! "compiled attribute fields are not canonical"
           ::invalid-manifest {:section :fields}))
  (when-not (and (every? valid-diagnostic? (:diagnostics manifest))
                 (= (:diagnostics manifest)
                    (->> (:diagnostics manifest) distinct
                         (sort-by diagnostic-sort-key) vec)))
    (fail! "compiled attribute diagnostics are not canonical"
           ::invalid-manifest {:section :diagnostics}))
  (let [payload (dissoc manifest :checksum)
        expected (sha256 (canonical-edn payload))]
    (when-not (= expected (:checksum manifest))
      (fail! "compiled attribute manifest checksum does not match its payload"
             ::checksum-mismatch
             {:expected expected :actual (:checksum manifest)})))
  manifest)

(defn render
  "Return the canonical UTF-8 EDN representation of a compiled manifest."
  [manifest]
  (canonical-edn (validate-manifest manifest)))
