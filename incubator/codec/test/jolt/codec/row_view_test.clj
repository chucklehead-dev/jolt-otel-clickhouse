(ns jolt.codec.row-view-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.codec.experimental :as codec]))

(defn rejects? [f] (try (f) false (catch Throwable _ true)))
(defn text [bytes] (String. bytes "UTF-8"))

(deftest row-values-width-and-independent-suffix
  (let [c (codec/open-codec) build (codec/row-builder c 3)
        row (build nil false {"unicode" "λ😀"})
        combined (codec/append-row-fields c row [0 "x"])]
    (is (= 3 (codec/row-width c row)))
    (is (= 5 (codec/row-width c combined)))
    (is (= (codec/write-nested c [nil false {"unicode" "λ😀"} 0 "x"])
           (codec/write-nested c combined)))
    (is (= (codec/write-nested c [nil false {"unicode" "λ😀"}])
           (codec/write-nested c row)))
    (is (rejects? #(codec/append-row-fields c combined [])))
    (is (rejects? #(codec/append-row-fields c row '(1))))
    (is (rejects? #(codec/append-row-fields c [] [])))
    (is (rejects? #(codec/row-width c [])))
    (is (rejects? #(build 1)))
    (is (rejects? #(build 1 2 3 4)))
    (is (rejects? #(codec/write-nested c (build 1 2 (Object.)))))
    (is (= "[]" (codec/write-nested c ((codec/row-builder c 0)))))
    (is (= (codec/write-nested c (vec (range 32)))
           (codec/write-nested c (apply (codec/row-builder c 32) (range 32)))))
    (is (= (codec/write-nested c (vec (range 128)))
           (codec/write-nested c (codec/append-row-fields c
                                   ((codec/row-builder c 0)) (vec (range 128))))))))

(deftest bounds-size-ownership-and-record-failure
  (let [c (codec/open-codec) build (codec/row-builder c 1)
        row (build (apply str (repeat 70000 "x")))
        encode #(codec/encode-prefixed-records c "SQL\n" [%] identity 100000)
        bytes (encode row) expected (text bytes)]
    (aset-byte bytes 0 (byte 0))
    (is (= expected (text (encode row))))
    (doseq [width [-1 33 128 1.5 nil]]
      (is (rejects? #(codec/row-builder c width))))
    (is (rejects? #(codec/row-builder nil 1)))
    (let [seen (atom [])
          source (lazy-seq (cons 1 (lazy-seq (swap! seen conj :next) [2])))]
      (is (rejects? #(codec/encode-prefixed-records c "" source
                      (fn [n] (swap! seen conj n) (build "too-long")) 2)))
      (is (= [1] @seen)))
    (let [small (build false)]
      (is (= "[false]\n" (text (codec/encode-prefixed-records c "" [small] identity 8))))
      (is (rejects? #(codec/encode-prefixed-records c "" [small] identity 7))))))

(deftest nested-rows-cache-and-reentry
  (doseq [cache? [false true]]
    (let [c (codec/open-codec {:flat-map-cache? cache?}) build (codec/row-builder c 2)
          row (build [1 false] (build nil 3))]
      (is (= "[[1,false],[null,3]]" (codec/write-nested c row)))
      (is (= "[[1,false],[null,3]]\n"
             (text (codec/encode-prefixed-records c "" [row]
                     (fn [value]
                       (is (= "[0,1]" (codec/write-nested c (build 0 1))))
                       value) 100))))
      (let [one (codec/row-builder c 1)]
        (doseq [depth [0 1 127 128 129]
                :let [rows (nth (iterate one false) depth)
                      vectors (nth (iterate vector false) depth)]]
          (if (> depth 128)
            (do (is (rejects? #(codec/write-nested c rows)))
                (is (rejects? #(codec/write-nested c vectors))))
            (is (= (codec/write-nested c vectors) (codec/write-nested c rows)))))))))

(defn -main [& _]
  (let [r (clojure.test/run-tests 'jolt.codec.row-view-test)]
    (assert (= 3 (:test r)))
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
