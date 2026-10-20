;; Requires the archived candidate.patch; not part of the maintained library.
(ns jolt.codec.string-fragment-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.codec.experimental :as codec]
            [jolt.traversal.experimental :as traversal]
            [jolt.scheme :as scheme]))

(defn text [bytes] (String. bytes "UTF-8"))
(defn emit [enabled rows limit]
  (codec/encode-prefixed-records
    (codec/open-codec {:flat-map-cache? true :string-fragment-cache? enabled})
    "SQL\n" rows identity limit))
(defn outcome [enabled rows limit]
  (try [:ok (text (emit enabled rows limit))] (catch Throwable _ [:rejected])))

(def mechanism
  (delay
    (let [factory
          (scheme/eval-string
            (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?)"
                 " (let ((stores 0) (charged 0) (real-set! hashtable-set!))"
                 " (let ((hashtable-set! (lambda (h k v)"
                 "   (when (string? k) (set! stores (+ stores 1))"
                 "     (set! charged (+ charged (bytevector-length v)))) (real-set! h k v))))"
                 " (let () " @#'codec/json-source
                 " (lambda (rows depth) (set! stores 0) (set! charged 0)"
                 "   (let ((out (ffc-json-encode/string-cached rows 2000000 depth #t #t #f \"\")))"
                 "     (jolt-vector out stores charged)))))))"))]
      (apply factory (traversal/native-kernels (traversal/open-traversal))))))

(deftest complete-literals-reused-with-unchanged-wire-and-current-budgets
  (doseq [s ["abcdefghijklmnop" "quote\"/\\\nλ😀-abcdefghijklmnop"
             (apply str (repeat 2048 "x")) (apply str (repeat 5000 "x"))]]
    (let [rows [[s s] [s]] expected (outcome false rows 100000)]
      (is (= expected (outcome true rows 100000)))
      (let [size (- (alength (emit false rows 100000)) 4)]
        (doseq [budget [0 1 (dec size) size (inc size)]]
          (is (= (outcome false rows budget) (outcome true rows budget)))))))
  (let [s "abcdefghijklmnop" [_ stores charged] (@mechanism [[s s s]] 128)]
    (is (= 1 stores)) (is (= 18 charged)))
  ;; Scalars at max depth zero are valid; map fragment's deeper-value guard
  ;; must not be applied to strings, including hits.
  (let [s "abcdefghijklmnop" [_ stores] (@mechanism [s s] 0)]
    (is (= 1 stores))))

(deftest bounded-insertions-and-large-literal-decline
  (let [strings (mapv #(str "abcdefghijklmnop-" %) (range 500))
        [_ stores charged] (@mechanism strings 128)]
    (is (= 64 stores)) (is (<= charged 131072)))
  (let [s (apply str (repeat 5000 "x")) [_ stores] (@mechanism [s s] 128)]
    (is (zero? stores)))
  (is (thrown? Throwable (codec/open-codec {:string-fragment-cache? true}))))

(deftest failure-does-not-request-later-records
  (doseq [enabled [false true]]
    (let [seen (atom []) c (codec/open-codec {:flat-map-cache? true :string-fragment-cache? enabled})]
      (is (thrown? Throwable
            (codec/encode-prefixed-records c "" [1 2 3]
              (fn [n] (swap! seen conj n) "abcdefghijklmnop") 20)))
      (is (= [1 2] @seen)))))
