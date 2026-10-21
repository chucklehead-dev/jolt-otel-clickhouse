(ns jolt.codec.scalar-text.view-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [jolt.codec.experimental :as codec]
            [jolt.codec.scalar-text.experimental :as shapes]
            [jolt.codec.scalar-text.experimental-test :as fixtures]
            [jolt.traversal.experimental :as traversal]
            [jolt.scheme :as scheme]))

(defn planners [keys]
  (let [args [[[#'fixtures/policy fixtures/original-policy]] 100 (sorted-map) 8 8192]
        plain (apply (shapes/native-plan-factory) args)
        view (apply (shapes/native-view-plan-factory (codec/native-scalar-text-view-box)) args)]
    (is (true? (plain 1 keys))) (is (true? (view 1 keys)))
    [plain view]))

(deftest scalar-text-view-preserves-bytes-without-pretending-to-be-a-map
  (doseq [n [0 1 8 9 17 32 128]
          reverse? [false true]]
    (let [m (into (if reverse? (sorted-map-by #(compare %2 %1)) (sorted-map)) (fixtures/fixture n))
          [plain view] (planners m) c (codec/open-codec)
          one (view 0 m) two (view 0 (into (empty m) m))]
      (is (not (map? one))) (is (not (identical? one two)))
      (is (= (codec/write-nested c (plain 0 m)) (codec/write-nested c one)
             (codec/write-nested c two)))
      (is (= (codec/write-nested c [(plain 0 m) (plain 0 m)])
             (codec/write-nested c [one two])))))
  (let [m (sorted-map "Aa" -42 "BB" 0 "escapes" "quote\"slash\\\nλ😀" "flag" true)
        [plain view] (planners m) c (codec/open-codec)
        v (view 0 m)]
    (is (= (codec/write-nested c (plain 0 m)) (codec/write-nested c v)))
    (is (= (codec/write-nested c v)
           (codec/write-nested c v)))
    ;; Independent views retain only their own values, not mutable scratch.
    (is (not= (codec/write-nested c v)
              (codec/write-nested c (view 0 (assoc m "Aa" 99)))))
    (is (= (codec/write-nested c (plain 0 m)) (codec/write-nested c v)))))

(deftest existing-policy-admission-and-decline-are-shared
  (let [m (fixtures/fixture 17) [plain view] (planners m)]
    (doseq [bad [nil {} (into {} m) (assoc m "key-0" nil)
                (assoc m "key-0" [1]) (assoc m "key-0" 1.25)
                (assoc m "key-0" 9223372036854775807)
                (assoc m "key-0" (apply str (repeat 101 "x")))]]
      (is (false? (plain 0 bad))) (is (false? (view 0 bad))))
    (with-redefs [fixtures/policy (Object.)]
      (is (false? (plain 0 m))) (is (false? (view 0 m))))
    (is (= [1 (second (plain 2 nil))] (view 2 nil)))))

(deftest budgets-failure-order-and-outputs-remain-independent
  (let [m (sorted-map "a" -42 "b" false) [plain view] (planners m)
        p (plain 0 m) v (view 0 m) c (codec/open-codec)
        reference (codec/encode-prefixed-records c "SQL\n" [p] identity 1000)
        limit (- (alength reference) 4)
        actual (codec/encode-prefixed-records c "SQL\n" [v] identity limit)]
    (is (= (vec reference) (vec actual)))
    (is (thrown? Throwable (codec/encode-prefixed-records c "SQL\n" [v] identity (dec limit))))
    (doseq [x [p v]]
      (let [calls (atom [])]
        (is (thrown? Throwable
              (codec/encode-prefixed-records c "" [x :later]
                (fn [row] (swap! calls conj row) row) 1)))
        (is (= 1 (count @calls)))))
    (aset-byte actual 0 (byte 0))
    (is (= (vec reference) (vec (codec/encode-prefixed-records c "SQL\n" [v] identity limit))))
    (doseq [depth [127 128]]
      (let [nest (fn [x] (nth (iterate vector x) depth))
            attempt (fn [x] (try [:ok (vec (codec/encode-prefixed-records c "" [(nest x)] identity 100000))]
                                (catch Throwable _ [:rejected])))]
        (is (= (attempt p) (attempt v)))))))

(deftest mechanism-control-counts-real-map-and-numeric-string-construction
  (let [[maker reset! counts]
        (scheme/eval-string
          (str "(let ((maps 0) (strings 0) (original-map make-pmap)"
               " (original-text jolt-fixnum->string))"
               " (let ((make-pmap (lambda args (set! maps (+ maps 1)) (apply original-map args)))"
               " (jolt-fixnum->string (lambda (v) (set! strings (+ strings 1)) (original-text v))))"
               " (jolt-vector (lambda (box) (" @#'shapes/source " box #f))"
               " (lambda () (set! maps 0) (set! strings 0))"
               " (lambda () (jolt-vector maps strings)))))"))
        m (fixtures/fixture 17)
        args [[[#'fixtures/policy fixtures/original-policy]] 100 (sorted-map) 8 8192]]
    (doseq [view? [false true]]
      (let [p (apply (maker (if view? (codec/native-scalar-text-view-box) false)) args)]
        (is (true? (p 1 m)))
        (reset!)
        (is (some? (p 0 m)))
        (is (= (if view? [0 0] [1 5]) (counts)))))))

(deftest compiled-keys-preserve-order-limits-policy-and-independent-values
  (doseq [n [0 1 8 9 17 32 128] reverse? [false true]]
    (let [m (into (if reverse? (sorted-map-by #(compare %2 %1)) (sorted-map))
              (fixtures/fixture n))
          args [[[#'fixtures/policy fixtures/original-policy]] 100 (sorted-map) 8 8192]
          plain (apply (shapes/native-plan-factory) args)
          compiled (apply (shapes/native-compiled-view-plan-factory
                            (codec/native-compiled-scalar-text-view-factory)) args)
          c (codec/open-codec)]
      (is (true? (plain 1 m))) (is (true? (compiled 1 m)))
      (let [p (plain 0 m) v (compiled 0 m)
            bytes #(codec/encode-prefixed-records c "SQL\n" [%] identity 100000)
            expected (bytes p) limit (- (alength expected) 4)]
        (is (some? v)) (is (not (map? v)))
        (is (= (codec/write-nested c p) (codec/write-nested c v)))
        (is (= (vec expected) (vec (codec/encode-prefixed-records c "SQL\n" [v] identity limit))))
        (is (thrown? Throwable (codec/encode-prefixed-records c "SQL\n" [v] identity (dec limit))))
        (with-redefs [fixtures/policy (Object.)] (is (false? (compiled 0 m))))
        (is (= (codec/write-nested c p) (codec/write-nested c v))))))
  (doseq [m [(sorted-map "Aa" -42 "BB" false "quote\"/\\\nλ😀" "original")
             ;; Exact schema character budget; ASCII expansion crosses 64KiB.
             (sorted-map (apply str (repeat 8192 "😀")) "original")]]
   (let [args [[[#'fixtures/policy fixtures/original-policy]] 100 (sorted-map) 8 8192]
        plain (apply (shapes/native-plan-factory) args)
        compiled (apply (shapes/native-compiled-view-plan-factory
                          (codec/native-compiled-scalar-text-view-factory)) args)
        c (codec/open-codec)]
    (is (true? (plain 1 m))) (is (true? (compiled 1 m)))
    (let [v (compiled 0 m) expected (codec/write-nested c (plain 0 m))]
      (is (= expected (codec/write-nested c v)))
      (is (not= expected (codec/write-nested c (compiled 0 (assoc m (first (keys m)) 777)))))
      (is (= expected (codec/write-nested c v)))
      (is (false? (compiled 0 (assoc m (first (keys m)) nil))))
      (let [limit (alength (codec/encode-prefixed-records c "" [v] identity 200000))
            output (codec/encode-prefixed-records c "" [v] identity limit)
            calls (atom 0)]
        (is (thrown? Throwable
              (codec/encode-prefixed-records c "" [v :later]
                (fn [row] (swap! calls inc) row) (dec limit))))
        (is (= 1 @calls))
        (aset-byte output 0 (byte 0))
        (is (= expected (codec/write-nested c v))))
      (doseq [depth [127 128]]
        (let [attempt (fn [value]
                        (try [:ok (codec/write-nested c (nth (iterate vector value) depth))]
                             (catch Throwable _ [:rejected])))]
          (is (= (attempt (plain 0 m)) (attempt v)))))))))

(deftest compiled-schema-removes-per-output-template-traversal-in-both-wire-modes
  (let [walk (traversal/open-traversal)
        factory (scheme/eval-string
                  (str "(lambda (original-map-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?)"
                       " (let ((walks 0)) (let ((ffc-map-fold (lambda (m visit initial)"
                       " (set! walks (+ walks 1)) (original-map-fold m visit initial))))"
                       @#'codec/json-source
                       " (jolt-vector ffc-json-scalar-map-box ffc-json-compile-scalar-schema"
                       " ffc-json-bytes ffc-json-ascii-bytes"
                       " (lambda () (set! walks 0)) (lambda () walks)))))"))
        [box compile-schema raw ascii reset! walks] (apply factory (traversal/native-kernels walk))
        ;; Explicit nontrivial ordinal order, Unicode/escaping and collisions.
        template {"Aa" 2 "BB" 0 "quote\"/\\\nλ😀" 1}
        slots (scheme/eval-string "(vector #f \"value-λ😀\" -42)")
        old (box template slots) new ((compile-schema template) slots)
        equal? (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))")]
    (doseq [encode [raw ascii]]
      (reset!)
      (let [expected (encode old 100000 128)]
        (is (= 1 (walks)))
        (reset!)
        (is (equal? expected (encode new 100000 128)))
        (is (zero? (walks)))))
    (doseq [bad [{"a" 0 "b" 0} {"a" -1} {"a" 1} {"a" "0"}
                {(apply str (repeat 8193 "x")) 0}]]
      (is (thrown? Throwable (compile-schema bad))))))

(defn -main [& _]
  (let [r (run-tests 'jolt.codec.scalar-text.experimental-test 'jolt.codec.scalar-text.view-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
