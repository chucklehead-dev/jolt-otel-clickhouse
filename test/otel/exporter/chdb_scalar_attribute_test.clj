(ns otel.exporter.chdb-scalar-attribute-test
  (:require [clojure.test :as test :refer [deftest is]]
            [clojure.data.json :as json]
            [otel.any-value :as any]
            [otel.exporter.chdb :as exporter]))

;; Frozen normalization boundary from main2230d4f, independent of the shortcut.
(defn- legacy-value-string [value]
  (if (nil? value) ""
      (let [canonical (any/canonicalize value)]
        (if (:error canonical) (pr-str value)
            (#'exporter/canonical-value-string (:value canonical))))))

(deftest scalar-and-fallback-values-retain-exact-wire
  (doseq [value [nil "" "é😀\n\u0000" true false 0 -1
                 any/min-int64 any/max-int64 9007199254740993
                 (dec any/min-int64) (inc any/max-int64)
                 1.5 ##Inf ##-Inf ##NaN :named/key 'named/key [] {} [false nil]
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

(defn- frozen-attrs [m]
  (into {} (map (fn [[k v]] [(#'exporter/key-string k)
                            (#'exporter/value-string v)])) (or m {})))

(defrecord CustomAttributes [])

(deftest unchanged-string-maps-share-entries-without-changing-wire
  (doseq [source [(array-map "a" "" "b" "é😀\n\u0000")
                  (into {} (map (fn [i] [(str "field-" i) (str "value-" i)])) (range 16))]]
    (is (identical? source (#'exporter/attrs source)))
    (is (= (json/write-str (frozen-attrs source))
           (json/write-str (#'exporter/attrs source))))
    (let [tagged (with-meta source {:private true}) result (#'exporter/attrs tagged)]
      (is (= source result))
      (is (nil? (meta result)))
      (is (= {:private true} (meta tagged)))))
  (doseq [source [nil {} {"n" 1} {"nil" nil} {"nested" [false "x"]}
                  (array-map :a "first" "a" "last")
                  (sorted-map "b" "second" "a" "first")
                  (assoc (->CustomAttributes) "a" "value")]]
    (is (= (frozen-attrs source) (#'exporter/attrs source)))
    (is (= (json/write-str (frozen-attrs source))
           (json/write-str (#'exporter/attrs source)))))
  (doseq [source [(sorted-map "a" "value")
                  (assoc (->CustomAttributes) "a" "value")]]
    (is (false? (#'exporter/reusable-string-attrs? source)))))

(deftest string-map-reuse-retains-live-policies-and-converters
  (doseq [options [(assoc any/default-limits :max-bytes 0)
                   (assoc any/default-limits :value-length-limit 1)]]
    (with-redefs [any/default-limits options]
      (let [source {"a" "long"} result (#'exporter/attrs source)]
        (is (= (frozen-attrs source) result))
        (is (not (identical? source result))))))
  (let [source (array-map "a" "first" "b" "stop" "c" "later")
        effects (atom []) error (ex-info "stop" {})]
    (with-redefs [exporter/key-string (fn [key] (swap! effects conj [:key key]) key)
                  exporter/value-string (fn [value]
                                          (swap! effects conj [:value value])
                                          (when (= "stop" value) (throw error))
                                          value)]
      (is (identical? error (try (#'exporter/attrs source) (catch Throwable e e))))
      (is (= [[:key "a"] [:value "first"] [:key "b"] [:value "stop"]] @effects))))
  (let [calls (atom [])]
    (with-redefs [any/canonicalize (fn [value] (swap! calls conj value) {:value "custom"})]
      (is (= {"a" "custom"} (#'exporter/attrs {"a" "plain"})))
      (is (= ["plain"] @calls))))
  (let [calls (atom [])]
    (with-redefs [any/try-scalar-string (fn [value] (swap! calls conj value) "replacement")]
      (is (= {"a" "replacement"} (#'exporter/attrs {"a" "plain"})))
      (is (= ["plain"] @calls))))
  (let [oversized (apply str (repeat (inc (quot (:max-bytes any/default-limits) 4)) "x"))
        source {"a" oversized} result (#'exporter/attrs source)]
    (is (= (frozen-attrs source) result))
    (is (not (identical? source result)))))

(deftest canonicalizer-can-change-a-later-key-converter
  (let [original-key @#'exporter/key-string]
    (with-redefs [exporter/key-string original-key
                  any/canonicalize
                  (fn [value]
                    (alter-var-root #'exporter/key-string
                                    (constantly (fn [key] (str "changed-" key))))
                    {:value value})]
      (is (= {"a" "first" "changed-b" "second"}
             (#'exporter/attrs (array-map "a" "first" "b" "second")))))))

(deftest mixed-map-shapes-decline-before-sdk-value-admission
  (let [original @#'any/try-scalar-string seen (atom [])
        observed (fn [value] (swap! seen conj value) (original value))]
    ;; Explicitly trust this observer so the positive case reaches admission;
    ;; ordinary helper replacements must fail the SDK-owned qualification.
    (with-redefs [any/try-scalar-string observed
                  any/default-scalar-string? (constantly true)]
      (is (true? (#'exporter/reusable-string-attrs? (array-map "a" "first" "b" "second"))))
      (is (= ["first" "second"] @seen))
      (doseq [source [(array-map "a" 42 "b" "second")
                      (array-map "a" "first" "b" 42)]]
        (reset! seen [])
        (is (false? (#'exporter/reusable-string-attrs? source)))
        (is (empty? @seen))))))

(defn -main [& _]
  (let [result (test/run-tests 'otel.exporter.chdb-scalar-attribute-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
