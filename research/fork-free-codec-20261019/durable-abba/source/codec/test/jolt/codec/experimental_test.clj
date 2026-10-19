(ns jolt.codec.experimental-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.codec.experimental :as codec]
            [jolt.scheme :as scheme]))

(defn rejects? [f] (try (f) false (catch Throwable _ true)))
(defn text [bytes] (String. bytes "UTF-8"))

(deftest explicit-wire-values-and-prefix-budget
  (let [c (codec/open-codec)]
    (is (= "{\"a\":false,\"z\":0}" (codec/write-nested c (sorted-map "z" 0 "a" false))))
    (is (= "\"\\u00e9\\ud83d\\ude00\"" (codec/write-nested c "é😀")))
    (is (= "[]" (codec/write-nested c [])))
    (is (= "SQL\n[1]\n[2]\n"
           (text (codec/encode-prefixed-records c "SQL\n" [1 2] vector 8))))
    (is (rejects? #(codec/encode-prefixed-records c "SQL\n" [1 2] vector 7)))
    (is (= "SQL\n" (text (codec/encode-prefixed-records c "SQL\n" nil vector 0))))
    (is (rejects? #(codec/encode-prefixed-records c "é" [1] vector 100)))
    (is (rejects? #(codec/encode-prefixed-records c "" {} vector 100)))
    (is (rejects? #(codec/encode-prefixed-records c "" [] vector -1)))
    (is (rejects? #(codec/write-nested nil [])))))

(deftest failure-stops-before-next-record
  (let [c (codec/open-codec) seen (atom [])
        source (lazy-seq (cons 1 (lazy-seq (swap! seen conj :next) [2])))]
    (is (rejects? #(codec/encode-prefixed-records c "SQL\n" source
                    (fn [n] (swap! seen conj n) (apply str (repeat 100 "x"))) 5)))
    (is (= [1] @seen))
    (is (= "[1]\n[2]\n" (text (codec/encode-prefixed-records c "" (list 1 2) vector 8))))))

(deftest reentry-and-independent-output
  (let [c (codec/open-codec)
        a (codec/encode-prefixed-records c "" [1]
            (fn [_] [(codec/write-nested c {"x" [1 false]})]) 100)
        before (text a)
        b (codec/encode-prefixed-records c "" [2] vector 100)]
    (aset-byte b 1 (byte 57))
    (is (= before (text a)))
    (is (= "[9]\n" (text b)))
    (is (= "[\"{\\\"x\\\":[1,false]}\"]\n" before))
    ;; The source is evaluated lexically, not installed at the Scheme top level.
    (is (rejects? #(scheme/proc "ffc-json-encode")))))

(deftest scalar-and-prefix-chunk-boundaries
  (let [c (codec/open-codec)]
    (doseq [n [0 1 1021 1022 1023 1024 1025 65530 65535 65536]]
      (let [s (str (apply str (repeat n "x")) "😀\n")
            prefix (apply str (repeat 1100 "p"))
            bytes (codec/encode-prefixed-records c prefix [s] identity 262144)
            payload (- (alength bytes) (count prefix))]
        (is (= (text bytes) (text (codec/encode-prefixed-records c prefix [s] identity payload))))
        (is (rejects? #(codec/encode-prefixed-records c prefix [s] identity (dec payload))))))))
