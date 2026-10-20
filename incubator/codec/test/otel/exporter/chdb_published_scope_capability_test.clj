(ns otel.exporter.chdb-published-scope-capability-test
  "Real old/current library capability control; no storage acquisition."
  (:require [otel.exporter.chdb :as exporter]
            [jdbc.core :as jdbc]))

(defn -main [expected]
  (assert (contains? #{"present" "absent"} expected))
  (let [opens (atom 0)
        outcome (with-redefs [jdbc/connection
                             (fn [& _]
                               (swap! opens inc)
                               (throw (ex-info "Unexpected storage acquisition" {})))]
                  (try
                    (let [context (if (= expected "absent")
                                    ;; Real old dependency must reject through
                                    ;; the public constructor before opening.
                                    (exporter/exporter
                                      {:db-spec {:vendor "chdb" :name "/must-not-open"}
                                       :durable? true :signals #{:spans}
                                       :json-backend :native-guarded-byte-batch
                                       :insert-format :json-compact-each-row
                                       :owned-statement-output? true
                                       :experimental-span-codec? true
                                       :experimental-span-payload-cache? true
                                       :experimental-span-flat-map-cache? true
                                       :experimental-span-nested-fragment-cache? true})
                                    (#'exporter/load-experimental-span-codec! true true true))]
                      (assert (every? ifn? (map context [:encode :nested :prepare-statement])))
                      :present)
                    (catch Throwable error
                      (assert (= :otel.exporter.chdb/experimental-span-nested-fragments-unavailable
                                 (:type (ex-data error))))
                      :absent)))]
    (assert (= (keyword expected) outcome))
    (assert (zero? @opens))
    (prn {:published-scope-capability outcome :storage-opens @opens})))
