(ns jdbc.chdb-owned-wal-clean-words-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as scheme]
            [jdbc.chdb.owned-statement :as owned]
            [jdbc.chdb.durable.wal :as wal]))

(defn- emitted [prepared]
  (let [out (java.io.ByteArrayOutputStream.)]
    (wal/write-prepared! out prepared) (.toByteArray out)))

(defn- kernel-prepared [kernel bytes]
  (let [[total chunks] (kernel bytes)] {:byte-count total :chunks chunks}))

(deftest every-ascii-byte-at-each-word-lane-and-alignment
  ;; Stock portable JSONL is independent of the native word predicate. This
  ;; includes DEL, every control, quote/slash/backslash, and short tails.
  (doseq [padding (range 4) lane (range 4) cp (range 128)]
    (let [sql (str (apply str (repeat padding "a"))
                   (subs "bcde" 0 lane) (char cp) (subs "bcde" (inc lane)) "tail")
          prepared (owned/prepared-wal (owned/try-snapshot (.getBytes sql "UTF-8")))]
      (is (java.util.Arrays/equals (wal/portable-line-bytes sql) (emitted prepared)))
      (is (= (:byte-count prepared) (alength (emitted prepared)))))))

(deftest copy-and-scalar-paths-agree-across-chunk-boundaries
  (let [source (slurp (io/resource "jdbc/chdb/owned_ascii_wal.ss"))
        ;; Disable only the optional word branch, leaving the scalar codec.
        scalar (scheme/eval-string
                 (str/replace source "(clean-word? (bytevector-u32-native-ref input i))" "#f"))]
    (doseq [n [0 1 3 4 5 244 245 255 256 65516 65523 65524 65525 65536 131072]
            suffix ["" "\"/\\\n\u0000tail" "\u007ftail"]]
      (let [sql (str (apply str (repeat n "a")) suffix)
            bytes (.getBytes sql "UTF-8")
            actual (owned/prepared-wal (owned/try-snapshot bytes))
            expected (kernel-prepared scalar bytes)]
        (is (= (:byte-count expected) (:byte-count actual)))
        (is (java.util.Arrays/equals (emitted expected) (emitted actual)))
        (is (every? (fn [[chunk used]] (<= 1 used (alength chunk) 65536)) (:chunks actual)))))))

(deftest copy-path-is-live-and-each-escape-rejection-is-necessary
  (let [source (slurp (io/resource "jdbc/chdb/owned_ascii_wal.ss"))
        observed (str/replace source "(bytevector-copy! input i buffer at 4)"
                   "(begin (set! copies (+ copies 1)) (bytevector-copy! input i buffer at 4))")
        stage (scheme/eval-string
                (str "(let ((copies 0)) (let ((encoder " observed ")) "
                     "(jolt-vector encoder (lambda () copies))))"))
        bytes (.getBytes "ordinary ASCII words" "UTF-8")]
    (is (java.util.Arrays/equals (wal/portable-line-bytes "ordinary ASCII words")
                                (emitted (kernel-prepared (nth stage 0) bytes))))
    (is (pos? ((nth stage 1))))
    ;; These mutants use checked word reads/copies and safe original stores.
    ;; They must differ from exact V1 bytes, even where JSON stays valid.
    (doseq [[clause sql]
            [["(not (word-has? word #x22222222))" "a\"bc"]
             ["(not (word-has? word #x2f2f2f2f))" "a/bc"]
             ["(not (word-has? word #x5c5c5c5c))" "a\\bc"]
             ["(not (word-has? (fxand word #x60606060) 0))" "a\u0000bc"]]]
      (when (str/includes? source clause)
        (let [mutant (scheme/eval-string (str/replace source clause "#t"))]
          (is (not (java.util.Arrays/equals
                     (wal/portable-line-bytes sql)
                     (emitted (kernel-prepared mutant (.getBytes sql "UTF-8")))))))))))

(defn -main [& _]
  (let [result (run-tests 'jdbc.chdb-owned-wal-clean-words-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
