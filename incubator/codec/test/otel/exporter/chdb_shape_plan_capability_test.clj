(ns otel.exporter.chdb-shape-plan-capability-test
  "Actual old SDK/library selection control. No mocked namespace capability."
  (:require [clojure.java.io :as io] [clojure.string :as str]
            [otel.exporter.chdb :as exporter] [jdbc.core :as jdbc]))

(defn -main [& [expected-sdk expected-codec]]
  (assert (and expected-sdk expected-codec))
  (assert (str/includes? (str (io/resource "otel/any_value.clj")) expected-sdk))
  (assert (str/includes? (str (io/resource "jolt/codec/experimental.clj")) expected-codec))
  (let [opens (atom 0)
        error (with-redefs [jdbc/connection (fn [& _] (swap! opens inc))]
                (try
                  (exporter/exporter {:experimental-span-codec? true :experimental-span-payload-cache? true
                                      :experimental-span-shape-plans? true :signals #{:spans}
                                      :durable? true :insert-format :json-compact-each-row
                                      :json-backend :native-guarded-byte-batch :owned-statement-output? true})
                  nil (catch Throwable e e)))]
    (assert (= ::exporter/experimental-span-shape-plans-unavailable (:type (ex-data error))))
    (assert (nil? (ex-cause error)))
    (assert (zero? @opens))
    (println :actual-old-capability-rejected-before-jdbc :sdk expected-sdk :codec expected-codec)))
