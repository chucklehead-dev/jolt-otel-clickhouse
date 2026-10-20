(ns otel.exporter.chdb-preparation-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [otel.exporter.chdb :as e]
            [otel.exporter.chdb.compact-format :as compact]
            [jdbc.chdb.owned-statement :as owned]))

(deftest preparation-does-not-publish-or-replay-producers
  (let [context (#'e/load-experimental-span-codec!) events (atom [])
        checked (fn [] (swap! events conj :snapshot) {:compact-plans :test :owned-statement-output? true})
        context (assoc context :prepare-statement
                       (fn [statement] (swap! events conj :wal) ((:prepare-statement context) statement)))
        result {:status :committed :receipt :unchanged} captured (atom nil)]
    (with-redefs-fn {#'compact/require-order! (fn [& _] (swap! events conj :plan))
                    #'e/execute-durable-sql!
                    (fn [_ sql] (reset! captured sql) (swap! events conj :execute) result)}
      (fn []
        (let [prepared (#'e/prepare-closed-compact-rows :conn "otel_traces" ["x"] ["private-value" 2]
                         checked context (fn [x] (swap! events conj [:project x]) [x]) nil)]
          (is (= [:snapshot :plan [:project "private-value"] [:project 2] :wal] @events))
          (is (nil? @captured))
          (is (= "#<prepared compact insert>" (pr-str prepared)))
          (is (= "#<prepared compact insert>" (binding [*print-dup* true] (pr-str prepared))))
          (is (identical? result (#'e/publish-prepared-compact-insert! prepared)))
          (is (= [:snapshot :plan [:project "private-value"] [:project 2] :wal :snapshot :plan :execute] @events))
          (is (owned/statement? @captured)))))))

(deftest publication-revalidates-after-preparation
  (let [context (#'e/load-experimental-span-codec!) stale? (atom false) calls (atom [])
        checked (fn [] (swap! calls conj :snapshot)
                  (when @stale? (throw (ex-info "stale" {:type ::stale})))
                  {:compact-plans :test :owned-statement-output? true})]
    (with-redefs-fn {#'compact/require-order! (fn [& _] (swap! calls conj :plan))
                    #'e/execute-durable-sql! (fn [& _] (swap! calls conj :execute))}
      (fn []
        (let [prepared (#'e/prepare-closed-compact-rows :conn "otel_traces" ["x"] [1] checked context vector nil)]
          (reset! stale? true)
          (is (= ::stale
                 (try (#'e/publish-prepared-compact-insert! prepared) nil
                      (catch Throwable error (:type (ex-data error))))))
          (is (= [:snapshot :plan :snapshot] @calls)))))))

(deftest preparation-failure-stops-before-next-input-or-publication
  (let [context (#'e/load-experimental-span-codec!) seen (atom []) executed (atom 0)
        source (lazy-seq (cons 1 (lazy-seq (swap! seen conj :next) [2])))
        checked (fn [] {:compact-plans :test :owned-statement-output? true})]
    (with-redefs-fn {#'compact/require-order! (fn [& _])
                    #'e/execute-durable-sql! (fn [& _] (swap! executed inc))}
      (fn []
        (is (= :otel.exporter.chdb/invalid-compact-row
               (try (#'e/execute-closed-compact-rows! :conn "otel_traces" ["x"] source checked context
                                                    (fn [x] (swap! seen conj x) []) nil)
                    nil (catch Throwable error (:type (ex-data error))))))
        (is (= [1] @seen))
        (is (zero? @executed))))))

(deftest publisher-rejects-invalid-values-and-propagates-exact-execution-error
  (let [context (#'e/load-experimental-span-codec!) error (ex-info "execution-control" {})
        snapshots (atom 0) executions (atom 0)
        checked (fn [] (swap! snapshots inc) {:compact-plans :test :owned-statement-output? true})]
    (with-redefs-fn {#'compact/require-order! (fn [& _])
                    #'e/execute-durable-sql! (fn [& _] (swap! executions inc) (throw error))}
      (fn []
        (doseq [invalid [nil {} "SQL"]]
          (is (= :otel.exporter.chdb/invalid-prepared-compact-insert
                 (try (#'e/publish-prepared-compact-insert! invalid) nil
                      (catch Throwable x (:type (ex-data x)))))))
        (is (zero? @snapshots))
        (let [prepared (#'e/prepare-closed-compact-rows :conn "otel_traces" ["x"] [1] checked context vector nil)]
          (is (identical? error
                         (try (#'e/publish-prepared-compact-insert! prepared) nil (catch Throwable x x))))
          (is (= 2 @snapshots))
          (is (= 1 @executions)))))))

(deftest ordinary-string-path-keeps-the-same-materialization-and-check-order
  (let [events (atom []) captured (atom nil) receipt {:status :reconciled}
        checked (fn [] (swap! events conj :snapshot) {:compact-plans :test})
        rows (map (fn [n] (swap! events conj [:row n]) [n]) [1 2])]
    (with-redefs-fn {#'compact/require-order! (fn [& _] (swap! events conj :plan))
                    #'e/execute-durable-sql!
                    (fn [_ sql] (reset! captured sql) (swap! events conj :execute) receipt)}
      (fn []
        (with-bindings {#'e/*json-backend* :configured}
          (let [prepared (#'e/prepare-closed-compact-rows :conn "otel_traces" ["x"] rows checked nil nil nil)]
            (is (= [:snapshot :plan [:row 1] [:row 2]] @events))
            (is (nil? @captured))
            (is (= "#<prepared compact insert>" (pr-str prepared)))
            (is (= "#<prepared compact insert>" (binding [*print-dup* true] (pr-str prepared))))
            (is (identical? receipt (#'e/publish-prepared-compact-insert! prepared)))
            (is (string? @captured))
            (is (str/ends-with? @captured "[1]\n[2]\n"))
            (is (= [:snapshot :plan [:row 1] [:row 2] :snapshot :plan :execute] @events))))))))
