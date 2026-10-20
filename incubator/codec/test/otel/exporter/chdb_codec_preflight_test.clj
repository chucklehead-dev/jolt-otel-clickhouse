(ns otel.exporter.chdb-codec-preflight-test
  (:require [clojure.test :refer [deftest is]]
            [jdbc.core :as jdbc]
            [jdbc.chdb.json-each-row :as row-encoder]
            [jdbc.chdb.owned-statement :as owned]
            [otel.exporter.chdb :as exporter]))

(defn- failure [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest real-capability-check-needs-no-storage-or-legacy-writer
  (let [effects (atom [])]
    (with-redefs [jdbc/connection (fn [& _] (swap! effects conj :jdbc) (throw (Exception. "Unexpected JDBC")))
                  row-encoder/open-encoder (fn [& _] (swap! effects conj :legacy) (throw (Exception. "Unexpected legacy writer")))]
      (is (true? (exporter/check-experimental-span-codec!)))
      (is (true? (exporter/check-experimental-span-codec!
                  {:experimental-span-payload-cache? true
                   :experimental-span-flat-map-cache? true
                   :experimental-span-nested-fragment-cache? true})))
      (is (empty? @effects)))))

(deftest preflight-options-are-closed-and-errors-do-not-echo-values
  (let [loader (ns-resolve 'otel.exporter.chdb 'load-experimental-span-codec!)
        calls (atom 0)]
    (with-redefs-fn {loader (fn [& _] (swap! calls inc))}
      (fn []
        (doseq [options [nil [] {:unexpected "PRIVATE-CANARY"}
                        {:experimental-span-payload-cache? nil}
                        {:experimental-span-flat-map-cache? 1}
                        {:experimental-span-nested-fragment-cache? "PRIVATE-CANARY"}]]
          (let [error (failure #(exporter/check-experimental-span-codec! options))]
            (is (= ::exporter/invalid-experimental-span-codec-preflight (:type (ex-data error))))
            (is (not (.contains (str error) "PRIVATE-CANARY")))))))
    (is (zero? @calls))))

(deftest missing-owned-capability-rejects-without-storage
  (let [calls (atom 0)]
    (with-redefs [owned/try-snapshot (constantly nil)
                  jdbc/connection (fn [& _] (swap! calls inc))]
      (is (= ::exporter/owned-statement-unavailable
             (:type (ex-data (failure #(exporter/check-experimental-span-codec!))))))
      (is (zero? @calls)))))
