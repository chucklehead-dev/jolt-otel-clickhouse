(ns otel.exporter.log-durable-ab
  "Public log-exporter to Durable writer/fresh-reader qualification."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.local-posix :as local]
            [jdbc.chdb.native :as native]
            [jolt.host :as host]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(def ^:private batch-size 512)
(def ^:private confirmed #{:committed :reconciled})
(def ^:private sql-prefix
  (str "insert into otel_logs ("
       (str/join ", " schema/clickstack-log-insert-columns)
       ") FORMAT JSONEachRow\n"))

(defn- check! [condition label]
  (when-not condition (throw (ex-info (str "Log Durable qualification: " label) {}))))

(defn- sha256 [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (str value) "UTF-8"))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn- fixture []
  ;; Closed, shared-map SDK shape used by the encoder-only probe. This source
  ;; is selected from the candidate bench tree in *both* launcher arms.
  (let [resource {"service.name" "encoder-probe" "region" "us-east-2"}
        scope {"library" "bench"}
        attributes {"component" "encoder-probe" "enabled" true}]
    (mapv (fn [n]
            {:timestamp-unix-nano (+ 1700000000000000000 n)
             :trace-id "0123456789abcdef0123456789abcdef"
             :span-id (str "span-" n)
             :trace-flags 1 :severity-text "INFO" :severity-number 9
             :body (str "steady-log-" n) :event-name "benchmark"
             :attributes attributes
             :resource {:attributes resource}
             :scope {:name "probe" :version "1" :attributes scope}})
          (range batch-size))))

(def ^:private selection
  (str "SELECT "
       (str/join ", "
                 (map (fn [column]
                        (if (= column "Timestamp")
                          "toString(toUnixTimestamp64Nano(Timestamp)) AS Timestamp"
                          (str "`" column "`")))
                      schema/clickstack-log-insert-columns))
       " FROM otel_logs ORDER BY TraceId, SpanId"))

