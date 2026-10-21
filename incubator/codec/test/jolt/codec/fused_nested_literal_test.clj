(ns jolt.codec.fused-nested-literal-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.codec.experimental :as codec]
            [jolt.traversal.experimental :as traversal]
            [jolt.scheme :as scheme]))

(defn rejects? [f] (try (f) false (catch Throwable _ true)))
(defn text [bytes] (String. bytes "UTF-8"))
(defn fixture []
  (into (sorted-map)
    (map (fn [i] [(str "key-" i)
                   (case (mod i 5) 0 false 1 nil 2 i 3 1.25 "quote\"/\\λ😀")]) (range 12))))

(def instrumented
  (delay
    (let [factory (scheme/eval-string
                    (str "(lambda (real-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?)"
                         " (let ((walks 0) (stores 0) (charged 0) (real-set! hashtable-set!))"
                         " (let ((ffc-map-fold (lambda (m v a) (set! walks (+ walks 1)) (real-fold m v a)))"
                         "       (hashtable-set! (lambda (h k v) (set! stores (+ stores 1))"
                         "                         (set! charged (+ charged (bytevector-length (if (bytevector? v) v (car v))))) (real-set! h k v))))"
                         " (let () " @#'codec/json-source
                         " (define observed #f)"
                         " (define original-new ffc-new-fragments)"
                         " (set! ffc-new-fragments (lambda () (let ((cache (original-new)))"
                         "   (set! observed cache) cache)))"
                         " (jolt-vector ffc-json-nested-scope"
                         "  (lambda () (jolt-vector walks stores charged))"
                         "  (lambda (value)"
                         "    (ffc-json-encode/shared value 100000 128 #f #f #f \"\" (ffc-new-fragments)))"
                         "  (lambda (value) (ffc-json-ascii-bytes value 100000 128))"
                         "  (lambda () (jolt-vector (hashtable? (ffc-fragments-table observed))"
                         "    (ffc-fragments-insertions observed) (ffc-fragments-retained observed)"
                         "    (bytevector? (ffc-fragments-scratch observed))"
                         "    (bytevector? (ffc-fragments-digits observed))))"
                         " ffc-json-nested-literal-scope)))))"))]
      (apply factory (traversal/native-kernels (traversal/open-traversal))))))

(deftest fused-nested-literal-remains-a-json-string
  (let [c (codec/open-codec)]
    (let [scalar (fn [value _ _] (.getBytes (codec/write-nested c value) "UTF-8"))]
      (is (nil? (#'codec/qualify-nested-text! scalar
                   (fn [value _ _] (codec/prepare-nested-literal c value)))))
      (is (nil? (codec/with-nested-literal-writer c
                   (fn [write] (#'codec/qualify-nested-text! scalar
                                (fn [value _ _] (write value))))))))
    (doseq [value [nil false true 0 -9223372036854775808 18446744073709551615
                  123456789012345678901234567890N 1.25 :name
                  "quote\"slash/\\\n\tλ😀" [] {} [1 false nil "é😀"]
                  (sorted-map "escaped\"/\\😀" [1 false nil])
                  (into {} (map (fn [n] [(str "key-" n) n]) (range 32)))]]
      (let [old (codec/prepare-nested-text c value)
            fused (codec/prepare-nested-literal c value)
            expected (codec/encode-prefixed-records c "SQL\n" [old] vector 100000)
            size (- (alength expected) 4)]
        (is (= (text expected)
               (text (codec/encode-prefixed-records c "SQL\n" [fused] vector size))))
        (is (rejects? #(codec/encode-prefixed-records c "SQL\n" [fused] vector (dec size))))
        (is (= (codec/write-nested c old) (codec/write-nested c fused)))))
    (is (= "\"null\"" (codec/write-nested c (codec/prepare-nested-literal c nil))))
    (is (rejects? #(codec/prepare-nested-literal c (Object.))))
    (is (rejects? #(codec/prepare-nested-literal nil [])))
    ;; A nested literal inside another must be escaped again, not spliced.
    (let [old (codec/prepare-nested-text c {"x" "\"/\\😀"})
          fused (codec/prepare-nested-literal c {"x" "\"/\\😀"})]
      (is (= (codec/write-nested c (codec/prepare-nested-text c old))
             (codec/write-nested c (codec/prepare-nested-literal c fused)))))))

(deftest fused-inner-budgets-depth-chunks-and-output-ownership
  (let [c (codec/open-codec) prepare (nth @@#'codec/backend 10)]
    (doseq [n [0 1 1021 1022 1023 1024 1025 65530 65535 65536 70000]
            suffix ["" "\"/\\😀\n"]]
      (let [value {"text" (str (apply str (repeat n "x")) suffix)}
            ordinary (codec/write-nested c value) limit (count ordinary)
            old (codec/prepare-nested-text c value) fused (prepare value limit 128)]
        (is (= (codec/write-nested c old) (codec/write-nested c fused)))
        (is (rejects? #(prepare value (dec limit) 128)))
        (let [a (codec/encode-prefixed-records c "" [fused] vector 300000)
              before (text a) calls (atom 0)]
          (is (rejects? #(codec/encode-prefixed-records c "" [fused :later]
                           (fn [x] (swap! calls inc) [x]) (dec (alength a)))))
          (is (= 1 @calls))
          (aset-byte a 0 (byte 0)) (System/gc)
          (is (= before (text (codec/encode-prefixed-records c "" [fused] vector 300000)))))))
    (doseq [depth [127 128]]
      (let [value (nth (iterate vector {"a" 1}) depth)
            outcome (fn [f] (try [:ok (codec/write-nested c (f value))]
                                (catch Throwable _ [:reject])))]
        (is (= (outcome #(codec/prepare-nested-text c %))
               (outcome #(codec/prepare-nested-literal c %))))))))

(deftest fused-scope-reuses-escaped-fragments-with-logical-bounds-and-clear
  (let [[_ stats _ render state open] @instrumented [write close] (open)
        c (codec/open-codec) m (fixture) before (stats)
        ordinary (codec/write-nested c m) limit (count ordinary)
        one (write m limit 128) two (write m limit 128)]
    (is (= (codec/write-nested c ordinary) (text (render one)) (text (render two))))
    (is (= [1 1] (mapv - (subvec (stats) 0 2) (subvec before 0 2))))
    (is (rejects? #(write m (dec limit) 128)))
    (is (rejects? #(write [m] 100000 1)))
    (is (not= (text (render one)) (text (render (write (assoc m "key-0" true) 100000 128)))))
    (close)
    (is (= [false 0 0 false false] (state)))
    (is (rejects? #(write m 100000 128)))
    (is (= (codec/write-nested c ordinary) (text (render one))))
    (let [held (atom nil) marker (ex-info "stop" {})]
      (is (identical? marker
            (try (codec/with-nested-literal-writer c
                   (fn [w] (reset! held w) (w m) (throw marker)))
                 nil (catch Throwable error error))))
      (is (rejects? #(@held m))))))
