(ns jolt.codec.escape-burst-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str] [clojure.java.io :as io]
            [jolt.codec.experimental :as codec]
            [jolt.traversal.experimental :as traversal]
            [jolt.scheme :as scheme]))

(defmacro baseline-source [] (slurp (io/resource "jolt/codec/escape_burst_baseline.ss")))
(def old-source (baseline-source))
(defn text [bytes] (String. bytes "UTF-8"))
(defn kernel [source]
  (let [source (str/replace source "(define (ensure-space!)"
                 "(define (ensure-space!) (set! guard-count (+ guard-count 1))")
        factory (scheme/eval-string
                  (str "(lambda (ffc-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?)"
                       " (let ((guard-count 0)) (let () " source
                       " (jolt-vector"
                       " (lambda (v limit) (set! guard-count 0)"
                       "   (let ((out (ffc-json-ascii-bytes v limit 128))) (jolt-vector out guard-count)))"
                       " ffc-json-project-prefixed-ascii-lines-bytes))))"))]
    (apply factory (traversal/native-kernels (traversal/open-traversal)))))
(def original (delay (kernel old-source)))
(def candidate (delay (kernel @#'codec/json-source)))
(defn outcome [encode prefix values limit]
  (try [:ok (text (encode prefix values identity limit 128))]
       (catch Throwable _ [:rejected])))

(deftest guard-reduction-has-real-slow-control
  (doseq [[value old-guards new-guards]
          [["\"" 4 2] ["λ" 8 2] ["😀" 14 2] [1234567890 10 0]
           [-9223372036854775808 20 0] [18446744073709551615N 20 0]]]
    (let [[before guards-before] ((first @original) value 1000)
          [after guards-after] ((first @candidate) value 1000)]
      (is (= (text before) (text after)))
      (is (= old-guards guards-before))
      (is (= new-guards guards-after)))))

(deftest all-control-characters-and-numeric-ranges-preserve-bytes
  (doseq [value [(apply str (map char (range 32)))
                 "\"/\\éλ漢字😀" "" false nil
                 0 -1 9223372036854775807 18446744073709551615N
                 18446744073709551616N -9223372036854775809N]]
    (let [[old] ((first @original) value 10000)
          [new] ((first @candidate) value 10000)]
      (is (= (text old) (text new))))))

(deftest every-burst-offset-near-chunk-and-exact-budget-boundaries
  (let [before (second @original) after (second @candidate)]
    (doseq [value ["\"" "λ" "😀" -9223372036854775808]
            offset (range 22)]
      (let [prefix (apply str (repeat (- 65536 offset) "x"))
            result (outcome before prefix [value] 1000)
            size (- (alength (.getBytes (second result) "UTF-8")) (count prefix))]
        (is (= result (outcome after prefix [value] 1000)))
        (is (= result (outcome after prefix [value] size)))
        (is (= [:rejected] (outcome after prefix [value] (dec size))))))))

(deftest burst-failure-does-not-realize-later-input-and-outputs-are-private
  (let [c (codec/open-codec) seen (atom [])]
    (is (thrown? Throwable
          (codec/encode-prefixed-records c "" [1 2 3]
            (fn [n] (swap! seen conj n) "λ") 10)))
    (is (= [1 2] @seen))
    (let [one (codec/encode-prefixed-records c "" ["\"λ😀"] identity 100)
          expected (text one)]
      (aset-byte one 1 0)
      (is (= expected (text (codec/encode-prefixed-records c "" ["\"λ😀"] identity 100)))))))
