;; Archived candidate tests; apply candidate.patch before loading this file.
(ns jolt.codec.key-fragment-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.codec.experimental :as codec]
            [jolt.traversal.experimental :as traversal]
            [jolt.scheme :as scheme]))

(defn text [bytes] (String. bytes "UTF-8"))
(defn emit [enabled flat? rows limit]
  (codec/encode-prefixed-records
    (codec/open-codec {:flat-map-cache? flat? :key-fragment-cache? enabled})
    "SQL\n" rows identity limit))
(defn outcome [enabled flat? rows limit]
  (try [:ok (text (emit enabled flat? rows limit))] (catch Throwable _ [:rejected])))

(def mechanism
  (delay
    (let [factory (scheme/eval-string
                    (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?)"
                         " (let ((stores 0) (charged 0) (real-set! hashtable-set!))"
                         " (let ((hashtable-set! (lambda (h k v)"
                         "   (when (string? k) (set! stores (+ stores 1))"
                         "     (set! charged (+ charged (bytevector-length v)))) (real-set! h k v))))"
                         " (let () " @#'codec/json-source
                         " (lambda (rows depth) (set! stores 0) (set! charged 0)"
                         "   (let ((out (ffc-json-encode/key-only rows 2000000 depth #t #t #f \"\")))"
                         "     (jolt-vector out stores charged)))))))"))]
      (apply factory (traversal/native-kernels (traversal/open-traversal))))))

(deftest key-reuse-preserves-wire-budgets-and-value-encoding
  (doseq [key ["" "a" "service.name" "quote\"/\\\nλ😀" :keyword 'symbol]
          flat? [false true]]
    (let [rows [{key "PRIVATE-VALUE-LONG"} {key false} {key 42}]
          expected (outcome false flat? rows 100000)
          size (- (alength (emit false flat? rows 100000)) 4)]
      (is (= expected (outcome true flat? rows 100000)))
      (doseq [budget [0 1 (dec size) size (inc size)]]
        (is (= (outcome false flat? rows budget) (outcome true flat? rows budget))))))
  (let [key "a" [_ stores charged] (@mechanism [{key "PRIVATE-VALUE-LONG"} {key "changed"}] 128)]
    (is (= 1 stores)) (is (= 3 charged) "only the quoted key is retained")))

(deftest insertion-and-byte-budgets-remain-monotonic
  (let [rows (mapv #(hash-map (str "key-" %) %) (range 500))
        [_ stores charged] (@mechanism rows 128)]
    (is (= 64 stores)) (is (<= charged 8192)))
  (let [rows (mapv #(hash-map (str (apply str (repeat 300 "x")) %) %) (range 100))
        [_ stores charged] (@mechanism rows 128)]
    (is (pos? stores)) (is (< stores 64)) (is (<= charged 8192)))
  (doseq [key [(apply str (repeat 4095 "x")) (apply str (repeat 700 "λ"))]]
    (let [[_ stores] (@mechanism [{key 1} {key 2}] 128)] (is (zero? stores))))
  (let [key (apply str (repeat 4094 "x")) [_ stores charged] (@mechanism [{key 1} {key 2}] 128)]
    (is (= 1 stores)) (is (= 4096 charged))))

(deftest no-global-output-alias-or-depth-bypass
  (let [m {"a" 1} wrap (fn [n] (nth (iterate vector m) n))]
    (doseq [flat? [false true]]
      (is (= (outcome false flat? [m (wrap 127)] 100000)
             (outcome true flat? [m (wrap 127)] 100000)))
      (is (= [:rejected] (outcome true flat? [m (wrap 128)] 100000)))))
  (let [rows [{"a" "value"} {"a" "other"}] one (emit true false rows 1000)
        expected (text one)]
    (aset-byte one 0 81)
    (is (= expected (text (emit true false rows 1000))))))

(deftest cached-key-does-not-request-later-record-after-failure
  (doseq [enabled [false true]]
    (let [seen (atom []) key "a" c (codec/open-codec {:key-fragment-cache? enabled})]
      (is (thrown? Throwable
            (codec/encode-prefixed-records c "" [1 2 3]
              (fn [n] (swap! seen conj n) {key "long-value"}) 25)))
      (is (= [1 2] @seen)))))
