(ns otel.exporter.chdb-prefixed-payload-test
  (:require [clojure.test :as test :refer [deftest is]]
            [clojure.data.json :as json]
            [jdbc.chdb.json-each-row :as encoder]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-compact-wire-test :as wire]))

;; Native mode is explicit and must fail, not skip, on an unavailable runtime.
(def ^:dynamic *backend* :configured)

(defn- run-insert
  ([backend format rows limit direct?]
   (run-insert backend format rows limit direct? (atom [])))
  ([backend format rows limit direct? sql]
  (let [columns ["x"]
        state (atom {:durable? true :insert-format format
                     :compact-plans {"otel_logs" (wire/confirmed-plan :connection columns)}})]
    (with-bindings {#'exporter/*json-backend* backend}
      (with-redefs [exporter/max-insert-bytes limit
                    exporter/execute-durable-sql! (fn [connection text] (swap! sql conj [connection text]))]
        (if direct?
          (#'exporter/execute-closed-compact-rows!
            :connection "otel_logs" columns rows #(deref state))
          (#'exporter/insert-batch! :connection state "otel_logs" columns "insert into otel_logs" rows))))
    @sql)))

(deftest prefixed-path-matches-independent-sql
  (doseq [format [:json-each-row :json-compact-each-row]
          values [[] ["β😀\n/\\"] [1 [false nil 9007199254740993]]]
          :let [columns ["x"] rows (mapv #(hash-map "x" %) values)
                compact? (= format :json-compact-each-row)
                physical (if compact? (mapv vector values) rows)
                prefix (if compact?
                         "insert into otel_logs (`x`) FORMAT JSONCompactEachRow\n"
                         "insert into otel_logs FORMAT JSONEachRow\n")
                expected [[:connection (str prefix (apply str (map #(str (json/write-str %) "\n") physical)))]]]]
    (is (= expected (run-insert *backend* format rows 10000 false)))
    (when compact?
      (is (= expected (run-insert *backend* format physical 10000 true))))))

(deftest prefixed-view-and-budget-exclude-prefix
  (doseq [direct? [false true]
          format (if direct? [:json-compact-each-row] [:json-each-row :json-compact-each-row])]
    (let [views (atom [])
          value (reify json/JSONWriter
                  (-write [_ sink _] (swap! views conj (.toString sink)) (.write sink "true")))
          rows [(if direct? [value] {"x" value})]
          reference (run-insert :configured format rows 10000 direct?)
          expected @views
          _ (reset! views [])
          row-bytes (if (= format :json-each-row) 11 7)]
      (is (= reference (run-insert *backend* format rows row-bytes direct?)))
      (is (= expected @views))
      (is (= 1 (count @views)))
      (let [error (try (run-insert *backend* format rows (dec row-bytes) direct?)
                       nil (catch Throwable e e))]
        (is (= {:limit (dec row-bytes)} (ex-data error)))))))

(deftest overflow-stops-before-next-row-or-writer
  (doseq [direct? [false true]
          format (if direct? [:json-compact-each-row] [:json-each-row :json-compact-each-row])]
    (let [effects (atom []) executions (atom [])
          rows (letfn [(step [n] (lazy-seq (swap! effects conj n)
                                          (cons (if direct? [true] {"x" true})
                                                (lazy-seq (step (inc n))))))] (step 0))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (run-insert *backend* format rows 1 direct? executions)))
      (is (= [0] @effects))
      (is (empty? @executions)))))

(deftest closed-plan-is-rechecked-after-serialization
  (let [columns ["x"] state (atom {:compact-plans {"otel_logs" (wire/confirmed-plan :connection columns)}})
        calls (atom 0)
        value (reify json/JSONWriter
                (-write [_ sink _] (swap! state assoc :changed? true) (.write sink "true")))
        checked #(let [snapshot @state]
                   (when (:changed? snapshot) (throw (ex-info "Changed test plan" {:type ::changed})))
                   snapshot)]
    (with-bindings {#'exporter/*json-backend* *backend*}
      (with-redefs [exporter/execute-durable-sql! (fn [& _] (swap! calls inc))]
        (is (= {:type ::changed}
               (try (#'exporter/execute-closed-compact-rows!
                      :connection "otel_logs" columns [[value]] checked)
                    nil (catch clojure.lang.ExceptionInfo e (ex-data e)))))
        (is (zero? @calls))))))

(deftest prefixed-context-is-released-on-success-and-error
  (let [opened (atom []) closed (atom []) prefix "SQL prefix\n"]
    (with-redefs [encoder/open-encoder
                  (fn [_] (let [context (Object.)] (swap! opened conj context) context))
                  encoder/encode-limited-text! (fn [& _] (throw (Exception. "Unprefixed encoder selected")))
                  encoder/encode-limited-prefixed-text!
                  (fn [_ actual rows limit]
                    (is (= prefix actual)) (is (= (* 8 1024 1024) limit))
                    (if (= rows [:overflow])
                      (throw (ex-info "bounded" {:type :jdbc.chdb.json-each-row/output-limit}))
                      (str actual "true\n")))
                  encoder/close! #(swap! closed conj %)]
      (with-bindings {#'exporter/*json-backend* :native-guarded-byte-batch}
        (is (= (str prefix "true\n") (#'exporter/json-each-row-payload [true] prefix)))
        (is (= {:limit (* 8 1024 1024)}
               (try (#'exporter/json-each-row-payload [:overflow] prefix)
                    nil (catch clojure.lang.ExceptionInfo e (ex-data e)))))))
    (is (= 2 (count @opened))) (is (= @opened @closed))
    (is (not (identical? (first @opened) (second @opened))))))

(deftest native-generic-and-closed-paths-really-delegate-prefix
  (when (= :native-guarded-byte-batch *backend*)
    (let [old encoder/encode-limited-prefixed-text! calls (atom 0)]
      (with-redefs [encoder/encode-limited-prefixed-text!
                    (fn [& args] (swap! calls inc) (apply old args))]
        (run-insert *backend* :json-each-row [{"x" 1}] 10000 false)
        (run-insert *backend* :json-compact-each-row [{"x" 1}] 10000 false)
        (run-insert *backend* :json-compact-each-row [[1]] 10000 true))
      (is (= 3 @calls)))))

(deftest other-backends-retain-unprefixed-encoding
  (doseq [backend [:configured :native-guarded :native-guarded-string-cache]
          direct? [false true]]
    (let [calls (atom 0)]
      (with-redefs [encoder/open-encoder (fn [_] :context)
                    encoder/close! (fn [_])
                    encoder/encode-limited-prefixed-text!
                    (fn [& _] (throw (Exception. "Unexpected prefix delegation")))
                    encoder/encode-limited-text!
                    (fn [_ rows _] (swap! calls inc)
                      (apply str (map #(str (json/write-str %) "\n") rows)))]
        (is (= [[:connection "insert into otel_logs (`x`) FORMAT JSONCompactEachRow\n[1]\n"]]
               (run-insert backend :json-compact-each-row
                           (if direct? [[1]] [{"x" 1}]) 10000 direct?)))
        (is (= (if (= backend :configured) 0 1) @calls))))))

(defn -main [& args]
  (when-not (or (empty? args) (= ["native"] (vec args)))
    (throw (ex-info "Invalid prefix test mode" {})))
  (binding [*backend* (if (seq args) :native-guarded-byte-batch :configured)]
    (let [result (test/run-tests 'otel.exporter.chdb-prefixed-payload-test)]
      (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))))
