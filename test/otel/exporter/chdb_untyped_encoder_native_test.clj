(ns otel.exporter.chdb-untyped-encoder-native-test
  "Small writer/fresh-reader recovery gate; each phase must use a new process."
  (:require [clojure.edn :as edn] [clojure.string :as str] [db.jdbc] [jdbc.core :as jdbc]
            [jolt.process :as process]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.sdk.export :as export]))

(def spans
  (mapv (fn [n]
          {:name (str "unicode-λ😀-" n) :start-time-unix-nano n :end-time-unix-nano (+ n 7)
           :span-context {:trace-id (str n) :span-id (str n)}
           :resource {:attributes {"service.name" "unicode-λ😀" "enabled" true}}
           :scope {:name "gate" :version "1"} :kind :server
           :attributes (if (= n 3) {"nested" [true 42]} {"number" n "flag" true})
           :events [{:timestamp-unix-nano n :name "event-λ" :attributes {"x" n}}]
           :links (if (= n 3) [{:span-context {:trace-id "linked" :span-id "id"}
                               :attributes {"relation" "prior"}}] [])}) [1 2 3]))

(def selection
  "SELECT TraceId, SpanId, SpanName, Duration, SpanAttributes, EventsJSON, LinksJSON, arrayMap(x -> toString(toUnixTimestamp64Nano(x)), `Events.Timestamp`) AS event_ticks, `Events.Name`, `Events.Attributes`, `Links.TraceId`, `Links.SpanId`, `Links.TraceState`, `Links.Attributes` FROM otel_traces ORDER BY TraceId, SpanId")

(defn check! [x label] (when-not x (throw (ex-info label {}))))
(defn -main [phase root]
  (let [opts {:namespace-backend (local/local-backend (str root "/objects"))
              :object-id "telemetry" :scratch-parent (str root "/scratch-" phase)}]
    (case phase
      "writer"
      (with-open [connection (jdbc/connection (durable/writer-dbspec
                                               (assoc opts :owner "encoder-gate" :instance "one" :database "otel"))) ]
        (schema/ensure-schema! connection)
        (check! (contains? #{:committed :reconciled} (:status (durable/checkpoint! connection))) "schema checkpoint")
        (let [target (exporter/exporter {:connection connection :durable? true :create-schema? false :signals #{:spans}})
              eligible (subvec spans 0 2) fallback (subvec spans 2)
              legacy-payload (#'exporter/json-each-row-payload (mapv #(#'exporter/span-row % nil) spans))]
          (check! (every? #'exporter/untyped-span-eligible? eligible) "fully eligible event/scalar/empty-link batch")
          (check! (not (#'exporter/untyped-span-eligible? (first fallback))) "distinct nonempty-link fallback")
          (jdbc/execute! connection "CREATE TABLE otel_traces_legacy AS otel_traces")
          (jdbc/execute! connection (str "insert into otel_traces_legacy FORMAT JSONEachRow\n" legacy-payload))
          (check! (contains? #{:committed :reconciled} (:status (durable/flush! connection))) "legacy baseline publication")
          (check! (ifn? (force @#'exporter/untyped-span-encoder)) "production plan available")
          (with-redefs [exporter/span-row (fn [& _] (throw (ex-info "eligible batch fell back" {})))]
            (check! (true? (export/export-spans! target eligible)) "eligible public export uses encoder"))
          (check! (true? (export/export-spans! target fallback)) "fallback public export")
          (check! (= :empty (:status (durable/flush! connection))) "published before return")
          (let [rows (vec (jdbc/fetch connection selection))]
            (check! (= rows (vec (jdbc/fetch connection (str/replace selection "FROM otel_traces " "FROM otel_traces_legacy "))))
                    "native selected rows equal separately materialized legacy baseline")
            (check! (= 3 (count rows)) "writer row count")
            (check! (= (mapv :name spans) (mapv :spanname rows)) "writer Unicode names")
            (check! (= [7 7 7] (mapv :duration rows)) "writer exact durations")
            (spit (str root "/writer.edn") (pr-str rows)))
          (export/shutdown-exporter! target)))
      "reader"
      (with-open [connection (jdbc/connection (durable/snapshot-dbspec opts))]
        (let [expected (edn/read-string (slurp (str root "/writer.edn")))
              actual (vec (jdbc/fetch connection selection))]
          (check! (= expected actual) "fresh reader full event/link/scalar parity")
          (check! (= actual (vec (jdbc/fetch connection (str/replace selection "FROM otel_traces " "FROM otel_traces_legacy "))))
                  "fresh reader independent legacy table parity")
          (spit (str root "/reader.edn") (pr-str {:rows (count actual) :equal? true}))))
      (throw (ex-info "writer or reader required" {})))
    (println :untyped-encoder-native-green phase)))

(defn run [executable]
  ;; Called by the maintained aggregate (and thus tests.yml). Each phase is a
  ;; fresh process; never accept same-process writer readback as recovery proof.
  (let [placeholder (java.io.File/createTempFile "untyped-encoder-native-" ".gate")
        root (.getAbsolutePath placeholder)]
    (check! (.delete placeholder) "replace owned temporary file")
    (check! (.mkdir (java.io.File. root)) "create owned gate directory")
    (doseq [directory ["objects" "scratch-writer" "scratch-reader"]]
      (check! (.mkdir (java.io.File. (str root "/" directory))) "create gate scratch"))
    (println :untyped-encoder-native-root root)
    (doseq [phase ["writer" "reader"]]
      (let [child (process/process [executable "-Srepro" "-Sdeps" "{:paths [\"src\" \"test\"]}"
                                    "-m" "otel.exporter.chdb-untyped-encoder-native-test" phase root]
                                   {:out :string :err :string})
            result (deref child 120000 ::timeout)]
        (when (= ::timeout result) (process/destroy-tree child))
        (spit (str root "/" phase ".log") (str (:out result) "\n" (:err result)))
        (check! (and (map? result) (= 0 (:exit result))
                     (str/includes? (str (:out result)) (str ":untyped-encoder-native-green " phase)))
                (str "fresh native child failed: " phase " evidence: " root))))
    (check! (= {:rows 3 :equal? true} (edn/read-string (slurp (str root "/reader.edn")))) "terminal reader receipt")
    (println :untyped-encoder-native-two-process-green root)))
