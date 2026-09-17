(ns otel.exporter.chdb-test-support
  "Test-only ordinary shim and diagnostic statement views; never transport fallback."
  (:require [db.jdbc] [db.driver :as driver] [db.jdbc-shim :as shim]
            [clojure.string :as str] [jdbc.chdb.native :as native]
            [otel.exporter.chdb.schema :as schema]))

(defn call-with-qualified-native [f]
  ;; Only fake constructor fixtures use this; real native/socket gates probe
  ;; the loaded package through the canonical functions without these stubs.
  (with-redefs [native/ensure-loaded! (fn [] nil)
                native/chdb-version (fn [] "26.7.3")]
    (f)))

(defn owned-fixture []
  (let [closes (atom 0)
        drv (reify driver/Driver
              (descriptor [_] {:id :chdb :aliases #{"export-test"} :uri-prefixes ["export-test:"]
                               :product-name "ordinary export test"
                               :capabilities {:transactions :none :generated-keys :none}})
              (open-handle [_ _] {:connection :mock :closed? (atom false) :lock (Object.)})
              (close-handle [_ h] (swap! closes inc) (reset! (:closed? h) true))
              (execute-handle [_ _ _ _] (throw (ex-info "Unexpected test JDBC execution" {}))))]
    {:connection (with-redefs [driver/resolve-driver (fn [_] drv)]
                   (shim/connection "export-test:memory")) :close-count closes}))

(defn connection [] (:connection (owned-fixture)))

(defn exporter-state [state]
  (merge {:span-insert-columns (into schema/clickstack-trace-insert-columns ["EventsJSON" "LinksJSON"])
          :log-insert-columns schema/clickstack-log-insert-columns} state))

(defn statement-view [table columns payload]
  ;; Retain old pure-test SQL-shaped output oracles while spying the new API.
  ;; This function never executes SQL and is not production transport logic.
  (str "insert into " table
       (when columns (str " (" (str/join ", " columns) ")"))
       " FORMAT JSONEachRow\n" payload))
