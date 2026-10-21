(load-file "research/template-key-prefixes-20261021/prototype.clj")
(require '[clojure.test :refer [deftest is run-tests]])

(def view-factory
  (scheme/eval-string
                  "(lambda (box) (lambda (template values)
                     (let* ((n (pvec-count values)) (out (make-vector n)))
                       (do ((i 0 (fx+ i 1))) ((fx=? i n))
                         (vector-set! out i (pvec-nth! values i)))
                       (box template out))))"))
(def box-view (view-factory (nth prefix-native 3)))
(defn encode-one [arm value limit depth ascii?]
  ((nth (nth prefix-native arm) (if ascii? 1 4)) value limit depth))
(defn attempt [f]
  (try [:ok (vec (f))] (catch Throwable _ [:rejected])))

(deftest exact-bytes-fresh-values-and-permuted-ordinals
  (doseq [n [0 1 8 9 17 32 128] ascii? [false true]]
    (let [template (into {} (map (fn [i] [(str "key-" i) (- n i 1)]) (range n)))
          values (mapv #(case (mod % 3) 0 "quote\"slash\\\nλ😀" 1 false (- %)) (range n))
          view (box-view template values)
          expected (encode-one 0 view 1000000 10 ascii?)]
      (doseq [arm [1 2]]
        (is (bytes-equal? expected (encode-one arm view 1000000 10 ascii?)))
        (when (pos? n)
          (is (not (bytes-equal? expected
                     (encode-one arm (box-view template (assoc values 0 "changed"))
                                 1000000 10 ascii?))))))))
  (doseq [key ["" "\"\\\n\t" "λ漢字😀" (apply str (repeat 64 "x"))
               (apply str (repeat 65 "x")) :fallback-key]]
    (let [view (box-view {key 0} ["fresh"])]
      (doseq [ascii? [false true] arm [1 2]]
        (is (bytes-equal? (encode-one 0 view 100000 5 ascii?)
                         (encode-one arm view 100000 5 ascii?)))))))

(deftest budgets-depth-chunks-and-ownership
  (let [template {"a" 1 "b" 0}
        values [false (apply str (repeat 70000 "x"))]
        view (box-view template values)
        small (box-view template [false "value"])]
    (doseq [input [view [small small] (box-view {} [])]
            ascii? [false true]]
      (let [expected (encode-one 0 input 1000000 10 ascii?) limit (alength expected)]
        (doseq [arm [1 2]]
          (is (bytes-equal? expected (encode-one arm input limit 10 ascii?)))
          (is (= [:rejected] (attempt #(encode-one arm input (dec limit) 10 ascii?))))
          (doseq [depth [0 1 2 3]]
            (is (= (attempt #(encode-one 0 input limit depth ascii?))
                   (attempt #(encode-one arm input limit depth ascii?))))))
        (let [output (encode-one 2 input limit 10 ascii?)]
          (aset-byte output 0 (byte 0))
          (is (bytes-equal? expected (encode-one 2 input limit 10 ascii?))))))
    (doseq [arm [0 1 2]]
      (let [lines (first (nth prefix-native arm))
            expected (lines "SQL\n" [small small] identity 100000 10)
            limit (- (alength expected) 4)
            calls (atom 0)]
        (is (bytes-equal? expected (lines "SQL\n" [small small] identity limit 10)))
        (is (= [:rejected] (attempt #(lines "SQL\n" [small small] identity (dec limit) 10))))
        (is (= [:rejected] (attempt #(lines "" [small :later]
                                       (fn [row] (swap! calls inc) row) 1 10))))
        (is (= 1 @calls)))))
  ;; A template with an out-of-range ordinal must preserve the original rejection.
  (doseq [template [{"bad" -1} {"bad" 1} {"bad" "not-an-index"}]]
    (let [v (box-view template ["value"])]
      (doseq [arm [0 1 2]]
        (is (= [:rejected] (attempt #(encode-one arm v 1000 10 true))))))))

(deftest cached-hits-enforce-current-depth-and-values
  (let [template {"field" 0} a (box-view template ["a"]) b (box-view template ["b"])]
    (doseq [arm [1 2]]
      (is (bytes-equal? (encode-one 0 [a b a] 1000 2 true)
                       (encode-one arm [a b a] 1000 2 true)))
      (is (= [:rejected] (attempt #(encode-one arm [a b] 1000 1 true)))))))

(load-file "research/template-key-prefixes-20261021/mechanism.clj")
(let [r (run-tests 'user)]
  (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))
