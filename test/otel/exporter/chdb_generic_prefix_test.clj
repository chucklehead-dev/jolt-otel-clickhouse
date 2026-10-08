(ns otel.exporter.chdb-generic-prefix-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [jdbc.chdb.json-each-row :as encoder]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-compact-wire-test :as wire]))

(defn run-insert [backend format rows limit]
  (let [sql (atom []) columns ["x"]
        state (atom {:durable? true :insert-format format
                     :compact-plans {"otel_logs" (wire/confirmed-plan :connection columns)}})]
    (with-bindings {#'exporter/*json-backend* backend}
      (with-redefs [exporter/max-insert-bytes limit
                    exporter/execute-durable-sql! (fn [connection text] (swap! sql conj [connection text]))]
        (@#'exporter/insert-batch! :connection state "otel_logs" columns "insert into otel_logs" rows)))
    @sql))

(deftest generic-native-prefix-matches-exact-sql-and-is-delegated
  (when (string? (System/getProperty "jolt.version"))
    (let [old encoder/encode-limited-prefixed-text! calls (atom 0)]
      (with-redefs [encoder/encode-limited-prefixed-text!
                    (fn [& args] (swap! calls inc) (apply old args))]
        (doseq [format [:json-each-row :json-compact-each-row]
                rows [[] [{"x" "β😀\n/\\"}] [{"x" 1} {"x" [false nil 9007199254740993]}]]]
          (is (= (run-insert :configured format rows 10000)
                 (run-insert :native-guarded-byte-batch format rows 10000)))))
      (is (= 6 @calls)))))

(deftest generic-native-prefix-retains-payload-budget-and-row-local-view
  (when (string? (System/getProperty "jolt.version"))
    (doseq [format [:json-each-row :json-compact-each-row]]
      (let [views (atom [])
            value (reify json/JSONWriter
                    (-write [_ sink _] (swap! views conj (.toString sink)) (.write sink "true")))
            row {"x" value}
            reference (run-insert :configured format [row] 10000)
            expected @views
            _ (reset! views [])
            ;; SQL prefix is much larger than the entire encoded row budget.
            row-bytes (if (= format :json-each-row) 11 7)]
        (is (= reference (run-insert :native-guarded-byte-batch format [row] row-bytes)))
        (is (= expected @views))
        (let [error (try (run-insert :native-guarded-byte-batch format [row] (dec row-bytes))
                         nil (catch Throwable e e))]
          (is (= {:limit (dec row-bytes)} (ex-data error))))))))

(deftest generic-overflow-does-not-execute-or-request-a-later-row
  (when (string? (System/getProperty "jolt.version"))
    (doseq [format [:json-each-row :json-compact-each-row]]
      (let [effects (atom []) executions (atom 0)
            rows (letfn [(step [n] (lazy-seq (swap! effects conj n)
                                           (cons {"x" true} (lazy-seq (step (inc n))))))] (step 0))
            columns ["x"]
            state (atom {:durable? true :insert-format format
                         :compact-plans {"otel_logs" (wire/confirmed-plan :connection columns)}})]
        (with-bindings {#'exporter/*json-backend* :native-guarded-byte-batch}
          (with-redefs [exporter/max-insert-bytes 1
                        exporter/execute-durable-sql! (fn [& _] (swap! executions inc))]
            (is (thrown? clojure.lang.ExceptionInfo
                         (@#'exporter/insert-batch! :connection state "otel_logs" columns
                                                   "insert into otel_logs" rows)))))
        (is (= [0] @effects)) (is (zero? @executions))))))
