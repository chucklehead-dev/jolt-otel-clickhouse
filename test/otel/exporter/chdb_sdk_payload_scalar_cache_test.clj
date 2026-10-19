(ns otel.exporter.chdb-sdk-payload-scalar-cache-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [otel.exporter.chdb :as e]
            [jdbc.chdb.json-each-row :as encoder]
            [otel.exporter.chdb-sdk-scalar-map-test :as scalar]))

(def factory-calls (atom 0))
(def conversion-calls (atom []))

(defn cache-factory []
  (let [id (swap! factory-calls inc)]
    (fn [m]
      (swap! conversion-calls conj id)
      (scalar/oracle m))))

(defn payload [backend]
  (with-bindings {#'e/*json-backend* backend}
    (#'e/json-each-row-payload
      (map (fn [m] [(#'e/attrs m)]) (repeat 2 (scalar/fixture 16))))))

(deftest optional-converter-is-created-once-per-serial-payload
  (reset! factory-calls 0) (reset! conversion-calls [])
  (let [reference (with-redefs [e/sdk-scalar-map-payload-factory (delay nil)]
                    (payload :native-guarded-byte-batch))]
    (with-redefs [e/sdk-scalar-map-payload-factory (delay #'cache-factory)]
      (is (= reference (payload :native-guarded-byte-batch)))
      (is (= reference (payload :native-guarded-byte-batch)))
      (is (= 2 @factory-calls))
      (is (= [1 1 2 2] @conversion-calls))
      (is (nil? @#'e/*sdk-scalar-map-payload-converter*))
      (is (= reference (payload :configured)))
      (is (= 2 @factory-calls)))))

(deftest changed-entry-converters-remain-live-before-and-after-cache-call
  (let [m (scalar/fixture 16) original @#'e/value-string calls (atom 0)]
    (with-bindings {#'e/*json-backend* :native-guarded-byte-batch
                    #'e/*sdk-scalar-map-payload-converter*
                    (fn [_] (swap! calls inc) (throw (ex-info "must-not-call" {})))}
      (with-redefs [e/value-string (constantly "changed")]
        (is (= (zipmap (keys m) (repeat "changed")) (#'e/attrs m)))
        (is (zero? @calls))))
    (with-redefs [e/value-string original]
      (with-bindings {#'e/*json-backend* :native-guarded-byte-batch
                      #'e/*sdk-scalar-map-payload-converter*
                      (fn [value]
                        (let [converted (scalar/oracle value)]
                          (alter-var-root #'e/value-string (constantly (constantly "late")))
                          converted))}
        (is (= (zipmap (keys m) (repeat "late")) (#'e/attrs m)))))))

(deftest missing-or-declining-capability-retains-the-original-sdk-fallback
  (let [m (scalar/fixture 16) seen (atom 0)]
    (with-bindings {#'e/*json-backend* :native-guarded-byte-batch
                    #'e/*sdk-scalar-map-payload-converter* (constantly nil)}
      (with-redefs [e/sdk-sorted-scalar-map-converter (delay nil)
                    e/sdk-scalar-map-converter
                    (delay #'cache-factory)]
        ;; Existing helper dispatch must remain available even after a cache
        ;; decline; replacing a live SDK helper is not a cache-hit permission.
        (with-redefs [cache-factory (fn [value] (swap! seen inc) (scalar/oracle value))]
          (is (= (scalar/oracle m) (#'e/attrs m)))
          (is (= 1 @seen)))))))

(deftest exception-unwinds-the-cache-binding-and-closes-the-encoder
  (let [closed (atom 0)]
    (with-redefs [e/sdk-scalar-map-payload-factory (delay #'cache-factory)
                  encoder/open-encoder (fn [_] :owned)
                  encoder/encode-limited-text! (fn [& _] (throw (ex-info "injected" {})))
                  encoder/close! (fn [owned] (is (= :owned owned)) (swap! closed inc))]
      (is (thrown? clojure.lang.ExceptionInfo (payload :native-guarded-byte-batch)))
      (is (= 1 @closed))
      (is (nil? @#'e/*sdk-scalar-map-payload-converter*)))))

(defn -main [& _]
  (let [result (run-tests 'otel.exporter.chdb-sdk-payload-scalar-cache-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
