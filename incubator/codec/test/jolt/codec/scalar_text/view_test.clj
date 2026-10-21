(ns jolt.codec.scalar-text.view-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
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
        (is (p 0 m) "false must not masquerade as an observed view")
        (is (= (if view? [0 0] [1 5]) (counts)))))))

(deftest ordinary-hamt-views-preserve-wire-order-values-and-freshness
  (doseq [n [9 16 32 128] reverse? [false true]]
    (let [entries (seq (fixtures/fixture n))
          m (with-meta (into {} (if reverse? (reverse entries) entries)) {:private true})
          args [[[#'fixtures/policy fixtures/original-policy]] 100 (sorted-map) 8 8192]
          plain (apply (shapes/native-plan-factory) args)
          view (apply (shapes/native-view-plan-factory (codec/native-scalar-text-view-box)) args)
          c (codec/open-codec)]
      (is (false? (plain 1 m)) "map-returning sorted planner is unchanged")
      (is (true? (view 1 m)))
      (let [one (view 0 m) changed (assoc m "key-0" -42) two (view 0 changed)
            expected (codec/write-nested c (fixtures/oracle m))]
        (is one) (is two) (is (not (map? one)))
        (is (not (identical? one two)))
        (is (= expected (codec/write-nested c one)))
        (is (= (codec/write-nested c (fixtures/oracle changed)) (codec/write-nested c two)))
        (is (= expected (codec/write-nested c one)) "later values cannot change an old view")
        (is (= {:private true} (meta m)))))))

(deftest hamt-plans-preserve-admission-policy-and-retention-bounds
  (let [args [[[#'fixtures/policy fixtures/original-policy]] 100 (sorted-map) 1 8192]
        view (apply (shapes/native-view-plan-factory (codec/native-scalar-text-view-box)) args)
        m (into {} (fixtures/fixture 16))]
    (is (true? (view 1 m)))
    (let [budget (view 2 nil)]
      (doseq [bad [(assoc m "key-0" nil) (assoc m "key-0" [1])
                   (assoc m "key-0" 1.25) (assoc m "key-0" 9223372036854775807)
                   (assoc m "key-0" (apply str (repeat 101 "x")))
                   (assoc (dissoc m "key-0") "other-key" 1)]]
        (is (false? (view 0 bad))))
      (is (false? (view 1 (into {} (fixtures/fixture 17)))))
      (is (= budget (view 2 nil)) "failed/full learning retains no new schema")
      (with-redefs [fixtures/policy (Object.)]
        (is (false? (view 0 m))) (is (false? (view 1 m))))
      (is (view 0 m)))))

(deftest hash-collision-nodes-remain-on-the-existing-generic-fallback
  (is (= (hash "Aa") (hash "BB")))
  (let [view ((shapes/native-view-plan-factory (codec/native-scalar-text-view-box))
               [[#'fixtures/policy fixtures/original-policy]] 100 (sorted-map) 8 8192)
        ordinary (into {} (fixtures/fixture 16))
        colliding (assoc ordinary "Aa" 1 "BB" 2)]
    (is (true? (view 1 ordinary)))
    (is (false? (view 1 colliding)))
    (is (false? (view 0 colliding)))
    (is (= 1 (first (view 2 nil))))))

(deftest hamt-view-selection-can-retain-the-sorted-only-control
  (let [args [[[#'fixtures/policy fixtures/original-policy]] 100 (sorted-map) 8 8192]
        m (into {} (fixtures/fixture 16))
        control (apply (shapes/native-view-plan-factory (codec/native-scalar-text-view-box) false) args)]
    (is (false? (control 1 m))) (is (false? (control 0 m)))
    (is (= [0 0] (control 2 nil)))
    (is (true? (control 1 (fixtures/fixture 16))))
    (is (control 0 (fixtures/fixture 16)))))

(deftest equal-hash-renamed-key-cannot-reuse-a-stale-template
  (let [args [[[#'fixtures/policy fixtures/original-policy]] 100 (sorted-map) 8 8192]
        a (assoc (into {} (fixtures/fixture 16)) "Aa" 42)
        b (assoc (dissoc a "Aa") "BB" 42)
        c (codec/open-codec)
        expected (codec/write-nested c (fixtures/oracle b))
        source @#'shapes/source
        mutant (str/replace source "(string=? (car actual) (car expected))" "#t")]
    (is (= (hash "Aa") (hash "BB")))
    (is (not= source mutant) "the control must remove the actual key-match guard")
    (doseq [bad? [false true]]
      (let [maker (scheme/eval-string (if bad? mutant source))
            p (apply (maker (codec/native-scalar-text-view-box)) args)]
        (is (true? (p 1 a)))
        (if bad?
          (let [wrong (p 0 b)]
            (is wrong "same geometry alone falsely admits the renamed key")
            (is (not= expected (codec/write-nested c wrong))))
          (do
            (is (false? (p 0 b)))
            (is (true? (p 1 b)))
            (is (= expected (codec/write-nested c (p 0 b))))))))))

(defn -main [& _]
  (let [r (run-tests 'jolt.codec.scalar-text.experimental-test 'jolt.codec.scalar-text.view-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
