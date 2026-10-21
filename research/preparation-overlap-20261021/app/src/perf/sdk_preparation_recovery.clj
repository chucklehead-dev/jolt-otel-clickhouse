(ns perf.sdk-preparation-recovery
  "Source-only independent physical oracle. Not linked into the timed app."
  (:require [clojure.edn :as edn] [clojure.string :as str]
            [jdbc.core :as jdbc] [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local]
            [otel.exporter.chdb :as exporter] [otel.sdk.export :as sdk]
            [perf.public-pipeline :as fixture])
  (:import [java.security MessageDigest]))

(defn- ensure! [ok label] (fixture/ensure! ok label))

(defn- options [root phase]
  {:namespace-backend (local/local-backend (str root "/objects"))
   :object-id "typed-compact-prefix" :scratch-parent (str root "/scratch-" phase)})

(defn- quoted [column]
  (ensure! (and (string? column) (not (str/includes? column "`"))) :fixed-schema-column)
  (str "`" column "`"))

(defn- physical-snapshot [root n]
  (.mkdirs (java.io.File. (str root "/scratch-reader")))
  (with-open [connection (jdbc/connection (durable/snapshot-dbspec (options root "reader")))]
    (let [columns (mapv #(select-keys % [:name :type]) (jdbc/fetch connection "DESCRIBE TABLE otel_traces"))
          selection (mapv (fn [{column :name}]
                            (case column
                              "Timestamp" "toUnixTimestamp64Nano(Timestamp) AS Timestamp"
                              "Events.Timestamp" (str "arrayMap(x -> toUnixTimestamp64Nano(x), "
                                                      "`Events.Timestamp`) AS `Events.Timestamp`")
                              (quoted column))) columns)
          sql (str "SELECT " (str/join ", " selection)
                   ", count() AS copies FROM otel_traces GROUP BY ALL ORDER BY SpanId")]
      {:columns columns :rows (vec (jdbc/fetch connection sql {:max-rows (inc n)}))})))

(defn- create-oracle! [root n]
  (ensure! (not (.exists (java.io.File. (str root "/objects")))) :fresh-oracle-store)
  (.mkdirs (java.io.File. (str root "/scratch-writer")))
  (with-open [connection
              (jdbc/connection
                (durable/writer-dbspec
                  (assoc (options root "writer") :owner "oracle" :instance "oracle-1" :database "otel")))]
    (let [capability (fixture/setup! connection)
          target (exporter/exporter
                   {:connection connection :durable? true :signals #{:spans} :create-schema? false
                    :typed-span-descriptors capability :datetime64-wire :raw-ticks
                    :max-insert-bytes (* 16 1024 1024)
                    ;; Explicit ordinary JSONEachRow/data.json control: neither
                    ;; the compact encoder nor its optional codec is selected.
                    :insert-format :json-each-row :json-backend :configured})]
      (try
        (ensure! (true? (sdk/export-spans! target (fixture/fixture n))) :ordinary-oracle-export)
        (ensure! (true? (sdk/shutdown-exporter! target)) :oracle-exporter-release)
        (finally (sdk/shutdown-exporter! target))))))

(defn- canonical [value]
  ;; Preserve container kinds and sort map entries independently of hash-map
  ;; iteration. Primitive EDN values retain their exact types/Unicode/integers.
  (cond
    (map? value) [::map (mapv (fn [[k v]] [(canonical k) (canonical v)])
                             (sort-by (comp pr-str key) value))]
    (sequential? value) [::sequence (mapv canonical value)]
    :else value))

(defn- digest [value]
  (apply str (map #(format "%02x" (bit-and (int %) 255))
                 (.digest (MessageDigest/getInstance "SHA-256")
                          (.getBytes (pr-str (canonical value)) "UTF-8")))))

(defn- snapshot-receipt! [root n copies destination]
  (let [{:keys [columns rows]} (physical-snapshot root n)]
    (ensure! (= n (count rows)) :exact-physical-row-groups)
    (ensure! (every? #(= copies (:copies %)) rows) :per-row-multiplicity)
    (spit destination
      (pr-str {:groups n :copies copies :columns columns
               :canonical-physical-row-sha256 (digest (mapv #(dissoc % :copies) rows))}))))

(defn -main [root phase]
  (ensure! (and root (.isAbsolute (java.io.File. root))) :absolute-owned-root)
  (let [writer (edn/read-string (slurp (str root "/writer.edn")))
        n (:batch-size writer) copies (+ (:warmups writer) (:rounds writer))
        _ (ensure! (and (integer? n) (<= 1 n 10000) (<= 3 copies 69)) :bounded-recovery-geometry)
        oracle-root (str root "/oracle-independent")]
    ;; chDB intentionally allows ONE storage lifetime per process, even after
    ;; close. Invoke these phases in separate processes, including each reader.
    (case phase
      "oracle" (create-oracle! oracle-root n)
      "actual" (snapshot-receipt! root n copies (str root "/actual-physical.edn"))
      "expected" (snapshot-receipt! oracle-root n 1 (str root "/oracle-physical.edn"))
      "compare"
      (let [actual (edn/read-string (slurp (str root "/actual-physical.edn")))
            expected (edn/read-string (slurp (str root "/oracle-physical.edn")))]
        (ensure! (= (:columns expected) (:columns actual)) :all-physical-column-types)
        (ensure! (= n (:groups expected) (:groups actual)) :physical-groups)
        (ensure! (= copies (:copies actual)) :actual-multiplicity)
        (ensure! (= 1 (:copies expected)) :oracle-multiplicity)
        (ensure! (= (:canonical-physical-row-sha256 expected)
                    (:canonical-physical-row-sha256 actual)) :independent-all-fields-and-exact-ticks)
        (ensure! (= (* n copies) (:stored-rows writer)) :writer-count-agrees)
        (spit (str root "/reader.edn")
          (pr-str {:passed true :rows (* n copies) :groups n :copies copies
                   :comparison :sha256-canonical-all-decoded-physical-fields
                   :all-physical-types-equal true :timestamps-and-event-timestamps-exact true
                   :independent-ordinary-json-oracle true}))
        (println :sdk-preparation-recovery-confirmed (:arm writer) :rows (* n copies)))
      (throw (ex-info "oracle, actual, expected or compare phase required" {})))))
