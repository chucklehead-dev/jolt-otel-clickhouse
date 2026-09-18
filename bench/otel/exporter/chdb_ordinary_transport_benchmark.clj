(ns otel.exporter.chdb-ordinary-transport-benchmark
  (:require [db.jdbc] [clojure.data.json :as json] [clojure.string :as str]
            [jdbc.core :as jdbc] [jdbc.chdb :as driver] [jdbc.chdb.native :as native]
            [jdbc.chdb.durable.backend :as backend]
            [jolt.host :as host] [otel.sdk.export :as export]
            [otel.context :as context]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.attribute-registry-installer :as installer]))

(def batch-size 512)
(def profiles {:benchmark {:warmups 5 :measured 20}
               :semantic-control {:warmups 0 :measured 1}})
(def ^:dynamic *profile* (:benchmark profiles))
(defn copies [] (+ (:warmups *profile*) (:measured *profile*)))
(defn total-rows [] (* 2 batch-size (copies)))
(def base-nanos 1700000000123456789)
(def limit (* 8 1024 1024))
(defn require! [ok] (when-not ok (throw (ex-info "Benchmark control failed" {}))))
(defn schema-failure-diagnostic [data]
  ;; Closed public labels only: never return arbitrary type/message/SQL/data.
  (let [category (case (:type data)
                   :otel.exporter.chdb.schema/invalid-plan :invalid-plan
                   :otel.exporter.chdb.schema/duplicate-version :duplicate-version
                   :otel.exporter.chdb.schema/unknown-version :unknown-version
                   :otel.exporter.chdb.schema/migration-drift :migration-drift
                   :otel.exporter.chdb.schema/nonconsecutive-history :nonconsecutive-history
                   :otel.exporter.chdb.schema/migration-failed :migration-failed
                   :unknown)
        phase (case (:phase data) :statement :statement :record :record :unknown)
        version (:version data) index (:statement-index data)]
    {:category category :phase phase
     :version (if (and (integer? version) (<= 1 version 4)) version :unknown)
     :statement-index (if (and (= phase :statement) (integer? index) (<= 0 index 127))
                        index :unknown)}))

(defn- emit-diagnostic! [& public-fields]
  ;; Evidence I/O failure must not replace setup's original result/Throwable.
  (try (apply println public-fields) (flush) (catch Throwable _ nil)))
