(ns otel.exporter.chdb-ordinary-rows-test
  (:require [clojure.data.json :as json]
            [db.driver :as driver]
            [db.jdbc-shim :as jdbc-shim]
            [jdbc.chdb :as chdb]
            [jdbc.chdb.native :as native]
            [jdbc.chdb.durable :as durable]
            [jdbc.core :as jdbc]
            [jdbc.proto :as jdbc-proto]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]))

(defn- rejected? [f]
  (try (f) false (catch Throwable _ true)))

(defn- version-fence-checks [check]
  (doseq [durable? [false true] owned? [false true]
          version ["26.7.3" "26.8.1" "unknown" nil]
          close-fails? [false true]]
    (let [effects (atom []) closes (atom 0)
          drv (reify driver/Driver
                (descriptor [_] {:id (if durable? :chdb-durable :chdb)
                                 :aliases #{"wire-version-test"} :uri-prefixes ["wire-version-test:"]
                                 :product-name "Wire version fixture"
                                 :capabilities {:transactions :none :generated-keys :none}})
                (open-handle [_ _] {})
                (close-handle [_ _] (swap! closes inc)
                  (when close-fails? (throw (ex-info "Secondary fixture close failure" {}))))
                (execute-handle [_ _ _ _] (throw (ex-info "Unexpected native execution" {}))))
          connection (with-redefs [driver/resolve-driver (fn [_] drv)]
                       (jdbc-shim/connection "wire-version-test:memory"))
          context! jdbc-shim/driver-context
          options (cond-> {:durable? durable? :signals #{:spans}}
                    (not owned?) (assoc :connection connection))]
      (try
        (with-redefs [jdbc/connection (fn [_] connection)
                      jdbc-shim/driver-context (fn [conn id]
                                                (swap! effects conj :context) (context! conn id))
                      native/ensure-loaded! (fn [] (swap! effects conj :load))
                      native/chdb-version (fn [] (swap! effects conj :version) version)
                      schema/ensure-schema! (fn [_] (swap! effects conj :schema))
                      durable/checkpoint! (fn [_] (swap! effects conj :checkpoint) {:status :committed})]
          ;; Real shim identity/role check, not a mocked role detector; no claim
          ;; that this context fixture contains an actual native Durable writer.
          (let [[writer error] (try [(exporter/exporter options) nil]
                                    (catch Throwable error [nil error]))]
            (if (= "26.7.3" version)
              (do (check "qualified package constructor succeeds" true (some? writer))
                  (check "driver check and version probe precede schema/checkpoint"
                         (cond-> [:context :load :version :schema] durable? (conj :checkpoint)) @effects)
                  (check "successful constructor retains connection ownership" 0 @closes))
              (do (check "unqualified package error fixed and original despite secondary close"
                         {:type :otel.exporter.chdb/unqualified-timestamp-wire} (ex-data error))
                  (check "unqualified package performs zero DDL/checkpoint"
                         [:context :load :version] @effects)
                  (check "rejected owned closes once; shared remains application-owned"
                         (if owned? 1 0) @closes)))))
        (finally (try (.close connection) (catch Throwable _ nil))))))
  (doseq [durable? [false true]]
    (let [effects (atom [])
          context! jdbc-shim/driver-context
          connection (:connection (let [drv (reify driver/Driver
                                              (descriptor [_] {:id :other :aliases #{"wrong-wire-test"}
                                                               :uri-prefixes ["wrong-wire-test:"]
                                                               :product-name "Wrong driver fixture"
                                                               :capabilities {:transactions :none :generated-keys :none}})
                                              (open-handle [_ _] {}) (close-handle [_ _] nil)
                                              (execute-handle [_ _ _ _] nil))]
                                    {:connection (with-redefs [driver/resolve-driver (fn [_] drv)]
                                                   (jdbc-shim/connection "wrong-wire-test:memory"))}))]
      (try
        (with-redefs [jdbc-shim/driver-context (fn [conn id]
                                               (swap! effects conj :context) (context! conn id))
                      native/ensure-loaded! (fn [] (swap! effects conj :load))
                      native/chdb-version (fn [] (swap! effects conj :version) "26.7.3")
                      schema/ensure-schema! (fn [_] (swap! effects conj :schema))]
          (check "wrong ordinary/Durable driver identity rejects" true
                 (rejected? #(exporter/exporter {:connection connection :durable? durable?})))
          (check "wrong driver rejection makes zero native probes or schema writes" [:context] @effects))
        (finally (.close connection))))))

(defn run [check]
  (version-fence-checks check)
  (let [timestamp #'exporter/timestamp
        payload #'exporter/ordinary-payload]
    (doseq [nanos [0 1 1000000000 1700000000123456789 9223372036854775807]]
      (check "DateTime64 exact integer nanos retained" nanos (timestamp nanos)))
    (doseq [invalid [-1 9223372036854775808N 18446744073709551615N 0.5 nil]]
      (check "shared timestamp formatter rejects unsupported physical domain" true
             (rejected? #(timestamp invalid)))
      (check "ordinary Timestamp reinforces physical domain" true
             (rejected? #(payload ["Timestamp"] [{"Timestamp" invalid}]))))
    (check "event timestamp array uses exact integer nanos" [0 1 1700000000123456789]
           (get (#'exporter/event-columns
                  [{:timestamp-unix-nano 0} {:timestamp-unix-nano 1}
                   {:timestamp-unix-nano 1700000000123456789}]) "Events.Timestamp"))
    (check "log observed fallback uses exact integer nanos without new column"
           1700000000123456789
           (get (#'exporter/log-row {:timestamp-unix-nano 0
                                    :observed-time-unix-nano 1700000000123456789} nil) "Timestamp"))
    (check "nonzero log event timestamp still wins over observed fallback" 1
           (get (#'exporter/log-row {:timestamp-unix-nano 1 :observed-time-unix-nano 2} nil) "Timestamp"))
    (let [calls (atom [])
          good {"Timestamp" 0 "Events.Timestamp" [0 1]}]
      (with-redefs [chdb/insert-json-rows! (fn [& args] (swap! calls conj args))]
        (check "later invalid event timestamp rejects before ordinary API" true
               (rejected? #(#'exporter/insert-batch! :connection (atom {:durable? false})
                                                   "owned_table" ["Timestamp" "Events.Timestamp"] "unused"
                                                   [good (assoc good "Events.Timestamp" [9223372036854775808N])]))))
      (check "later invalid event timestamp has zero driver effects" [] @calls))
    (let [calls (atom [])]
      (with-redefs [jdbc/execute! (fn [& args] (swap! calls conj args))]
        (check "Durable timestamp failure precedes materialized SQL execution" true
               (rejected? #(#'exporter/insert-batch! :writer (atom {:durable? true})
                                                   "owned_table" ["Timestamp"] "insert into owned_table"
                                                   (map (fn [n] {"Timestamp" (timestamp n)})
                                                        [0 9223372036854775808N])))))
      (check "Durable timestamp failure has zero JDBC execution effects" [] @calls)))
  (doseq [close-fails? [false true]]
    (let [closed (atom 0) schema-calls (atom 0)
          original (ex-info "Original context rejection" {})
          drv (reify driver/Driver
                (descriptor [_] {:id :chdb :aliases #{"owned-row-test"}
                                 :uri-prefixes ["owned-row-test:"]
                                 :product-name "Owned row fixture"
                                 :capabilities {:transactions :none :generated-keys :none}})
                (open-handle [_ _] {})
                (close-handle [_ _]
                  (swap! closed inc)
                  (when close-fails? (throw (ex-info "Secondary close failure" {}))))
                (execute-handle [_ _ _ _] (throw (ex-info "Unexpected execution" {}))))
          connection (with-redefs [driver/resolve-driver (fn [_] drv)]
                       (jdbc-shim/connection "owned-row-test:memory"))]
      (with-redefs [jdbc/connection (fn [_] connection)
                    jdbc-shim/driver-context (fn [& _] (throw original))
                    schema/ensure-schema! (fn [& _] (swap! schema-calls inc))]
        (check "owned startup guard preserves original error even if close fails"
               true (identical? original
                                (try (exporter/exporter {}) nil
                                     (catch Throwable error error))))
        (check "owned startup guard attempts connection close exactly once" 1 @closed)
        (check "owned startup guard performs zero schema writes" 0 @schema-calls))))
  (let [effects (atom [])
        closed (atom 0)
        drv (reify driver/Driver
              (descriptor [_] {:id :chdb-durable :aliases #{"durable-row-test"}
                               :uri-prefixes ["durable-row-test:"]
                               :product-name "Durable context fixture"
                               :capabilities {:transactions :none :generated-keys :none}})
              (open-handle [_ _] {})
              (close-handle [_ _] (swap! closed inc))
              (execute-handle [_ _ _ _] (swap! effects conj :driver-execute)))
        connection (with-redefs [driver/resolve-driver (fn [_] drv)]
                     (jdbc-shim/connection "durable-row-test:memory"))]
    ;; Actual shim context rejection, with no mocked driver-context detector.
    ;; This fixture proves driver identity guarding, not a native Durable writer.
    (try
      (with-redefs [schema/ensure-schema! (fn [& _] (swap! effects conj :schema))
                  chdb/insert-json-rows! (fn [& _] (swap! effects conj :insert))
                  jdbc/execute! (fn [& _] (swap! effects conj :execute))]
      (check "real shim rejects unmarked Durable context at startup" true
             (rejected? #(exporter/exporter {:connection connection})))
      (check "ordinary context rejection precedes every schema/data mutation"
             [] @effects)
      (check "startup rejection does not close application-owned connection" 0 @closed))
      (finally (.close connection))))
  (let [calls (atom [])
        gauge (assoc (first (#'exporter/metric-rows
                              {:attributes {}}
                              [{:scope {} :metrics [{:type :gauge :name "valid-first"
                                                    :data-points [{:value 0}]}]}]))
                     :_type :gauge)
        malformed (assoc (zipmap (get schema/clickstack-metric-insert-columns :histogram)
                                (repeat "")) :_type :histogram)]
    (with-redefs [chdb/insert-json-rows! (fn [& args] (swap! calls conj args))
                  jdbc/execute! (fn [& args] (swap! calls conj args))]
      (check "first metric type is independently valid" true
             (string? (#'exporter/ordinary-payload
                        (get schema/clickstack-metric-insert-columns :gauge)
                        [(dissoc gauge :_type)])))
      (check "later invalid metric type rejects the logical batch" true
             (rejected? #(#'exporter/export-metric-rows!
                           :connection (atom {:durable? false}) [gauge malformed])))
      (check "later invalid metric type performs zero native queries" [] @calls)))
  (let [payload #'exporter/ordinary-payload
        maximum 18446744073709551615N]
    (check "physical UInt64 count and buckets preserve their full domain"
           {"Count" maximum "BucketCounts" [0 maximum] "Duration" maximum}
           (json/read-str (payload ["Count" "BucketCounts" "Duration"]
                                   [{"Count" maximum "BucketCounts" [0 maximum]
                                     "Duration" maximum}])))
    (doseq [invalid [-1 18446744073709551616N]]
      (check "out-of-domain UInt64 rejects" true
             (rejected? #(payload ["Count"] [{"Count" invalid}])))))
  (let [calls (atom [])
        columns ["text" "flag" "count" "status" "attributes"]
        row {"text" "? é\n\"\\" "flag" false "count" 0 "status" 3
             "attributes" {"secret-shaped" "? preserved"}}
        ordinary (atom {:durable? false})
        insert #'exporter/insert-batch!]
    (with-redefs [chdb/insert-json-rows!
                  (fn [connection table ordered payload]
                    (swap! calls conj [connection table ordered payload]))
                  jdbc/execute!
                  (fn [& _] (throw (ex-info "Old SQL path reached" {})))]
      (insert :connection ordinary "owned_table" columns "unused SQL" [row])
      (check "ordinary API receives table and ordered schema" 
             [:connection "owned_table" columns] (subvec (first @calls) 0 3))
      (check "maintained JSON preserves question marks, Unicode, false and zero"
             row (json/read-str (nth (first @calls) 3)))
      (doseq [[label invalid]
              [["same-cardinality wrong key" (-> row (dissoc "text") (assoc "wrong" "x"))]
               ["missing key" (dissoc row "text")]
               ["unexpected key" (assoc row "later" "x")]
               ["NaN" (assoc row "count" Double/NaN)]
               ["infinity" (assoc row "count" Double/POSITIVE_INFINITY)]
               ["negative infinity" (assoc row "count" Double/NEGATIVE_INFINITY)]
               ["oversized integer" (assoc row "count" (bigint "9223372036854775808"))]
               ["ratio" (assoc row "count" 1/3)]]]
        (reset! calls [])
        (check (str label " later row rejects") true
               (rejected? #(insert :connection ordinary "owned_table" columns
                                   "unused SQL" [row invalid])))
        (check (str label " rejects before driver entry") [] @calls)))
    (let [sql (atom [])]
      (with-redefs [chdb/insert-json-rows!
                    (fn [& _] (throw (ex-info "Durable entered ordinary API" {})))
                    jdbc/execute! (fn [connection query]
                                    (swap! sql conj [connection query]))]
        (insert :writer (atom {:durable? true}) "owned_table" columns
                "insert into owned_table" [row])
        (check "Durable retains exact materialized SQL" 
               [[:writer (str "insert into owned_table FORMAT JSONEachRow\n"
                              (json/write-str row) "\n")]] @sql)))
    (let [payload #'exporter/ordinary-payload]
      (check "exact UTF8 boundary includes row punctuation and newline"
             (* 8 1024 1024)
             (alength (.getBytes
                       (payload ["x"] [{"x" (apply str (repeat (- (* 8 1024 1024) 9) "a"))}])
                       "UTF-8")))
      (check "multibyte UTF8 overflow rejects" true
             (rejected? #(payload ["x"]
                                  [{"x" (apply str (repeat (* 4 1024 1024) "é"))}]))))))
