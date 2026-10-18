(ns otel.exporter.chdb-sdk-scalar-map-test
  (:require [clojure.test :refer [deftest is] :as t]
            [otel.any-value :as any]
            [otel.exporter.chdb :as e]))

(defn fixture [n]
  (into {} (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "λ\n\"" 1 false i)]) (range n))))

(defn oracle [m]
  (into {} (map (fn [[k v]] [(@#'e/key-string k) (@#'e/value-string v)]) m)))

(defn convert [m]
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch}
    (@#'e/attrs m)))

(deftest stock-sdk-path-is-selected-before-the-native-fallback
  (let [m (with-meta (fixture 16) {:source true})]
    (with-redefs [e/small-attribute-transform
                  (delay (fn [_] (throw (ex-info "fallback-must-not-run" {}))))]
      (let [result (convert m)]
        (is (= (oracle m) result))
        (is (= (vec (keys m)) (vec (keys result))))
        (is (nil? (meta result)))
        (is (= {:source true} (meta m)))))))

(deftest unsupported-input-and-missing-sdk-helper-retain-conversion
  (doseq [m [(fixture 8) (fixture 129) (assoc (fixture 16) :alias 42)
             (assoc (fixture 16) "float" 1.25)
             (assoc (fixture 16) "nil" nil)
             (assoc (fixture 16) "nested" {"x" [1 false]})
             (assoc (fixture 16) "Aa" 1 "BB" 2)]]
    (is (= (oracle m) (convert m))))
  (with-redefs [e/sdk-scalar-map-converter (delay nil)]
    (is (= (oracle (fixture 16)) (convert (fixture 16))))))

(deftest changed-per-entry-and-sdk-policies-remain-live
  (let [m (fixture 16) seen (atom [])]
    (with-redefs [e/value-string (fn [v] (swap! seen conj v) "changed")]
      (is (= (zipmap (keys m) (repeat "changed")) (convert m)))
      (is (= (vec (vals m)) @seen)))
    (reset! seen [])
    (with-redefs [any/try-scalar-string (fn [v] (swap! seen conj v) "sdk-change")]
      (is (= (zipmap (keys m) (repeat "sdk-change")) (convert m)))
      (is (= (vec (vals m)) @seen)))
    (with-redefs [e/key-string (fn [k] (str "new-" k))]
      (is (= (oracle m) (convert m))))
    (with-redefs [any/default-limits (into {} any/default-limits)]
      (is (= (oracle m) (convert m))))))

(deftest late-exporter-policy-change-after-sdk-call-falls-back
  (let [m (fixture 16) original @#'e/value-string
        sdk-var (ns-resolve 'otel.any-value 'try-scalar-string-map)
        sdk-original @sdk-var
        seen (atom [])]
    (with-redefs-fn
      {#'e/value-string original
       sdk-var (fn [value]
                 (let [converted (sdk-original value)]
                   (alter-var-root #'e/value-string
                                   (constantly (fn [v] (swap! seen conj v) "late")))
                   converted))}
      #(do (is (= (zipmap (keys m) (repeat "late")) (convert m)))
           (is (= (vec (vals m)) @seen))))))

(defn -main [& _]
  (let [sdk-var (ns-resolve 'otel.any-value 'try-scalar-string-map)]
    (assert (and sdk-var (some? (@sdk-var (fixture 16))))
            "explicit gate requires the new SDK native capability"))
  (let [{:keys [test pass fail error]} (t/run-tests 'otel.exporter.chdb-sdk-scalar-map-test)]
    (assert (and (>= test 4) (>= pass 20)))
    (System/exit (if (zero? (+ fail error)) 0 1))))
