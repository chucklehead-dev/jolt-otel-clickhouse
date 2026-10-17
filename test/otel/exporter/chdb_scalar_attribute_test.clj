(ns otel.exporter.chdb-scalar-attribute-test
  (:require [clojure.test :as test :refer [deftest is]]
            [clojure.data.json :as json]
            [jolt.scheme :as scheme]
            [otel.any-value :as any]
            [otel.exporter.chdb.attribute-map-shape :as attribute-map-shape]
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

(deftest rebuilding-order-boundaries-retain-exact-wire
  (is (= (hash "Aa") (hash "BB")) "collision control must remain non-vacuous")
  (is (instance? clojure.lang.PersistentHashMap (hash-map "a" "value")))
  (let [padding (into {} (map (fn [index] [(str "padding-" index) "value"])) (range 50))]
    (doseq [source [(assoc padding "Aa" "first" "BB" "second")
                    (assoc padding "BB" "second" "Aa" "first")
                    (apply array-map (mapcat (fn [index] [(str "field-" index) "value"]) (range 16)))
                    (hash-map "a" "value")]]
      (let [result (#'exporter/attrs source)]
        (is (= (frozen-attrs source) result))
        (is (not (identical? source result)))
        (is (= (.getName (class (frozen-attrs source))) (.getName (class result))))
        (is (= (vec (keys (frozen-attrs source))) (vec (keys result))))
        (is (= (json/write-str (frozen-attrs source)) (json/write-str result)))))))

(deftest builtin-map-mode-cohorts-retain-normalized-kind-order-and-wire
  (doseq [size [0 1 7 8 9 16 32 50]
          constructor [array-map hash-map]
          collisions? [false true]
          tagged? [false true]]
    (let [entries (concat (map (fn [index] [(str "cohort-" index) "value"]) (range size))
                          (when collisions? [["Aa" "first"] ["BB" "second"]]))
          original (apply constructor (mapcat identity entries))
          source (if tagged? (with-meta original {:source :cohort}) original)
          expected (frozen-attrs source) actual (#'exporter/attrs source)]
      (is (= expected actual))
      (is (= (.getName (class expected)) (.getName (class actual))))
      (is (= (vec (keys expected)) (vec (keys actual))))
      (is (= (json/write-str expected) (json/write-str actual)))
      (is (nil? (meta actual)))
      (is (= (when tagged? {:source :cohort}) (meta source))))))

(deftest unavailable-native-shape-check-retains-generic-normalization
  (with-redefs [attribute-map-shape/native-shape-check (delay (constantly false))]
    (let [source {"a" "value"} result (#'exporter/attrs source)]
      (is (= source result))
      (is (not (identical? source result))))))

(deftest native-shape-qualification-exercises-late-bound-branches
  (doseq [mode [:compile-failure :late-branch-failure]]
    (let [attempts (atom 0) collision-probed? (atom false)
          load (fn [_]
                 (swap! attempts inc)
                 (when (= mode :compile-failure)
                   (throw (ex-info "unsupported native compiler" {})))
                 (fn [m]
                   (when (contains? m "Aa")
                     (reset! collision-probed? true)
                     (throw (ex-info "unsupported collision primitive" {})))
                   (and (not (and (instance? clojure.lang.PersistentHashMap m)
                                  (<= (count m) 8)))
                        (every? string? (vals m))
                        (reduce max 0 (map count (vals m))))))]
      (with-redefs [scheme/eval-string load
                    attribute-map-shape/native-shape-check
                    (delay (#'attribute-map-shape/make-native-shape-check))]
        (let [source {"a" "value"} result (#'exporter/attrs source)]
          (is (= source result))
          (is (not (identical? source result)))
          (is (= 1 @attempts))
          (is (= (= mode :late-branch-failure) @collision-probed?)))))))

(deftest bounded-shape-admission-never-probes-the-scalar-formatter
  (let [original @#'any/try-scalar-string seen (atom []) queries (atom 0)
        maximum (any/default-string-value-max-chars)
        observed (fn [value] (swap! seen conj value) (original value))]
    ;; Explicitly trust this test issuer to exercise the scan. The real SDK
    ;; issuer declines changed formatters, covered by the live-policy controls.
    (with-redefs [any/try-scalar-string observed
                  any/default-string-value-max-chars
                  (fn [] (swap! queries inc) maximum)]
      (is (true? (#'exporter/reusable-string-attrs? (array-map "a" "first" "b" "second"))))
      (is (= 1 @queries))
      (is (empty? @seen))
      (doseq [source [(array-map "a" 42 "b" "second")
                      (array-map "a" "first" "b" 42)]]
        (reset! seen [])
        (is (false? (#'exporter/reusable-string-attrs? source)))
        (is (empty? @seen))))))

(deftest one-pass-shape-reports-the-largest-value-not-key-length
  (doseq [size [0 1 8 9 16 128]]
    (let [source (into {} (map (fn [i] [(str "deliberately-long-key-" i)
                                      (apply str (repeat (mod i 7) "漢😀"))]))
                       (range size))
          expected (reduce max 0 (map count (vals source)))]
      (is (= expected (attribute-map-shape/reusable-string-map-required-chars source)))
      (is (true? (attribute-map-shape/reusable-string-map-shape? source)))))
  (is (= 7 (attribute-map-shape/reusable-string-map-required-chars
             (array-map "a" "largest" "b" ""))))
  (doseq [source [(sorted-map "a" "value") (hash-map "a" "value")
                  (array-map :a "value") (array-map "a" 1)
                  (apply array-map (mapcat #(vector (str %) "value") (range 16)))
                  (assoc (into {} (map #(vector (str %) "value")) (range 16))
                         "Aa" "first" "BB" "second")]]
    (is (nil? (attribute-map-shape/reusable-string-map-required-chars source)))))

(deftest changed-byte-counter-declines-before-its-first-effect
  (let [source (array-map "a" "first" "b" "second")
        effects (atom []) original-key @#'exporter/key-string]
    (with-redefs [exporter/key-string original-key
                  any/string-bytes
                  (fn [value]
                    (swap! effects conj value)
                    (alter-var-root #'exporter/key-string
                                    (constantly (fn [key] (str "changed-" key))))
                    (* 4 (count value)))]
      (is (false? (#'exporter/reusable-string-attrs? source)))
      (is (empty? @effects))
      (is (= {"a" "first" "changed-b" "second"} (#'exporter/attrs source)))
      (is (= ["first" "second"] @effects)))))

(defn -main [& _]
  (let [result (test/run-tests 'otel.exporter.chdb-scalar-attribute-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
