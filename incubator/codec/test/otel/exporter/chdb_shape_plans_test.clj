(ns otel.exporter.chdb-shape-plans-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io] [clojure.string :as str]
            [otel.exporter.chdb :as exporter]
            [otel.any-value :as any] [jdbc.core :as jdbc]
            [jolt.codec.scalar-text.experimental :as shapes]
            [otel.exporter.chdb.compact-format :as compact]
            [jdbc.chdb.owned-statement :as owned]
            [otel.exporter.chdb-stock-payload-cache-test :as existing]))

(def valid-options (assoc existing/valid-options :experimental-span-shape-plans? true))
(defn failure [f] (try (f) nil (catch Throwable e e)))
(defn fixture [] (into (sorted-map) (existing/fixture)))

(deftest real-preflight-selects-new-sdk-and-needs-no-storage
  (let [calls (atom 0)]
    (with-redefs [jdbc/connection (fn [& _] (swap! calls inc))]
      (is (true? (exporter/check-experimental-span-codec!
                  {:experimental-span-payload-cache? true :experimental-span-shape-plans? true})))
      (is (zero? @calls)))))

(deftest actual-plan-conversion-preserves-wire-across-payloads
  (let [learns (atom 0) hits (atom 0) factory (shapes/native-plan-factory)
        plain (#'exporter/load-experimental-span-codec! true true true false)
        planned (with-redefs [shapes/native-plan-factory
                               (fn [] (fn [& args]
                                        (let [dispatch (apply factory args)]
                                          (fn [op value]
                                            (let [result (dispatch op value)]
                                              (when (and (= op 0) result) (swap! hits inc))
                                              (when (and (= op 1) result) (swap! learns inc))
                                              result)))))]
                  (#'exporter/load-experimental-span-codec! true true true true))
        m (fixture) records [m (into (sorted-map) m) (assoc m "key-0" "changed")]
        wire (atom [])]
    (with-redefs-fn {#'compact/require-order! (fn [& _])
                    #'exporter/execute-durable-sql! (fn [_ statement] (swap! wire conj (owned/text statement)))}
      (fn []
        (with-bindings {#'exporter/*json-backend* :native-guarded-byte-batch}
          (#'exporter/execute-closed-compact-rows! :conn "otel_traces" ["x"] records
            existing/checked plain vector existing/cached-project)
          (dotimes [_ 2]
            (#'exporter/execute-closed-compact-rows! :conn "otel_traces" ["x"] records
              existing/checked planned vector existing/cached-project)))))
    (is (= 3 (count @wire))) (is (apply = @wire))
    (is (= 2 @learns) "each payload independently learns once")
    (is (= 4 @hits) "equal/distinct roots and changed values must use the learned plan")))

(deftest invalid-or-missing-capability-rejects-before-storage-without-canary
  (let [opens (atom 0) resolve-var ns-resolve]
    (with-redefs [jdbc/connection (fn [& _] (swap! opens inc))]
      (doseq [options [(assoc valid-options :experimental-span-shape-plans? "PRIVATE-CANARY")
                       (assoc valid-options :experimental-span-payload-cache? false)
                       {:experimental-span-shape-plans? true}]]
        (let [e (failure #(exporter/exporter options))]
          (is (= ::exporter/invalid-experimental-span-shape-plans (:type (ex-data e))))
          (is (not (.contains (str e) "PRIVATE-CANARY")))))
      (with-redefs [ns-resolve (fn [namespace name]
                                (when-not (= ['jolt.codec.scalar-text.experimental 'native-plan-factory]
                                             [namespace name])
                                  (resolve-var namespace name)))]
        (is (= ::exporter/experimental-span-shape-plans-unavailable
               (:type (ex-data (failure #(exporter/exporter valid-options)))))))
      (is (zero? @opens)))))

(deftest preflight-rejects-shapes-without-payload-cache
  (is (= ::exporter/invalid-experimental-span-codec-preflight
         (:type (ex-data (failure #(exporter/check-experimental-span-codec!
                                    {:experimental-span-shape-plans? true})))))))

(defn -main [& [expected-sdk expected-codec]]
  (when (or expected-sdk expected-codec)
    (assert (and expected-sdk expected-codec))
    (assert (str/includes? (str (io/resource "otel/any_value.clj")) expected-sdk))
    (assert (str/includes? (str (io/resource "jolt/codec/scalar_text/experimental.clj")) expected-codec)))
  (let [r (run-tests 'otel.exporter.chdb-shape-plans-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