(defn- canonical-row [row]
  (walk/postwalk #(if (map? %) (into (sorted-map) %) %) row))

(defn- digest-rows [rows]
  ;; No telemetry or full result is written to a receipt. An ordered digest
  ;; covers every selected physical column and every duplicate copy.
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (doseq [row rows]
      (.update digest (.getBytes (str (pr-str (canonical-row row)) "\n") "UTF-8")))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn- expected-row [record]
  (let [row (#'exporter/log-row record nil)]
    (into {} (map (fn [[column value]]
                    [(keyword (str/lower-case column))
                     (if (= column "Timestamp") (str value) value)])
                  row))))

(defn- expected-digest [records copies]
  (let [ordered (sort-by (juxt :traceid :spanid) (map expected-row records))]
    (digest-rows (mapcat #(repeat copies %) ordered))))

(defn- percentile [ordered fraction]
  (nth ordered (dec (long (Math/ceil (* fraction (count ordered)))))))

(defn- counters []
  {:cpu-nanos (host/cpu-nanos)
   :real-nanos (host/real-nanos)
   :gc-count (host/gc-count)
   :gc-real-nanos (host/gc-real-nanos)
   :gc-bytes (host/gc-bytes)})

(defn- counter-delta [before after]
  (into {} (map (fn [[key value]] [key (- (get after key) value)]) before)))

(defn- options [root phase]
  {:namespace-backend (local/local-backend (str root "/objects"))
   :object-id "log-qualification"
   :scratch-parent (str root "/scratch-" phase)
   :owner "log-qualification" :instance "writer-1" :database "otel"
   :lease-ttl-ms 900000 :heartbeat-interval-ms 300000})

(defn- writer! [root arm samples]
  (native/ensure-loaded!)
  (check! (= "26.7.3" (native/chdb-version)) "native release")
  (check! (= "Chez Scheme Version 10.4.1" (host/scheme-version)) "Chez pin")
  (require 'jdbc.chdb.durable.wal)
  (check! (true? ((ns-resolve 'jdbc.chdb.durable.wal 'native-line-enabled?)))
          "guarded native WAL selector active")
  (check! (contains? #{"A" "B"} arm) "arm")
  (check! (and (integer? samples) (<= 1 samples 500)) "bounded sample count")
  (let [records (fixture)
        _ (check! (= batch-size (count records)) "fixture size")
        generic-payload (#'exporter/json-each-row-payload
                         (map #(#'exporter/log-row % nil) records))
        expected-sql (str sql-prefix generic-payload)
        store (backend/object-backend
               (local/local-backend (str root "/objects")) "log-qualification")]
    (check! (nil? (backend/get-bytes store "head.json")) "fresh object store")
    (with-open [connection (jdbc/connection (durable/writer-dbspec (options root "writer")))]
      (schema/ensure-schema! connection)
      (check! (contains? confirmed (:status (durable/checkpoint! connection)))
              "schema checkpoint")
      (let [target (exporter/exporter {:connection connection :durable? true
                                       :create-schema? false :signals #{:logs}})
            captured (atom [])]
        (try
          ;; Admission spy: public exporter call, but no DB/WAL mutation. It
          ;; establishes identical SQL bytes before any timed operation.
          (with-redefs [durable/execute-and-flush!
                        (fn [_ sql] (swap! captured conj sql) {:status :committed})]
            (check! (true? (logs/export-logs! target records)) "public SQL preflight"))
          (check! (= [expected-sql] @captured) "generic/public SQL byte parity")
          (when (= arm "B")
            ;; The schema-fenced compiler calls log-row at plan construction.
            ;; Force both plans first, then prove the *public* path does not
            ;; construct a physical row for this fixture.
            (check! (ifn? (some-> (ns-resolve 'otel.exporter.chdb 'untyped-log-encoder)
                                var-get force)) "row encoder plan")
            (check! (ifn? (some-> (ns-resolve 'otel.exporter.chdb 'untyped-log-batch-encoder)
                                var-get force)) "batch encoder plan")
            (with-redefs [exporter/log-row
                          (fn [& _] (throw (ex-info "candidate fell back to row map" {})))
                          durable/execute-and-flush!
                          (fn [_ sql]
                            (check! (= expected-sql sql) "candidate activation SQL")
                            {:status :committed})]
              (check! (true? (logs/export-logs! target records))
                      "candidate production encoder activation")))
          (let [warmups 3
                calls (+ warmups samples)
                op (fn []
                     (check! (true? (logs/export-logs! target records))
                             "confirmed public log export"))
                _ (dotimes [_ warmups] (op))
                _ (System/gc)
                before (counters)
                latencies (mapv (fn [_]
                                  (let [start (System/nanoTime)]
                                    (op)
                                    (- (System/nanoTime) start)))
                                (range samples))
                after (counters)
                sorted (vec (sort latencies))
                expected-count (* calls batch-size)
                actual-count (:n (jdbc/fetch-one connection
                                                 "SELECT count() AS n FROM otel_logs"))
                expected (expected-digest records calls)
                actual (digest-rows (jdbc/fetch connection selection))
                barrier (durable/flush! connection)
                report {:arm arm :samples samples :warmups warmups
                        :batch-size batch-size :rows expected-count
                        :fixture-sha256 (sha256 (pr-str records))
                        :sql-sha256 (sha256 expected-sql)
                        :sql-bytes (alength (.getBytes expected-sql "UTF-8"))
                        :production-encoder-witnessed? (= arm "B")
                        :p50-nanos (percentile sorted 0.50)
                        :p99-nanos (percentile sorted 0.99)
                        :aggregate-rows-per-second
                        (/ (* 1.0e9 samples batch-size) (reduce + latencies))
                        :latencies-nanos latencies
                        :counters (counter-delta before after)
                        :expected-digest expected :writer-digest actual
                        :empty-final-flush? (= :empty (:status barrier))}]
            (check! (= expected-count actual-count) "writer row count")
            (check! (= expected actual) "writer full-row digest")
            (check! (:empty-final-flush? report) "settled writer")
            (spit (str root "/writer-report.edn") (str (pr-str report) "\n"))
            (spit (str root "/public-sql.sha256") (str (:sql-sha256 report) "\n"))
            (println :log-writer-green :arm arm :samples samples :rows expected-count
                     :sql-bytes (:sql-bytes report)))
          (finally (export/shutdown-exporter! target)))))))

(defn- reader! [root]
  (let [writer (edn/read-string (slurp (str root "/writer-report.edn")))]
    (with-open [connection (jdbc/connection
                            (durable/snapshot-dbspec (options root "reader")))]
      (let [actual-count (:n (jdbc/fetch-one connection
                                             "SELECT count() AS n FROM otel_logs"))
            digest (digest-rows (jdbc/fetch connection selection))
            report {:rows actual-count :digest digest
                    :expected-digest (:expected-digest writer)
                    :writer-digest (:writer-digest writer)
                    :equal? (= (:expected-digest writer) digest)}]
        (check! (= (:rows writer) actual-count) "fresh reader row count")
        (check! (:equal? report) "fresh reader full-row digest")
        (check! (= (:writer-digest writer) digest) "fresh reader/writer parity")
        (spit (str root "/reader-report.edn") (str (pr-str report) "\n"))
        (spit (str root "/reader.digest") (str digest "\n"))
        (println :log-reader-green :rows actual-count :full-row-equal true)))))

(defn -main [phase root arm sample-text]
  (case phase
    "writer" (writer! root arm (Long/parseLong sample-text))
    "reader" (reader! root)
    (throw (ex-info "Expected writer or reader phase" {}))))
