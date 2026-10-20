(ns otel.exporter.chdb-stock-payload-cache-test
  (:require [clojure.test :refer [deftest is]]
            [otel.exporter.chdb :as e]
            [otel.exporter.chdb.compact-format :as compact]
            [jdbc.chdb.owned-statement :as owned]
            [jdbc.core :as jdbc]))

(defn fixture []
  (into {} (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "λ" 1 false i)]) (range 16))))
(defn oracle [m] (into {} (map (fn [[k v]] [k (#'e/value-string v)])) m))
(def valid-options
  {:experimental-span-codec? true :experimental-span-payload-cache? true
   :signals #{:spans} :durable? true :insert-format :json-compact-each-row
   :json-backend :native-guarded-byte-batch :owned-statement-output? true})
(defn checked [] {:compact-plans :test :owned-statement-output? true})
(defn cached-project [convert] (fn [m] [(convert m)]))

(deftest explicit-payload-cache-is-fresh-per-payload-and-preserves-wire
  (let [context (#'e/load-experimental-span-codec!) factories (atom 0) conversions (atom [])
        cached (assoc context :payload-cache-factory
                      (fn [] (let [id (swap! factories inc)]
                               (fn [m] (swap! conversions conj id) (oracle m)))))
        wire (atom []) source (fixture)]
    (with-redefs-fn {#'compact/require-order! (fn [& _])
                    #'e/execute-durable-sql! (fn [_ statement] (swap! wire conj (owned/text statement)))}
      (fn []
        (with-bindings {#'e/*json-backend* :native-guarded-byte-batch}
          (doseq [codec [context cached cached]]
            (#'e/execute-closed-compact-rows! :conn "otel_traces" ["x"] [source source]
                                             checked codec (fn [m] [(#'e/attrs m)]) cached-project)))
        (is (apply = @wire))
        (is (= 2 @factories))
        (is (= [1 1 2 2] @conversions))))))

(deftest encoder-failure-does-not-reuse-the-failed-payload-cache
  (let [context (#'e/load-experimental-span-codec!) fail? (atom true)
        factories (atom 0) conversions (atom []) executed (atom 0) source (fixture)
        context (assoc context
                       :payload-cache-factory
                       (fn [] (let [id (swap! factories inc)]
                                (fn [m] (swap! conversions conj id) (oracle m))))
                       :encode (fn [& args]
                                 (if @fail? (throw (ex-info "injected" {:type ::injected}))
                                     (apply (:encode context) args))))]
    (with-redefs-fn {#'compact/require-order! (fn [& _])
                    #'e/execute-durable-sql! (fn [& _] (swap! executed inc))}
      (fn []
        (is (= ::injected
               (try (#'e/execute-closed-compact-rows! :conn "otel_traces" ["x"] [source source]
                                                    checked context vector cached-project)
                    nil (catch Throwable error (:type (ex-data error))))))
        (is (zero? @executed))
        (reset! fail? false)
        (#'e/execute-closed-compact-rows! :conn "otel_traces" ["x"] [source source]
                                         checked context vector cached-project)
        (is (= 2 @factories))
        (is (= [2 2] @conversions))
        (is (= 1 @executed))))))

(deftest nested-payloads-do-not-share-cache-state
  (let [context (#'e/load-experimental-span-codec!) factories (atom 0) conversions (atom [])
        nested? (atom false) executed (atom 0) source (fixture)
        context (assoc context :payload-cache-factory
                       (fn [] (let [id (swap! factories inc)]
                                (fn [m] (swap! conversions conj id) (oracle m)))))]
    (letfn [(projector [convert]
              (fn [m]
                (when (compare-and-set! nested? false true) (run-payload [m]))
                [(convert m)]))
            (run-payload [rows]
              (#'e/execute-closed-compact-rows! :conn "otel_traces" ["x"] rows
                                               checked context vector projector))]
      (with-redefs-fn {#'compact/require-order! (fn [& _])
                      #'e/execute-durable-sql! (fn [& _] (swap! executed inc))}
        (fn []
          (run-payload [source source])
          (is (= 2 @factories))
          (is (= [2 1 1] @conversions))
          (is (= 2 @executed)))))))

(deftest live-entry-policy-is-checked-before-and-after-cache-conversion
  (let [source (fixture) calls (atom 0) original @#'e/value-string
        context (#'e/load-experimental-span-codec!)]
    (with-bindings {#'e/*json-backend* :native-guarded-byte-batch
                    #'e/*experimental-attribute-transform* (:attributes context)}
      (let [convert (#'e/payload-attrs-converter
                      (fn [_] (swap! calls inc) (throw (ex-info "must not call" {}))))]
        (with-redefs [e/value-string (constantly "changed")]
          (is (= (zipmap (keys source) (repeat "changed")) (convert source)))
          (is (zero? @calls))))
      (with-redefs [e/value-string original]
        (let [convert (#'e/payload-attrs-converter
                        (fn [m] (let [result (oracle m)]
                                  (alter-var-root #'e/value-string (constantly (constantly "late")))
                                  result)))]
          (is (= (zipmap (keys source) (repeat "late")) (convert source))))))))

(deftest invalid-or-unavailable-cache-rejects-before-storage
  (let [opens (atom 0) resolve-var ns-resolve]
    (with-redefs [jdbc/connection (fn [& _] (swap! opens inc) (throw (ex-info "storage" {})))]
      (doseq [options [{:experimental-span-payload-cache? true}
                       (assoc valid-options :experimental-span-payload-cache? "true")
                       (assoc valid-options :experimental-span-payload-cache? nil)]]
        (is (= :otel.exporter.chdb/invalid-experimental-span-payload-cache
               (try (e/exporter options) nil (catch Throwable error (:type (ex-data error)))))))
      (with-redefs [ns-resolve (fn [namespace name]
                                (when-not (= ['otel.any-value 'scalar-string-map-payload-converter]
                                             [namespace name])
                                  (resolve-var namespace name)))]
        (is (= :otel.exporter.chdb/experimental-span-payload-cache-unavailable
               (try (e/exporter valid-options) nil (catch Throwable error (:type (ex-data error)))))))
      (is (zero? @opens)))))
