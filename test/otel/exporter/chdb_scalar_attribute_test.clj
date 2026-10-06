(ns otel.exporter.chdb-scalar-attribute-test
  (:require [clojure.test :as test :refer [deftest is]]
            [clojure.data.json :as json] [otel.any-value :as any]
            [otel.exporter.chdb :as exporter]))

;; Frozen normalization boundary from c451e7a, independent of the shortcut.
(defn legacy-value-string [value]
  (if (nil? value) ""
      (let [canonical (any/canonicalize value)]
        (if (:error canonical) (pr-str value)
            (#'exporter/canonical-value-string (:value canonical))))))

(deftest scalar-and-fallback-values-retain-exact-wire
  (doseq [value [nil "" "é😀\n\u0000" true false 0 -1
                 any/min-int64 any/max-int64 9007199254740993
                 (dec any/min-int64) (inc any/max-int64)
                 1.5 :named/key 'named/key [] {} [false nil]
                 any/empty-value (any/bytes [0 255])
                 {"nested" [true "é"]} {:x 1 "x" 2}]]
    (let [expected (legacy-value-string value)
          actual (#'exporter/value-string value)]
      (is (= expected actual))
      (is (= (json/write-str {"attribute" expected})
             (json/write-str {"attribute" actual}))))))

(deftest changed-limits-retain-truncation-and-errors
  (doseq [options [(into {} any/default-limits)
                   (assoc any/default-limits :value-length-limit 1)
                   (assoc any/default-limits :max-bytes 0)
                   (assoc any/default-limits :max-nodes 0)]]
    (with-redefs [any/default-limits options]
      (doseq [value ["long" true 42]]
        (is (= (legacy-value-string value) (#'exporter/value-string value)))))))

(deftest live-canonicalizer-is-not-skipped-or-repeated
  (let [calls (atom [])]
    (with-redefs [any/canonicalize (fn [value]
                                   (swap! calls conj value) {:value "custom"})]
      (doseq [value ["plain" false 42]]
        (is (= "custom" (#'exporter/value-string value))))
      (is (= ["plain" false 42] @calls)))))

(defn legacy-attrs [source]
  (into {} (map (fn [[k v]] [(#'exporter/key-string k) (#'exporter/value-string v)]))
        (or source {})))

(deftest direct-attribute-reduction-retains-map-order-collisions-and-wire
  (doseq [source [nil false {} []
                  (array-map :x 1 "x" 2 :named/x 3 "é😀" "\n")
                  (into {} (map (fn [i] [(str "key-" i) i]) (range 32)))
                  (sorted-map "z" true "a" false)
                  [["key" nil] [:key "last"] [nil any/empty-value]
                   [42 (any/bytes [0 255])] ["structured" {:nested [1 nil true]}]]]]
    (let [expected (legacy-attrs source) actual (#'exporter/attrs source)]
      (is (= expected actual))
      (is (= (vec (keys expected)) (vec (keys actual))))
      (is (= (json/write-str expected) (json/write-str actual))))))

(deftest attribute-converters-retain-effect-order-and-single-realization
  (let [old-key @#'exporter/key-string old-value @#'exporter/value-string
        observe (fn [build fail?]
                  (let [effects (atom [])
                        source (lazy-seq
                                 (swap! effects conj :realized)
                                 (list [:x "first"] ["x" "second"] [:last "third"]))]
                    (with-redefs [exporter/key-string
                                  (fn [k] (swap! effects conj [:key k]) (old-key k))
                                  exporter/value-string
                                  (fn [v]
                                    (swap! effects conj [:value v])
                                    (when (and fail? (= "second" v))
                                      (throw (ex-info "converter rejected" {:type ::rejected})))
                                    (old-value v))]
                      [(try {:value (build source)}
                            (catch clojure.lang.ExceptionInfo e {:error (ex-data e)}))
                       @effects])))]
    (doseq [fail? [false true]]
      (is (= (observe legacy-attrs fail?) (observe #'exporter/attrs fail?))))))

(defn -main [& _]
  (let [result (test/run-tests 'otel.exporter.chdb-scalar-attribute-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
