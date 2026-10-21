(ns otel.exporter.chdb-scalar-views-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io] [clojure.string :as str]
            [otel.exporter.chdb :as e] [jdbc.core :as jdbc]
            [otel.exporter.chdb-stock-payload-cache-test :as existing]
            [jolt.codec.experimental :as codec]))

(def options (assoc existing/valid-options :experimental-span-shape-plans? true
                     :experimental-span-scalar-views? true))
(defn preflight [] {:experimental-span-payload-cache? true
                   :experimental-span-shape-plans? true :experimental-span-scalar-views? true})
(defn failure-type [f] (try (f) nil (catch Throwable e (:type (ex-data e)))))

(deftest view-capabilities-are-qualified-before-storage
  (let [opens (atom 0) resolve-var ns-resolve]
    (with-redefs [jdbc/connection (fn [& _] (swap! opens inc))]
      (is (true? (e/check-experimental-span-codec! (preflight))))
      (doseq [missing [['otel.any-value 'scalar-text-payload-projector]
                       ['jolt.codec.scalar-text.experimental 'native-view-plan-factory]
                       ['jolt.codec.experimental 'native-scalar-text-view-box]]]
        (with-redefs [ns-resolve (fn [n v] (when-not (= missing [n v]) (resolve-var n v)))]
          (is (= ::e/experimental-span-scalar-views-unavailable
                 (failure-type #(e/exporter options))))))
      (is (zero? @opens)))))

(deftest invalid-view-options-cannot-select-a-downgrade
  (doseq [bad [(assoc options :experimental-span-scalar-views? "PRIVATE-CANARY")
               (assoc options :experimental-span-shape-plans? false)
               (assoc options :experimental-span-payload-cache? false)
               {:experimental-span-scalar-views? true}]]
    (is (= ::e/invalid-experimental-span-scalar-views (failure-type #(e/exporter bad)))))
  (is (= ::e/invalid-experimental-span-codec-preflight
         (failure-type #(e/check-experimental-span-codec! {:experimental-span-scalar-views? true}))))
  (let [resolve-var ns-resolve calls (atom 0)]
    (with-redefs [ns-resolve (fn [n v]
                              (when (contains? #{'scalar-text-payload-projector 'native-view-plan-factory
                                                 'native-scalar-text-view-box} v) (swap! calls inc))
                              (resolve-var n v))]
      (is (true? (e/check-experimental-span-codec!
                  {:experimental-span-payload-cache? true :experimental-span-shape-plans? true})))
      (is (zero? @calls) "disabled path must not resolve new capabilities"))))

(deftest opaque-output-preserves-wire-and-changed-exporter-policy-fallback
  (let [plain (#'e/load-experimental-span-codec! true true true true)
        views (#'e/load-experimental-span-codec! true true true true true)
        m (into (sorted-map) (existing/fixture))
        values [m (into (sorted-map) m) (assoc m "key-2" -42)]
        bytes (fn [context]
                (let [convert (#'e/payload-attrs-converter ((:payload-cache-factory context)))]
                  (vec ((:encode context) "" values #(vector (convert %)) 100000))))]
    (is (= (bytes plain) (bytes views)))
    (let [project ((:payload-cache-factory views))]
      (is (map? (project m)))
      (let [view (project (into (sorted-map) m))]
        (is view "nil/false decline is not proof that an opaque view was selected")
        (is (not (map? view)))))
    (let [normal (bytes views)]
      (with-redefs-fn {#'e/value-string (fn [v] (str "custom:" v))}
        #(do (is (= (bytes plain) (bytes views)))
             (is (not= normal (bytes views))))))
    (is (= (bytes plain) (bytes views)))))

(defn -main [& [expected-sdk expected-codec]]
  (when (or expected-sdk expected-codec)
    (assert (and expected-sdk expected-codec))
    (assert (str/includes? (str (io/resource "otel/any_value.clj")) expected-sdk))
    (assert (str/ends-with? (str (io/resource "jolt/codec/scalar_text/experimental.clj"))
                           (str "/" expected-codec "/incubator/codec/src/jolt/codec/scalar_text/experimental.clj"))))
  (let [r (run-tests 'otel.exporter.chdb-scalar-views-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
