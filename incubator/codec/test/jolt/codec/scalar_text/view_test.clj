(ns jolt.codec.scalar-text.view-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [jolt.codec.experimental :as codec]
            [jolt.codec.scalar-text.experimental :as shapes]
            [jolt.codec.scalar-text.experimental-test :as fixtures]
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
               " (jolt-vector " @#'shapes/source
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

(defn -main [& _]
  (let [r (run-tests 'jolt.codec.scalar-text.experimental-test 'jolt.codec.scalar-text.view-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
