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
           :span-context {:trace-id (str n) :span-id (str n) :trace-state "vendor=λ"}
           :parent-span-id "parent" :status {:code :error :description "status-λ😀"}
           :resource {:attributes {"service.name" "unicode-λ😀" "enabled" true}}
           :scope {:name "gate" :version "1"} :kind :server
           :attributes (if (= n 3) {"nested" [true 42]} {"number" n "flag" true})
           :events [{:timestamp-unix-nano n :name "event-λ" :attributes {"x" n}}]
           :links (if (= n 3) [{:span-context {:trace-id "linked" :span-id "id"}
                               :attributes {"relation" "prior"}}] [])}) [1 2 3]))

(def selected-columns
  (into schema/clickstack-trace-insert-columns ["EventsJSON" "LinksJSON"]))

(def selection
  (str "SELECT "
       (str/join ", "
         (map (fn [column]
                (case column
                  "Timestamp" "toString(toUnixTimestamp64Nano(Timestamp)) AS Timestamp"
                  "Events.Timestamp" "arrayMap(x -> toString(toUnixTimestamp64Nano(x)), `Events.Timestamp`) AS `Events.Timestamp`"
                  (str "`" column "`"))) selected-columns))
       " FROM otel_traces ORDER BY TraceId, SpanId"))

(defn sha256 [text]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes text "UTF-8"))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

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
              legacy-payload (#'exporter/json-each-row-payload (mapv #(#'exporter/span-row % nil) spans))
              execute-and-flush @#'durable/execute-and-flush! captured (atom [])
              prefix "insert into otel_traces FORMAT JSONEachRow\n"]
          (check! (= (set selected-columns) (set (keys (#'exporter/span-row (first spans) nil))))
                  "selection covers every legacy row and ClickStack insert column")
          (check! (every? #'exporter/untyped-span-eligible? eligible) "fully eligible event/scalar/empty-link batch")
          (check! (not (#'exporter/untyped-span-eligible? (first fallback))) "distinct nonempty-link fallback")
          (jdbc/execute! connection "CREATE TABLE otel_traces_legacy AS otel_traces")
          (jdbc/execute! connection (str "insert into otel_traces_legacy FORMAT JSONEachRow\n" legacy-payload))
          (check! (contains? #{:committed :reconciled} (:status (durable/flush! connection))) "legacy baseline publication")
          (check! (ifn? (force @#'exporter/untyped-span-encoder)) "production plan available")
          (with-redefs [durable/execute-and-flush!
                        (fn [connection sql]
                          (when (str/starts-with? sql prefix)
                            (swap! captured conj (subs sql (count prefix))))
                          (execute-and-flush connection sql))]
            (with-redefs [exporter/span-row (fn [& _] (throw (ex-info "eligible batch fell back" {})))]
              (check! (true? (export/export-spans! target eligible)) "eligible public export uses encoder"))
            (check! (true? (export/export-spans! target fallback)) "fallback public export"))
          (let [actual-payload (apply str @captured)]
            (check! (= 2 (count @captured)) "two distinct public batch payloads")
            (check! (= legacy-payload actual-payload) "exact public materialized JSONEachRow")
            (spit (str root "/payload.edn")
                  (pr-str {:columns selected-columns :legacy-sha256 (sha256 legacy-payload)
                           :public-sha256 (sha256 actual-payload) :exact? true})))
          ;; Each public exporter batch is already settled by its own atomic
          ;; writer request. This explicit force flush is therefore empty.
          (check! (= :empty (:status (durable/flush! connection))) "published before return")
          (let [rows (vec (jdbc/fetch connection selection))]
            (check! (= rows (vec (jdbc/fetch connection (str/replace selection "FROM otel_traces " "FROM otel_traces_legacy "))))
                    "native selected rows equal separately materialized legacy baseline")
            (check! (= 3 (count rows)) "writer row count")
            (check! (every? #(= (count selected-columns) (count %)) rows) "all selected columns returned")
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
