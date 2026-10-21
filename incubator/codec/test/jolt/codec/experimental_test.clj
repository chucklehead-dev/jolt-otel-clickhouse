(ns jolt.codec.experimental-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.codec.experimental :as codec]
            [jolt.scheme :as scheme]))

(defn rejects? [f] (try (f) false (catch Throwable _ true)))
(defn text [bytes] (String. bytes "UTF-8"))

(deftest startup-nested-text-smoke-rejects-raw-splice-mutant
  (let [c (codec/open-codec)
        scalar (fn [value _ _] (.getBytes (codec/write-nested c value) "UTF-8"))]
    (is (nil? (#'codec/qualify-nested-text! scalar
                 (fn [value _ _] (codec/prepare-nested-text c value)))))
    (is (= :jolt.codec.experimental/unavailable
           (try (#'codec/qualify-nested-text! scalar (fn [value _ _] value)) nil
                (catch Throwable error (:type (ex-data error))))))))

(deftest hash-collision-bucket-keeps-source-sequence-order
  (let [c (codec/open-codec)
        input (into {"Aa" 1 "BB" 2}
                    (map (fn [i] [(str "extra-" i) i]) (range 12)))
        expected (str "{" (apply str (interpose "," (map (fn [[k v]]
                                            (str (codec/write-nested c k) ":" v)) input))) "}")]
    (is (= (hash "Aa") (hash "BB")))
    (is (= expected (codec/write-nested c input)))))

(deftest prepared-nested-text-is-a-string-not-a-json-splice
  (let [c (codec/open-codec)]
    (doseq [value [nil false 0 -9223372036854775808 1.25 "quote\"slash/\\\nλ😀"
                  [] [false {"x" "é😀"}] {"x" [1 false nil]}]]
      (let [ordinary (codec/write-nested c value)
            prepared (codec/prepare-nested-text c value)
            expected (codec/encode-prefixed-records c "SQL\n" [ordinary] vector 10000)
            actual (codec/encode-prefixed-records c "SQL\n" [prepared] vector 10000)
            size (- (alength expected) 4)]
        (is (= (text expected) (text actual)))
        (is (= (text expected)
               (text (codec/encode-prefixed-records c "SQL\n" [prepared] vector size))))
        (is (rejects? #(codec/encode-prefixed-records c "SQL\n" [prepared] vector (dec size))))))
    ;; JSON string containing JSON null, not the JSON null value.
    (is (= "[\"null\"]\n"
           (text (codec/encode-prefixed-records c "" [(codec/prepare-nested-text c nil)] vector 100))))
    (is (rejects? #(codec/prepare-nested-text c (Object.))))))

(deftest prepared-nested-text-chunk-boundaries-and-reuse
  (let [c (codec/open-codec)
        source {"text" (str (apply str (repeat 70000 "x")) "\"/\\😀")}
        prepared (codec/prepare-nested-text c source)
        ordinary (codec/write-nested c source)
        emit #(codec/encode-prefixed-records c "" [%] vector 200000)
        a (emit prepared) expected (text (emit ordinary))]
    (is (= expected (text a)))
    (aset-byte a 1 (byte 0))
    (System/gc)
    (is (= expected (text (emit prepared))))
    (is (= (str "[" (codec/write-nested c ordinary) "]\n") expected))))

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
    ;; Force decimal scratch output to straddle a 64KiB batch chunk. Cover
    ;; signed/unsigned 64-bit values and arbitrary-precision fallback too.
    (doseq [n [0 -1 9223372036854775807 -9223372036854775808
               18446744073709551615 18446744073709551616
               -9223372036854775809]
            padding [65525 65530 65533 65535]]
      (let [prefix (apply str (repeat padding "p")) payload (str n "\n")
            actual (codec/encode-prefixed-records c prefix [n] identity (count payload))]
        (is (= (str prefix payload) (text actual)))
        (is (rejects? #(codec/encode-prefixed-records c prefix [n] identity (dec (count payload)))))))
    (doseq [n [0 1 1021 1022 1023 1024 1025 65530 65535 65536]]
      (let [s (str (apply str (repeat n "x")) "😀\n")
            prefix (apply str (repeat 1100 "p"))
            bytes (codec/encode-prefixed-records c prefix [s] identity 262144)
            payload (- (alength bytes) (count prefix))]
        (is (= (text bytes) (text (codec/encode-prefixed-records c prefix [s] identity payload))))
        (is (rejects? #(codec/encode-prefixed-records c prefix [s] identity (dec payload))))))))
