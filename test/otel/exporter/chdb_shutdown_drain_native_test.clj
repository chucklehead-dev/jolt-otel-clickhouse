(ns otel.exporter.chdb-shutdown-drain-native-test
  "Two-process native #94 gate: admitted concurrent exports versus final close."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-untyped-encoder-native-test :as rows]
            [otel.sdk.export :as export]))

(defn- check! [condition label]
  (when-not condition
    (throw (ex-info "Native exporter shutdown gate failed" {:check label}))))

(defn- await! [value label]
  (let [result (deref value 60000 ::timeout)]
    (check! (not= ::timeout result) label)
    result))

(defn- options [root phase]
  {:namespace-backend (local/local-backend (str root "/objects"))
   :object-id "shutdown-race"
   :scratch-parent (str root "/scratch-" phase)})

(defn- expected-selection []
  (str/replace rows/selection "FROM otel_traces " "FROM otel_traces_expected "))

(defn- writer! [root]
  ;; The exporter owns the real Durable writer connection. The independent
  ;; expected table is populated with the established row-map/data.json path
  ;; before the measured race, so no synthetic native method is substituted.
  (let [target (exporter/exporter
                {:db-spec (durable/writer-dbspec
                           (assoc (options root "writer")
                                  :owner "exporter-shutdown-native"
                                  :instance "one" :database "otel"))
                 :durable? true :signals #{:spans}})
        connection (:connection target)
        state (:state target)
        entered-two (promise)
        release-native (promise)
        fenced (promise)
        calls (atom 0)
        native-execute @#'durable/execute-and-flush!
        expected-payload (#'exporter/json-each-row-payload
                          (mapv #(#'exporter/span-row % nil) rows/spans))]
    (try
      (check! (= (set rows/selected-columns)
                 (set (keys (#'exporter/span-row (first rows/spans) nil))))
              :full-physical-column-oracle)
      (jdbc/execute! connection "CREATE TABLE otel_traces_expected AS otel_traces")
      (jdbc/execute! connection
                     (str "insert into otel_traces_expected FORMAT JSONEachRow\n"
                          expected-payload))
      (check! (contains? #{:committed :reconciled}
                         (:status (durable/flush! connection)))
              :expected-table-published)
      (add-watch state ::fenced
                 (fn [_ _ _ snapshot]
                   (when (contains? (:closed-signals snapshot) :spans)
                     (deliver fenced true))))
      (with-redefs [durable/execute-and-flush!
                    (fn [actual-connection sql]
                      ;; Hold both public calls after lifecycle admission but
                      ;; before either real native writer request. A pre-fix
                      ;; exporter closes the owned handle in this interval.
                      (check! (identical? connection actual-connection)
                              :owned-writer-connection)
                      (check! (str/starts-with?
                               sql "insert into otel_traces FORMAT JSONEachRow\n")
                              :public-trace-sql)
                      (when (= 2 (swap! calls inc))
                        (deliver entered-two true))
                      @release-native
                      (native-execute actual-connection sql))]
        (let [first-call (future (export/export-spans! target [(nth rows/spans 0)]))
              second-call (future (export/export-spans! target (subvec rows/spans 1)))]
          (try
            (check! (true? (await! entered-two :two-native-requests-admitted))
                    :two-call-entry)
            (check! (= 2 (:in-flight @state)) :two-in-flight)
            (let [shutdown (future (export/shutdown-exporter! target))]
              (check! (true? (await! fenced :final-shutdown-fence)) :fenced)
              (check! (= :closing (:connection-close-status @state))
                      :owned-close-pending)
              (check! (false? (:connection-closed? @state))
                      :native-handle-still-open)
              (check! (= ::pending (deref shutdown 10 ::pending))
                      :shutdown-awaits-admitted-calls)
              (check! (false? (export/export-spans! target [(first rows/spans)]))
                      :post-fence-rejection)
              (check! (= 2 @calls) :rejected-call-did-not-enter-native)
              (deliver release-native true)
              (check! (true? (await! first-call :first-confirmed-ack))
                      :first-confirmed-ack)
              (check! (true? (await! second-call :second-confirmed-ack))
                      :second-confirmed-ack)
              (check! (true? (await! shutdown :terminal-owned-close))
                      :terminal-owned-close)
              (check! (= :closed (:connection-close-status @state))
                      :owned-handle-closed)
              (check! (= 0 (:in-flight @state)) :no-in-flight-calls)
              (check! (true? (export/shutdown-exporter! target))
                      :repeat-shutdown-stable)
              (spit (str root "/writer.edn")
                    (pr-str {:admitted 2 :post-fence-rejected true
                             :confirmed-acks 2 :close-status :closed
                             :expected-payload-sha256 (rows/sha256 expected-payload)})))
            (finally (deliver release-native true)))))
      (finally
        (remove-watch state ::fenced)
        (deliver release-native true)
        ;; A failed assertion must not strand an owned handle. The launcher
        ;; bounds a genuinely hung native call and retains its evidence root.
        (export/shutdown-exporter! target)))
    (println :shutdown-race-native-writer-confirmed)))

(defn- reader! [root]
  (with-open [connection (jdbc/connection
                          (durable/snapshot-dbspec (options root "reader")))]
    (let [writer (edn/read-string (slurp (str root "/writer.edn")))
          actual (vec (jdbc/fetch connection rows/selection))
          expected (vec (jdbc/fetch connection (expected-selection)))]
      (check! (= 2 (:admitted writer)) :writer-admission-receipt)
      (check! (:post-fence-rejected writer) :writer-rejection-receipt)
      (check! (= 2 (:confirmed-acks writer)) :writer-ack-receipt)
      (check! (= :closed (:close-status writer)) :writer-close-receipt)
      (check! (= 3 (count actual)) :exact-row-count)
      (check! (= expected actual) :fresh-reader-full-physical-row-parity)
      (check! (every? #(= (count rows/selected-columns) (count %)) actual)
              :all-physical-columns-present)
      (spit (str root "/reader.edn")
            (pr-str {:rows (count actual) :full-physical-row-parity true
                     :row-sha256 (rows/sha256 (pr-str actual))}))))
  (println :shutdown-race-native-fresh-reader-confirmed))

(defn -main [phase root]
  (case phase
    "writer" (writer! root)
    "reader" (reader! root)
    (throw (ex-info "writer or reader required" {:phase phase}))))
