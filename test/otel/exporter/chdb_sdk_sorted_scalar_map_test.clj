(ns otel.exporter.chdb-sdk-sorted-scalar-map-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [otel.any-value :as any] [otel.exporter.chdb :as e]
            [otel.exporter.chdb-sdk-scalar-map-test :refer [fixture oracle convert]]
            [jolt.host :as host]))

(defn sorted-fixture [n] (into (sorted-map) (fixture n)))

(deftest source-owned-sorted-path-precedes-native-and-generic-fallbacks
  (let [m (with-meta (sorted-fixture 16) {:source true}) expected (oracle m)]
    (with-redefs [e/small-attribute-transform
                  (delay (fn [_] (throw (ex-info "must-not-fallback" {}))))]
      (let [actual (convert m)]
        (is (= expected actual))
        (is (= (type expected) (type actual)))
        (is (= (vec expected) (vec actual)))
        (is (nil? (meta actual)))
        (is (= {:source true} (meta m)))))))

(deftest unsupported-inputs-and-missing-helper-retain-normal-conversion
  (doseq [m [(sorted-fixture 129) (assoc (sorted-fixture 16) "nil" nil)
             (assoc (sorted-fixture 16) "float" 1.25)
             (assoc (sorted-fixture 16) "nested" [1 false])
             (sorted-map :alias 42) (assoc (sorted-fixture 9) "Aa" 1 "BB" 2)]]
    (is (= (vec (oracle m)) (vec (convert m)))))
  (with-redefs [e/sdk-sorted-scalar-map-converter (delay nil)]
    (is (= (oracle (sorted-fixture 16)) (convert (sorted-fixture 16))))))

(deftest custom-operations-are-not-tried-before-fallback
  (let [m (sorted-fixture 16) calls (atom 0)
        entries (vec m) ops (host/ref-get m :ops)]
    (host/ref-put! m :ops (assoc ops :seq (fn [_] (swap! calls inc) (seq entries))))
    (is (= (into {} (map (fn [[k v]] [k (any/try-scalar-string v)])) entries) (convert m)))
    (is (= 1 @calls))))

(deftest changed-exporter-and-sdk-policies-remain-live
  (let [m (sorted-fixture 16) seen (atom [])]
    (with-redefs [e/value-string (fn [v] (swap! seen conj v) "changed")]
      (is (= (zipmap (keys m) (repeat "changed")) (convert m)))
      (is (= (vec (vals m)) @seen)))
    (reset! seen [])
    (with-redefs [any/try-scalar-string (fn [v] (swap! seen conj v) "sdk-change")]
      (is (= (zipmap (keys m) (repeat "sdk-change")) (convert m)))
      (is (= (vec (vals m)) @seen)))
    (with-redefs [e/key-string (fn [k] (str "new-" k))]
      (is (= (oracle m) (convert m))))))

(deftest late-policy-change-does-not-call-a-second-sdk-helper-or-accept-result
  (let [m (sorted-fixture 16) sdk-var (ns-resolve 'otel.any-value 'try-sorted-scalar-string-map)
        actual @sdk-var original @#'e/value-string seen (atom [])]
    (with-redefs-fn
      {#'e/value-string original
       #'any/try-scalar-string-map (fn [_] (throw (ex-info "second-helper-must-not-run" {})))
       sdk-var (fn [value]
                 (let [result (actual value)]
                   (alter-var-root #'e/value-string
                                   (constantly (fn [v] (swap! seen conj v) "late")))
                   result))}
      #(do (is (= (zipmap (keys m) (repeat "late")) (convert m)))
           (is (= (vec (vals m)) @seen))))))

(defn -main [& _]
  (let [sdk-var (ns-resolve 'otel.any-value 'try-sorted-scalar-string-map)]
    (assert (and sdk-var (some? (@sdk-var (sorted-fixture 16))))
            "strict gate requires the new SDK sorted-map capability"))
  (let [r (run-tests 'otel.exporter.chdb-sdk-sorted-scalar-map-test)]
    (assert (= 5 (:test r)))
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
