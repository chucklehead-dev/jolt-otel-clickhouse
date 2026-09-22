(ns otel.exporter.chdb-untyped-encoder-native-test
  "Small writer/fresh-reader recovery gate; each phase must use a new process."
  (:require [clojure.edn :as edn] [db.jdbc] [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.sdk.export :as export]))

(def spans
  (mapv (fn [n]
          {:name (str "unicode-λ😀-" n) :start-time-unix-nano n :end-time-unix-nano (+ n 7)
           :span-context {:trace-id (str n) :span-id (str n)}
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
        (let [target (exporter/exporter {:connection connection :durable? true :create-schema? false :signals #{:spans}})]
          (check! (true? (export/export-spans! target spans)) "public export")
          (check! (= :empty (:status (durable/flush! connection))) "published before return")
          (let [rows (vec (jdbc/fetch connection selection))]
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
          (spit (str root "/reader.edn") (pr-str {:rows (count actual) :equal? true}))))
      (throw (ex-info "writer or reader required" {})))
    (println :untyped-encoder-native-green phase)))