(defn digest [text] (#'manifest/sha256 text))

(defn approved []
  (manifest/compile-manifest
   {:dataset-id "ordinary-benchmark" :application-id "trace-transport"
    :lineage "benchmark-v1" :version 1
    :fragments [{:schema manifest/reviewed-fragment-schema :authority :advice
                 :source "advice/ordinary-benchmark.edn"
                 :entries [{:signal :spans :table "otel_traces" :location :span-attributes
                            :key "flag" :type :boolean}
                           {:signal :spans :table "otel_traces" :location :span-attributes
                            :key "count" :type :int64}]}]}))

(defn spans [region]
  (mapv (fn [index]
          {:name region :kind :internal
           :start-time-unix-nano (+ base-nanos index)
           :end-time-unix-nano (+ base-nanos index 1000)
           :span-context {:trace-id (format "%032x" (inc index))
                          :span-id (format "%016x" (inc index))}
           :resource {:attributes {"service.name" "ordinary-benchmark"}}
           :scope {:name "transport-benchmark" :version "1"}
           :attributes {"flag" false "count" index
                        "url" "https://synthetic.invalid/path?q=one?two"
                        "text" "quote\"slash\\newline\n漢字😀"}
           :events [] :links [] :status {:code :unset}})
        (range batch-size)))

(defn columns [fields]
  (into (into schema/clickstack-trace-insert-columns ["EventsJSON" "LinksJSON"])
        (mapcat #(vector (get-in % [:physical :value-column])
                         (get-in % [:physical :status-column])) fields)))

(defn counters []
  ;; Separate coarse snapshots; live heap is NOT a cumulative allocator.
  {:cpu (host/cpu-nanos) :real (host/real-nanos) :gc-count (host/gc-count)
   :gc-real (host/gc-real-nanos) :gc-bytes (host/gc-bytes)
   :live-heap (host/bytes-allocated) :memory (host/current-memory-bytes)})

(defn sample [f]
  (let [measured (:measured *profile*)
        before (counters)
        times (mapv (fn [index]
                      (let [start (System/nanoTime)
                            _ (f)
                            elapsed (- (System/nanoTime) start)]
                        ;; Outside the timed interval, but perturbs interbatch
                        ;; scheduling/GC. Preserve partial observations on failure.
                        (println :sample-observation :index index
                                 :batch-rows batch-size :latency-nanos elapsed)
                        (flush)
                        elapsed)) (range measured))
        after (counters)
        ordered (vec (sort times))
        total (reduce + 0 times)]
    (require! (and (= measured (count times)) (every? pos? times)))
    {:count measured :batch-rows batch-size :latency-nanos times
     :p50-latency-nanos (nth ordered (dec (quot (+ measured 1) 2)))
     :aggregate-rows-per-second (/ (double (* measured batch-size 1000000000)) total)
     :counters-before before :counters-after after
     :allocation-qualified false :tail-target-qualified false}))

(defn setup [connection]
  (emit-diagnostic! :benchmark-setup :schema :enter)
  (schema/ensure-schema! connection)
  (emit-diagnostic! :benchmark-setup :schema :return)
  (emit-diagnostic! :benchmark-setup :installer :enter)
  (let [installation (installer/install-approved!
                      (backend/memory-backend) (approved)
                      {:target connection
                       :observe-columns #(vector {:signal :spans :table "otel_traces"
                                                  :columns (into {} (map (juxt :name :type))
                                                                 (jdbc/fetch connection "DESCRIBE TABLE otel_traces"))})
                       :execute-ddl! #(jdbc/execute! connection %)})
        capability (:descriptor-set installation)]
    (emit-diagnostic! :benchmark-setup :installer :return)
    (require! (= :active (:status installation)))
    {:projector (projection/trace-projector capability connection)
     :fields (projection/confirmed-span-fields capability connection)
     :capability capability}))

(defn payload-control [rows cols]
  (let [legacy (apply str (#'exporter/chunks rows))
        candidate (#'exporter/ordinary-payload cols rows)
        bytes (alength (.getBytes candidate "UTF-8"))]
    (require! (and (= legacy candidate) (= batch-size (count rows))
                   (pos? bytes) (<= bytes limit)))
    {:payload candidate :bytes bytes :digest (digest candidate)}))

(defn writer! [route path]
  (require! (contains? #{:legacy :candidate} route))
  ;; Persistent path is the FIRST native storage identity in this process.
  (with-open [connection (jdbc/connection (str "chdb:" path))]
    (require! (= "26.7.2.1" (:engine (jdbc/fetch-one connection "SELECT version() AS engine"))))
    (println :sql-engine :qualified-26.7.2.1)
    (let [{:keys [projector fields capability]} (setup connection)
          cols (columns fields)
          owner (exporter/exporter {:connection connection :create-schema? false
                                    :signals #{:spans} :typed-span-descriptors capability})]
      (try
        (doseq [region ["serialization" "preencoded"]]
          (let [fixture (spans region)
                rows (mapv #(#'exporter/span-row % projector) fixture)
                control (payload-control rows cols)
                payload (:payload control)
                insert (if (= region "serialization")
                         (if (= route :legacy)
                           #(#'exporter/insert-json-rows! connection "insert into otel_traces"
                                                          (map (fn [span] (#'exporter/span-row span projector)) fixture))
                           #(let [encoded (#'exporter/ordinary-payload cols
                                            (map (fn [span] (#'exporter/span-row span projector)) fixture))]
                              (context/with-instrumentation-suppressed
                                (driver/insert-json-rows! connection "otel_traces" cols encoded))))
                         (if (= route :legacy)
                           #(context/with-instrumentation-suppressed
                              (jdbc/execute! connection (str "insert into otel_traces FORMAT JSONEachRow\n" payload)))
                           #(context/with-instrumentation-suppressed
                              (driver/insert-json-rows! connection "otel_traces" cols payload))))]
            (println :payload-control :region (keyword region) :rows batch-size
                     :bytes (:bytes control) :sha256 (:digest control)
                     :columns-sha256 (digest (pr-str cols)))
            (dotimes [_ (:warmups *profile*)] (insert))
            (println :region-start :route route :region (keyword region))
            (flush)
            (println :region-result :route route :region (keyword region) :result (sample insert))))
        (require! (= (total-rows) (:n (jdbc/fetch-one connection "SELECT count() AS n FROM otel_traces"))))
        (println :writer-green :route route :rows (total-rows))
        (finally (export/shutdown-exporter! owner))))))

(defn sql-column [column]
  (require! (boolean (re-matches #"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*" column)))
  (str "`" column "`"))

(defn reader! [path]
  ;; No schema creation, installation or mutations in the fresh reader.
  (with-open [connection (jdbc/connection (str "chdb:" path))]
    (let [fields (:fields (approved))
          cols (remove #{"Timestamp"} (columns fields))
          query (str "SELECT " (str/join ", " (map sql-column cols))
                     ", toString(toUnixTimestamp64Nano(Timestamp)) AS ticks, count() AS copies"
                     " FROM otel_traces GROUP BY ALL ORDER BY SpanName, TraceId")
          actual (jdbc/fetch connection query {:max-rows 1025})
          expected
          (vec (for [region ["preencoded" "serialization"]
                     [index span] (map-indexed vector (spans region))]
                 (let [generic (#'exporter/span-row span nil)
                       promoted (into {} (mapcat (fn [field]
                                                  [[(get-in field [:physical :value-column])
                                                    (get-in span [:attributes (:key field)])]
                                                   [(get-in field [:physical :status-column]) 3]]) fields))
                       row (merge generic promoted)]
                   (assoc (into {} (map (fn [column]
                                         [(keyword (str/lower-case column)) (get row column)]) cols))
                          :ticks (str (+ base-nanos index)) :copies (copies)))))]
      (require! (= 1024 (count actual)))
      ;; Full projected physical rows plus independently derived tick/count
      ;; oracles; physical Boolean/Int64 types are checked independently too.
      (require! (= expected actual))
      (doseq [field fields]
        (require! (= (if (= :boolean (:type field)) "Bool" "Int64")
                     (:physical_type
                      (jdbc/fetch-one connection
                       (str "SELECT toTypeName(" (sql-column (get-in field [:physical :value-column]))
                            ") AS physical_type FROM otel_traces LIMIT 1"))))))
      (println :fresh-reader-green :groups 1024 :rows (total-rows)
               :full-rows-equal true :exact-nanos true :typed-values-status true))))

(defn registry-readback-status [rows]
  (case (count rows)
    0 :absent
    1 :exact-one
    :duplicate))

(defn registry-readback! [path]
  "Test-only recovery observation for a terminal v1 registry-record failure.

  This is deliberately not a recovery operation: it opens a fresh connection
  and performs one bounded SELECT.  In particular it must not call schema
  migration, setup, DDL, the attribute installer, an exporter, or retry the
  failed writer.  The closed result is evidence only; it does not decide that
  an ambiguous native result was acknowledged."
  (let [status
        (try
          (with-open [connection (jdbc/connection (str "chdb:" path))]
            (registry-readback-status
             (jdbc/fetch connection
                         "SELECT Version FROM otel_schema_migrations WHERE Version=1")))
          (catch Throwable _ :unavailable))]
    ;; Keep the readback receipt closed and payload-free.
    (println :registry-readback :version 1 :status status)
    (flush)))

(defn -main [mode route path & [profile-name]]
  (try
    (native/ensure-loaded!)
    (require! (= "26.7.3" (native/chdb-version)))
    (println :native-package :qualified-26.7.3)
    (let [profile-key (case profile-name
                        nil :benchmark "benchmark" :benchmark
                        "semantic-control" :semantic-control :invalid)]
      (require! (contains? profiles profile-key))
      (binding [*profile* (get profiles profile-key)]
        (println :controlled-profile profile-key :warmups (:warmups *profile*)
                 :measured (:measured *profile*))
        (case mode
          "writer" (writer! (case route "legacy" :legacy "candidate" :candidate :invalid) path)
          "reader" (reader! path)
          "registry-readback" (registry-readback! path)
          (require! false))))
    (catch Throwable error
      (println :benchmark-red :class
               (cond (instance? java.sql.SQLException error) :sql-exception
                     (instance? clojure.lang.ExceptionInfo error) :exception-info
                     (instance? java.lang.IllegalArgumentException error) :illegal-argument
                     :else :other))
      (try
        (let [{:keys [category phase version statement-index]}
              (schema-failure-diagnostic (ex-data error))]
          (emit-diagnostic! :benchmark-red-diagnostic :category category :phase phase
                            :version version :statement-index statement-index))
        (catch Throwable _
          (emit-diagnostic! :benchmark-red-diagnostic :category :unknown :phase :unknown
                            :version :unknown :statement-index :unknown)))
      (System/exit 1))))
