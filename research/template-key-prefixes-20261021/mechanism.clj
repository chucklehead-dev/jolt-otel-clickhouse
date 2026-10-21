;; Loaded by check.clj. Instrumentation is absent from screen.clj's kernels.
(defn instrumented-source [source]
  (-> source
      (replace-once "(define (key! k)"
                    "(define (key! k) (ffc-prefix-observe 0 0 0 0)")
      (replace-once "(make-jolt-array out 'byte)"
                    "(begin (ffc-prefix-observe 1 template-attempts template-bytes (if template-cache (hashtable-size template-cache) 0)) (make-jolt-array out 'byte))")))

(deftest mechanism-cache-hits-and-monotonic-budgets
  (let [events (atom [])
        native (compile-prefix-native (instrumented-source candidate-source)
                                      (instrumented-source disabled-source)
                                      (fn [event a b c] (swap! events conj [event a b c])))
        make-view (view-factory (nth native 3))
        encode (fn [arm values] ((nth (nth native arm) 1) values 10000000 10))
        template {"field-one" 1 "field-two" 0}
        rows (mapv #(make-view template [(str %) false]) (range 20))]
    (reset! events []) (encode 1 rows)
    (is (= 40 (count (filter #(zero? (first %)) @events))))
    (is (= [1 0 0 0] (last @events)))
    (reset! events []) (encode 2 rows)
    (is (= 0 (count (filter #(zero? (first %)) @events))))
    (is (= 1 (second (last @events))))
    (is (pos? (nth (last @events) 2)))
    (is (= 1 (nth (last @events) 3)))
    ;; Declines consume attempts too. The ninth, eligible shape must not be learned.
    (let [rows (conj (mapv #(make-view {(str (apply str (repeat 65 "x")) %) 0} ["v"])
                           (range 8)) (make-view {"valid-ninth" 0} ["v"]))]
      (reset! events [])
      (is (bytes-equal? (encode 0 rows) (encode 2 rows)))
      (is (= [1 8 0 8] (last @events)))
      (is (= 9 (count (filter #(zero? (first %)) @events)))))
    ;; Escaped keys overflow the per-plan byte budget despite passing count/length.
    (let [template (into {} (map (fn [i] [(str (apply str (repeat 60 "漢")) i) i]) (range 128)))
          rows [(make-view template (vec (repeat 128 "v")))]]
      (reset! events [])
      (is (bytes-equal? (encode 0 rows) (encode 2 rows)))
      (is (= [1 1 0 1] (last @events)))
      (is (= 128 (count (filter #(zero? (first %)) @events)))))
    (let [rows (mapv (fn [shape]
                       (make-view
                         (into {} (map (fn [i] [(str shape (apply str (repeat 58 "漢")) i) i])
                                       (range 64)))
                         (vec (repeat 64 "v")))) (range 8))]
      (reset! events [])
      (is (bytes-equal? (encode 0 rows) (encode 2 rows)))
      (is (= 8 (second (last @events))))
      (is (<= 100000 (nth (last @events) 2) 131072))
      (is (= 8 (nth (last @events) 3)))
      (is (pos? (count (filter #(zero? (first %)) @events)))))))

(deftest mutations-are-rejected-by-real-oracles
  (doseq [[mutant oracle]
          [[(replace-once candidate-source "(vector-set! ordinals i ordinal)"
                                           "(vector-set! ordinals i 0)") :ordinals]
           [(replace-once candidate-source
                          "(when (fx>=? container-depth max-depth) (error 'ffc-json \"depth limit exceeded\"))"
                          "(when #f (error 'ffc-json \"depth limit exceeded\"))") :depth]]]
    (let [native (compile-prefix-native mutant disabled-source)
          make-view (view-factory (nth native 3))
          a (make-view {"one" 1 "two" 0} ["a" "b"])
          encode (fn [arm depth] ((nth (nth native arm) 1) [a a] 10000 depth))]
      (case oracle
        :ordinals (is (not (bytes-equal? (encode 0 3) (encode 2 3))))
        :depth (do (is (= [:rejected] (attempt #(encode 0 1))))
                   (is (= :ok (first (attempt #(encode 2 1))))))))))
