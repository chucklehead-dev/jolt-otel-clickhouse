(ns otel.exporter.chdb-mixed-codec-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [jolt.fibers :as fibers]
            [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.owned-statement :as owned]
            [jdbc.chdb.json-each-row :as row-encoder]
            [otel.exporter.chdb :as e]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb.compact-format :as compact]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(def options {:durable? true :insert-format :json-compact-each-row
              :json-backend :native-guarded-byte-batch :owned-statement-output? true
              :experimental-span-codec? true})
(def all-signals #{:spans :logs :metrics})
(def span {:name "mixed" :start-time-unix-nano 12 :end-time-unix-nano 34
           :span-context {:trace-id "0123456789abcdef0123456789abcdef" :span-id "0123456789abcdef"}
           :resource {:attributes {"service.name" "mixed"}} :attributes {"flag" false}
           :events [] :links []})
(def log-record {:timestamp-unix-nano 12 :severity-text "INFO" :severity-number 9
                 :body "é😀\\n" :attributes {"count" 2}
                 :resource {:attributes {"service.name" "mixed"}}})
(defn outcome [f] (try (f) nil (catch Throwable error error)))
(defn target []
  (let [codec (#'e/load-experimental-span-codec! true true true)
        state (atom {:durable? true :insert-format :json-compact-each-row
                     :json-backend :native-guarded-byte-batch :timestamp-wire :raw-ticks
                     :owned-statement-output? true :max-insert-bytes 100000
                     :experimental-span-codec codec :compact-plans :test
                     :span-insert-columns @#'e/direct-span-columns
                     :log-insert-columns schema/clickstack-log-insert-columns
                     :typed-metric-columns {} :in-flight 0})]
    (e/->ChdbExporter :connection false all-signals state)))
(defn collected [kind]
  [{:scope {:name "scope"} :metrics [{:name (name kind) :type kind
                                    :temporality :delta :monotonic? true
                                    :explicit-bounds [1.0]
                                    :data-points [{:value 2 :count 2 :sum 3.5
                                                   :bucket-counts [1 1]
                                                   :attributes {"flag" false}}]}]}])

(deftest ordinary-backend-selection-is-unchanged
  (doseq [backend [:configured :native-guarded :native-guarded-string-cache :native-guarded-byte-batch]]
    (is (= backend (#'e/non-span-json-backend {:json-backend backend})))
    (is (= :configured (#'e/non-span-json-backend {:json-backend backend :experimental-span-codec :test})))))

(deftest ordinary-methods-preserve-inherited-private-bindings
  (let [t (target) original json/write-str]
    (swap! (:state t) dissoc :experimental-span-codec)
    (swap! (:state t) assoc :json-backend :configured)
    (with-redefs [compact/require-order! (fn [& _])
                  json/write-str (fn [& args]
                                   (is (= ::outer @#'e/*experimental-attribute-transform*))
                                   (is (= ::outer @#'e/*experimental-nested-writer*))
                                   (apply original args))
                  durable/execute-and-flush! (fn [& _] {:status :committed})]
      (with-bindings {#'e/*experimental-attribute-transform* ::outer
                      #'e/*experimental-nested-writer* ::outer}
        (is (true? (logs/export-logs! t [log-record])))
        (is (true? (export/export-metrics! t {} (collected :gauge)))))
      (is (zero? (:in-flight @(:state t)))))))

(deftest mixed-preflight-admits-supported-signal-sets-without-legacy-writer
  (let [opens (atom 0)]
    (with-redefs [row-encoder/open-encoder (fn [& _] (throw (ex-info "Legacy writer loaded" {})))
                  jdbc/connection (fn [& _] (swap! opens inc) (throw (ex-info "Acquisition" {:type ::acquisition})))]
      (doseq [signals [#{:spans} #{:spans :logs} #{:spans :metrics} all-signals]]
        (is (= ::acquisition
               (:type (ex-data (outcome #(e/exporter (assoc options :signals signals))))))))
      (is (= 4 @opens)))))

(deftest unsupported-signal-sets-reject-before-acquisition
  (let [opens (atom 0)]
    (with-redefs [jdbc/connection (fn [& _] (swap! opens inc))]
      (doseq [signals [#{} #{:logs} #{:metrics} #{:logs :metrics} #{:spans :unknown}]]
        (is (= :otel.exporter.chdb/invalid-experimental-span-codec
               (:type (ex-data (outcome #(e/exporter (assoc options :signals signals))))))))
      (is (zero? @opens)))))

(deftest mixed-signals-use-owned-spans-and-configured-log-metric-sql
  (let [t (target) writes (atom []) json-calls (atom []) write-json json/write-str]
    (with-redefs [compact/require-order! (fn [& _])
                  row-encoder/open-encoder (fn [& _] (throw (ex-info "Legacy writer loaded" {})))
                  json/write-str (fn [& args]
                                   (swap! json-calls conj @#'e/*json-backend*)
                                   (is (nil? @#'e/*experimental-attribute-transform*))
                                   (is (nil? @#'e/*experimental-nested-writer*))
                                   (apply write-json args))
                  durable/execute-owned-and-flush!
                  (fn [_ statement] (swap! writes conj [:owned (owned/text statement)]) {:status :committed})
                  durable/execute-and-flush!
                  (fn [_ sql] (swap! writes conj [:string sql]) {:status :reconciled})]
      (is (true? (export/export-spans! t [span])))
      (is (true? (logs/export-logs! t [log-record])))
      (doseq [kind [:gauge :sum :histogram]]
        (is (true? (export/export-metrics! t {} (collected kind)))))
      (is (= [:owned :string :string :string :string] (mapv first @writes)))
      (is (pos? (count @json-calls)))
      (is (every? #{:configured} @json-calls))
      (doseq [[_ sql] @writes]
        (is (str/includes? sql "SETTINGS input_format_read_datetime_number_as_raw_value=1"))
        (is (str/includes? sql " FORMAT JSONCompactEachRow\n")))
      (let [sql (second (second @writes))
            marker " FORMAT JSONCompactEachRow\n"
            values (json/read-str (subs sql (+ (str/index-of sql marker) (count marker))))]
        (is (= (:body log-record) (get (zipmap schema/clickstack-log-insert-columns values) "Body"))))
      (is (zero? (:in-flight @(:state t)))))))

(deftest non-span-errors-are-visible-without-poisoning-span-bindings
  (let [t (target) marker (ex-info "Configured JSON stop" {}) writes (atom 0)]
    (with-redefs [compact/require-order! (fn [& _])
                  json/write-str (fn [& _] (throw marker))
                  durable/execute-owned-and-flush! (fn [& _] (swap! writes inc) {:status :committed})
                  durable/execute-and-flush! (fn [& _] (swap! writes inc) {:status :committed})]
      (doseq [emit [#(logs/export-logs! t [log-record])
                    #(export/export-metrics! t {} (collected :gauge))]]
        (is (false? (emit)))
        (is (identical? marker (e/last-error t)))
        (is (zero? @writes))
        (is (zero? (:in-flight @(:state t)))))
      (is (true? (export/export-spans! t [span])))
      (is (= 1 @writes))
      (is (nil? @#'e/*experimental-nested-writer*)))))

(deftest configured-non-span-payloads-still-enforce-the-byte-budget
  (let [t (target) writes (atom 0)]
    (swap! (:state t) assoc :max-insert-bytes 1)
    (with-redefs [compact/require-order! (fn [& _])
                  durable/execute-and-flush! (fn [& _] (swap! writes inc) {:status :committed})]
      (doseq [emit [#(logs/export-logs! t [log-record])
                    #(export/export-metrics! t {} (collected :gauge))]]
        (is (false? (emit)))
        (is (some? (e/last-error t)))
        (is (zero? @writes))
        (is (zero? (:in-flight @(:state t))))))))

(deftest configured-non-span-output-does-not-ack-admission-or-local-execution
  (let [t (target)]
    (with-redefs [compact/require-order! (fn [& _])]
      (doseq [status [:admitted :executed-local]]
        (with-redefs [durable/execute-and-flush! (fn [& _] {:status status})]
          (doseq [emit [#(logs/export-logs! t [log-record])
                        #(export/export-metrics! t {} (collected :gauge))]]
            (is (false? (emit)))
            (is (= :otel.exporter.chdb/durable-atomic-execution-unconfirmed
                   (:type (ex-data (e/last-error t)))))
            (is (zero? (:in-flight @(:state t))))))))))

(deftest reentrant-log-does-not-borrow-span-scope-and-restores-it
  (let [t (target) original @#'e/span-row called (atom false) writes (atom [])]
    (with-redefs-fn
      {#'compact/require-order! (fn [& _])
       #'e/span-row (fn [& args]
                     (when-not @called
                       (reset! called true)
                       (let [owner @#'e/*experimental-nested-writer*
                             attributes @#'e/*experimental-attribute-transform*]
                         (is (some? owner))
                         (is (true? (logs/export-logs! t [log-record])))
                         (is (identical? owner @#'e/*experimental-nested-writer*))
                         (is (identical? attributes @#'e/*experimental-attribute-transform*))
                         (is (= :native-guarded-byte-batch @#'e/*json-backend*))))
                     (apply original args))
       #'durable/execute-and-flush! (fn [& _]
                                     (is (nil? @#'e/*experimental-nested-writer*))
                                     (swap! writes conj :log) {:status :committed})
       #'durable/execute-owned-and-flush! (fn [& _] (swap! writes conj :span) {:status :committed})}
      (fn []
        (is (true? (export/export-spans! t [span])))
        (is (= [:log :span] @writes))
        (is (zero? (:in-flight @(:state t))))))))

(deftest concurrent-log-does-not-observe-a-parked-span-scope
  (let [t (target) ready (promise) resume (promise) result (promise)
        original @#'e/span-row writes (atom [])]
    (with-redefs-fn
      {#'compact/require-order! (fn [& _])
       #'e/span-row (fn [& args]
                     (is (some? @#'e/*experimental-nested-writer*))
                     (deliver ready true)
                     @resume
                     (apply original args))
       #'durable/execute-and-flush! (fn [& _]
                                     (is (= :configured @#'e/*json-backend*))
                                     (is (nil? @#'e/*experimental-nested-writer*))
                                     (swap! writes conj :log) {:status :committed})
       #'durable/execute-owned-and-flush! (fn [& _]
                                           (is (= :native-guarded-byte-batch @#'e/*json-backend*))
                                           (swap! writes conj :span) {:status :committed})}
      (fn []
        (let [worker (fibers/spawn (fn [] (let [out (export/export-spans! t [span])]
                                          (deliver result out) out)))]
          (try
            (is (= true (deref ready 2000 ::timeout)))
            (is (true? (logs/export-logs! t [log-record])))
            (is (= ::pending (deref result 0 ::pending)))
            (is (= 1 (:in-flight @(:state t))))
            (finally (deliver resume true) (fibers/join worker)))
          (is (= true @result))
          (is (= [:log :span] @writes))
          (is (zero? (:in-flight @(:state t)))))))))

(defn -main [& _]
  (let [r (run-tests 'otel.exporter.chdb-mixed-codec-test)]
    (assert (= 10 (:test r)))
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
