(ns otel.exporter.chdb-owned-output-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [jdbc.chdb.owned-statement :as owned]
            [jdbc.chdb.durable :as durable]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-compact-wire-test :as wire]))

(defn insert [rows]
  (let [state (atom {:durable? true :insert-format :json-compact-each-row
                     :owned-statement-output? true
                     :compact-plans {"otel_logs" (wire/confirmed-plan :connection ["x"])}})]
    (with-bindings {#'exporter/*json-backend* :native-guarded-byte-batch}
      (@#'exporter/execute-closed-compact-rows! :connection "otel_logs" ["x"] rows #(deref state)))))

(deftest owned-compact-output-reaches-owned-publication-without-text
  (let [requests (atom [])]
    (with-redefs [owned/text (fn [_] (throw (ex-info "unexpected SQL text conversion" {})))
                  durable/execute-and-flush! (fn [& _] (throw (ex-info "unexpected text publication" {})))
                  durable/execute-owned-and-flush!
                  (fn [connection statement]
                    (is (= :connection connection))
                    (is (owned/statement? statement))
                    (swap! requests conj statement) {:status :committed})]
      (is (= {:status :committed} (insert [[42]]))))
    (is (= 1 (count @requests)))
    (is (= (str (@#'exporter/compact-insert-query "otel_logs" ["x"])
                " FORMAT JSONCompactEachRow\n[42]\n")
           (owned/text (first @requests))))))

(deftest unsupported-output-decodes-once-and-keeps-confirmation-check
  (let [calls (atom 0) requests (atom [])
        value (reify json/JSONWriter
                (-write [_ out _] (swap! calls inc) (.write out "\"β\"")))]
    (with-redefs [durable/execute-owned-and-flush! (fn [& _] (throw (ex-info "unexpected owned publication" {})))
                  durable/execute-and-flush!
                  (fn [_ sql] (swap! requests conj sql) {:status :reconciled})]
      (is (= {:status :reconciled} (insert [[value]]))))
    (is (= 1 @calls))
    (is (= [(str (@#'exporter/compact-insert-query "otel_logs" ["x"])
                 " FORMAT JSONCompactEachRow\n[\"β\"]\n")] @requests)))
  (with-redefs [durable/execute-owned-and-flush! (fn [& _] {:status :admitted})]
    (is (= :otel.exporter.chdb/durable-atomic-execution-unconfirmed
           (:type (wire/error-data #(insert [[42]])))))))

(deftest invalid-owned-selection-fails-before-encoding-or-acquisition
  (doseq [options [{:owned-statement-output? true}
                   {:owned-statement-output? :yes}
                   {:owned-statement-output? true :durable? true :json-backend :configured}
                   {:owned-statement-output? true :durable? true :json-backend :native-guarded-byte-batch}]]
    (is (= :otel.exporter.chdb/invalid-owned-statement-output
           (:type (wire/error-data #(exporter/exporter options)))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'otel.exporter.chdb-owned-output-test
                                      'otel.exporter.chdb-compact-wire-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
