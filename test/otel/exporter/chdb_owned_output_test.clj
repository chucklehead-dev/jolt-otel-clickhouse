(ns otel.exporter.chdb-owned-output-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [jdbc.chdb.owned-statement :as owned]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.wal :as wal]
            [jdbc.chdb.json-each-row :as encoder]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-scoped-s3-native-test :as scoped]
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
  (let [effects (atom [])]
   (with-redefs [encoder/open-encoder (fn [& _] (swap! effects conj :encoder))
                 jdbc/connection (fn [& _] (swap! effects conj :connection))]
    (doseq [options [{:owned-statement-output? true}
                   {:owned-statement-output? :yes}
                   {:owned-statement-output? nil}
                   {:owned-statement-output? true :durable? true :json-backend :configured}
                   {:owned-statement-output? true :durable? true :json-backend :native-guarded-byte-batch}]]
    (is (= :otel.exporter.chdb/invalid-owned-statement-output
           (:type (wire/error-data #(exporter/exporter options))))))
    (is (empty? @effects)))))

(def valid-owned-options
  {:owned-statement-output? true :durable? true
   :json-backend :native-guarded-byte-batch :insert-format :json-compact-each-row})

(deftest owned-stack-preflight-precedes-storage-and-always-releases-context
  (doseq [mode [:missing-collector :missing-snapshot :throwing-snapshot :available]]
    (let [calls (atom []) original owned/try-snapshot
          opened (ex-info "Synthetic storage acquisition boundary" {:type ::storage})]
      (with-redefs [encoder/open-encoder
                    (fn [_] (swap! calls conj :encoder)
                      (when-not (= mode :missing-collector) {:native-prefixed-byte-writer identity}))
                    encoder/close! (fn [_] (swap! calls conj :close))
                    owned/try-snapshot
                    (fn [bytes]
                      (swap! calls conj :snapshot)
                      (is (zero? (alength bytes)))
                      (case mode
                        :missing-snapshot nil
                        :throwing-snapshot (throw (ex-info "Synthetic private loader details" {}))
                        (original bytes)))
                    jdbc/connection (fn [& _] (swap! calls conj :storage) (throw opened))]
        (is (= (if (= mode :available) ::storage :otel.exporter.chdb/owned-statement-unavailable)
               (:type (wire/error-data #(exporter/exporter valid-owned-options)))))
        (is (= (cond (= mode :missing-collector) [:encoder :close]
                     (= mode :available) [:encoder :snapshot :close :storage]
                     :else [:encoder :snapshot :close]) @calls))))))

(deftest default-output-does-not-probe-owned-snapshot
  (let [effects (atom [])]
    (with-redefs [encoder/open-encoder (fn [_] {:native-prefixed-byte-writer identity})
                  encoder/close! (fn [_] (swap! effects conj :close))
                  owned/try-snapshot (fn [_] (throw (Exception. "Unexpected owned ABI probe")))
                  jdbc/connection (fn [& _] (swap! effects conj :storage)
                                    (throw (ex-info "Synthetic storage boundary" {:type ::storage})))]
      (is (= ::storage (:type (wire/error-data
                                #(exporter/exporter (dissoc valid-owned-options :owned-statement-output?))))))
      (is (= [:close :storage] @effects)))))

(deftest owned-exporter-preserves-exact-v1-wal-bytes
  (doseq [value ["quote\" slash/ backslash\\ newline\n\t" [false nil 9007199254740993]
                 (apply str (repeat 65536 "a"))]]
    (let [statements (atom []) row [value]
          expected (str "insert into otel_logs (`x`) FORMAT JSONCompactEachRow\n"
                        (json/write-str row) "\n")]
      (with-redefs [owned/text (fn [_] (throw (Exception. "Unexpected text materialization")))
                    durable/execute-and-flush! (fn [& _] (throw (Exception. "Unexpected text execution")))
                    durable/execute-owned-and-flush!
                    (fn [_ statement] (swap! statements conj statement) {:status :committed})]
        (is (= {:status :committed} (insert [row]))))
      (is (= 1 (count @statements)))
      (let [out (java.io.ByteArrayOutputStream.)]
        (wal/write-prepared! out (owned/prepared-wal (first @statements)))
        (is (java.util.Arrays/equals (wal/portable-line-bytes expected) (.toByteArray out)))))))

(deftest owned-and-text-results-both-require-confirmation
  (doseq [status [:admitted :pending :failed nil]
          value [42 (reify json/JSONWriter (-write [_ out _] (.write out "\"β\"")))]]
    (with-redefs [durable/execute-owned-and-flush! (fn [& _] {:status status})
                  durable/execute-and-flush! (fn [& _] {:status status})]
      (is (= :otel.exporter.chdb/durable-atomic-execution-unconfirmed
             (:type (wire/error-data #(insert [[value]]))))))))

(deftest owned-overflow-and-serialization-failure-never-replay-or-execute
  (doseq [failure [:limit :producer]]
    (let [effects (atom []) executions (atom 0)
          sentinel (ex-info "Synthetic row failure" {:type ::producer})
          value (reify json/JSONWriter
                  (-write [_ out _] (swap! effects conj :write)
                    (if (= failure :producer) (throw sentinel) (.write out "true"))))
          rows (lazy-seq (cons [value] (lazy-seq (swap! effects conj :later) (list [42]))))]
      (with-redefs [exporter/max-insert-bytes (if (= failure :limit) 1 10000)
                    durable/execute-owned-and-flush! (fn [& _] (swap! executions inc))
                    durable/execute-and-flush! (fn [& _] (swap! executions inc))]
        (let [error (try (insert rows) nil (catch Throwable e e))]
          (is (if (= failure :producer) (identical? sentinel error) (= {:limit 1} (ex-data error))))))
      (is (= [:write] @effects)) (is (zero? @executions)))))

(deftest full-row-recovery-receipt-cannot-silently-omit-metric-tables
  (let [selections @#'scoped/selections
        changed (atom false)
        fetch (fn [_ sql]
                [{:visible 1 :otherwise-unchecked
                  (if (and @changed (.contains sql "otel_metrics_histogram")) 2 1)}])]
    (with-redefs [jdbc/fetch fetch]
      (let [before (#'scoped/full-row-receipt :connection)]
        (is (= #{:spans :logs :metrics :gauges :histograms} (set (keys before))))
        (doseq [missing [:gauges :histograms]]
          (with-redefs [scoped/selections (dissoc selections missing)]
            (is (= {:check :all-physical-table-receipts}
                   (wire/error-data #(#'scoped/full-row-receipt :connection))))))
        (reset! changed true)
        (let [after (#'scoped/full-row-receipt :connection)]
          (is (= (get-in before [:histograms :rows]) (get-in after [:histograms :rows])))
          (is (not= (get-in before [:histograms :sha256]) (get-in after [:histograms :sha256]))))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'otel.exporter.chdb-owned-output-test
                                      'otel.exporter.chdb-compact-wire-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
