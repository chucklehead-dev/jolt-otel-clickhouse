(ns otel.exporter.chdb-compact-wire-test
  (:require [clojure.test :as test :refer [deftest is]]
            [clojure.data.json :as json]
            [jdbc.core :as jdbc]
            [jdbc.chdb :as native-driver]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-wire-test-config :as wire-config]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb-benchmark :as benchmark]))

(defn error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest native-fixture-format-selection-is-closed
  (is (= :json-each-row (wire-config/parse-format [])))
  (is (= :json-each-row (wire-config/parse-format ["json-each-row"])))
  (is (= :json-compact-each-row (wire-config/parse-format ["json-compact-each-row"])))
  (doseq [arguments [["unknown"] ["json-compact-each-row" "extra"] [nil]]]
    (is (= {:type :otel.exporter.chdb-wire-test-config/invalid-format}
           (error-data #(wire-config/parse-format arguments))))))

(deftest format-validation-precedes-resource-acquisition
  (with-redefs [jdbc/connection (fn [& _] (throw (Exception. "must not open")))]
    (doseq [format [:raw-binary "JSONCompactEachRow" nil (Object.)]]
      (is (= {:type :otel.exporter.chdb/invalid-insert-format}
             (error-data #(exporter/exporter {:insert-format format})))))))

(deftest compact-projection-retains-schema-order-and-all-values
  (let [columns ["z" "x" "nil" "boolean" "nested"]
        row {"x" "é😀/?\n" "z" 9223372036854775807
             "nil" nil "boolean" false "nested" {"k" [1 2.5 true]}}
        wire (#'exporter/insert-payload :json-compact-each-row columns [row])
        decoded (json/read-str wire)]
    (is (= (json/read-str (json/write-str row)) (zipmap columns decoded)))
    (is (= [9223372036854775807 "é😀/?\n" nil false {"k" [1 2.5 true]}]
           (first (#'exporter/compact-rows columns [row]))))))

(deftest benchmark-metric-rows-retain-complete-projection
  (let [groups (#'exporter/metric-row-groups
                (#'benchmark/resource "compact-wire") (#'benchmark/metrics 0 4) nil)]
    (doseq [kind [:gauge :sum :histogram]
            :let [columns (get schema/clickstack-metric-insert-columns kind)]
            row (get groups kind)]
      (is (= (json/read-str (json/write-str row))
             (zipmap columns
                     (json/read-str (#'exporter/insert-payload
                                     :json-compact-each-row columns [row]))))))))

(deftest same-type-fields-retain-their-column-binding
  ;; Native coercion cannot detect two String columns accidentally swapped.
  (is (= ["service-left" "scope-right"]
         (json/read-str (#'exporter/insert-payload
                         :json-compact-each-row ["left" "right"]
                         [{"right" "scope-right" "left" "service-left"}])))))

(deftest physical-column-plan-is-closed-and-safely-quoted
  (is (= "insert into otel_logs (`Scope.Name`, `odd``column`)"
         (#'exporter/compact-insert-query "otel_logs" ["Scope.Name" "odd`column"])))
  (doseq [columns [nil [] ["x" "x"] [:x]]]
    (is (= {:type :otel.exporter.chdb/invalid-compact-columns}
           (error-data #(doall (#'exporter/compact-rows columns []))))))
  (is (= {:type :otel.exporter.chdb/invalid-compact-table}
         (error-data #(#'exporter/compact-insert-query "unowned_table" ["x"])))))

(deftest projection-rejects-missing-extra-and-falsey-rows
  (doseq [row [{"other" 1} {"x" 1 "extra" 2} {} nil false]]
    (is (= {:type :otel.exporter.chdb/invalid-compact-row}
           (error-data #(doall (#'exporter/compact-rows ["x"] [row])))))))

(deftest overflow-does-not-realize-the-following-row
  (let [visited (atom [])
        rows (lazy-seq
               (swap! visited conj :first)
               (cons {"x" "abc"}
                     (lazy-seq (swap! visited conj :second) (list {"x" "later"}))))]
    (with-redefs [exporter/max-insert-bytes 7]
      (is (= {:limit 7}
             (error-data #(#'exporter/insert-payload :json-compact-each-row ["x"] rows)))))
    (is (= [:first] @visited))))

(deftest compact-durable-insert-retains-one-confirmed-request
  (let [calls (atom []) state (atom {:durable? true :insert-format :json-compact-each-row})]
    (with-redefs [exporter/execute-durable-sql!
                  (fn [connection sql] (swap! calls conj [connection sql]) {:status :committed})]
      (#'exporter/insert-batch! :connection state "otel_logs" ["x"] "ignored" [{"x" "?"}]))
    (is (= [[:connection "insert into otel_logs (`x`) FORMAT JSONCompactEachRow\n[\"?\"]\n"]] @calls))))

(deftest default-durable-wire-is-unchanged
  (let [calls (atom []) state (atom {:durable? true})]
    (with-redefs [exporter/execute-durable-sql!
                  (fn [_ sql] (swap! calls conj sql) {:status :committed})]
      (#'exporter/insert-batch! nil state "otel_logs" ["x"] "original query" [{"x" 1}]))
    (is (= ["original query FORMAT JSONEachRow\n{\"x\":1}\n"] @calls))))

(deftest compact-ordinary-route-uses-the-query-api-with-an-explicit-plan
  (let [calls (atom []) state (atom {:durable? false :insert-format :json-compact-each-row})]
    (with-redefs [jdbc/execute! (fn [connection sql] (swap! calls conj [connection sql]))
                  native-driver/insert-json-rows! (fn [& _] (throw (Exception. "wrong format transport")))]
      (#'exporter/insert-batch! :connection state "otel_logs" ["x"] "ignored" [{"x" 1}]))
    (is (= [[:connection "insert into otel_logs (`x`) FORMAT JSONCompactEachRow\n[1]\n"]] @calls))))

(deftest compact-ordinary-metric-failure-still-precedes-every-driver-call
  (let [calls (atom 0)
        groups (#'exporter/metric-row-groups
                 (#'benchmark/resource "compact-wire") (#'benchmark/metrics 0 1) nil)
        groups (assoc groups :histogram [(assoc (first (:histogram groups)) "Count" -1)])
        state (atom {:durable? false :insert-format :json-compact-each-row})]
    (with-redefs [jdbc/execute! (fn [& _] (swap! calls inc))]
      (is (= {:type :otel.exporter.chdb/invalid-ordinary-row}
             (error-data #(#'exporter/export-metric-groups! nil state groups)))))
    (is (zero? @calls))))

(deftest typed-null-and-status-slots-retain-declared-order
  (let [columns ["TypedStatus" "TypedValue" "Generic"]
        row {"Generic" {"field" "historical"} "TypedValue" nil "TypedStatus" 3}]
    (is (= [3 nil {"field" "historical"}]
           (first (#'exporter/compact-rows columns [row]))))))

(deftest missing-field-rejection-never-enters-the-driver
  (let [calls (atom 0) state (atom {:durable? true :insert-format :json-compact-each-row})]
    (with-redefs [exporter/execute-durable-sql! (fn [& _] (swap! calls inc))]
      (is (= {:type :otel.exporter.chdb/invalid-compact-row}
             (error-data #(#'exporter/insert-batch! nil state "otel_logs" ["x"] "ignored" [{"other" 1}])))))
    (is (zero? @calls))))

(defn -main [& _]
  (let [result (test/run-tests 'otel.exporter.chdb-compact-wire-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
