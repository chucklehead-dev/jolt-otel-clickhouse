(ns jolt.codec.scalar-text.value-reuse-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [jolt.codec.experimental :as codec]
            [jolt.codec.scalar-text.experimental :as shapes]
            [jolt.codec.scalar-text.experimental-test :as fixtures]
            [jolt.scheme :as scheme]))
(defn- selected-factory []
  (let [native-box (codec/native-scalar-text-view-box)
        mutation (System/getenv "JOLT_CODEC_TEST_BAD_VALUE_REUSE")]
    (if (contains? #{"1" "alias"} mutation)
      ;; Test-only stale-cache mutant. It passes shape/policy admission but
      ;; ignores changed scalar values. The SAME ordinary tests must reject it.
      (let [source (if (= mutation "alias")
                     (str/replace @#'shapes/value-reuse-source
                       "(materialize-root template (vector-ref entry 1))"
                       "(materialize-root template values)")
                     (str/replace @#'shapes/value-reuse-source
                       "(define (same-values? a b)"
                       "(define (same-values? a b) #t) (define (unused-comparison a b)"))
            [box stats] ((scheme/eval-string source) native-box
                         (codec/native-map-folder (codec/open-codec)))
            factory (shapes/native-view-plan-factory box)]
        (fn [bindings maximum prototype schemas chars]
          (let [p (factory bindings maximum prototype schemas chars)]
            (fn [op m] (if (= op 3) (stats) (p op m))))))
      (shapes/native-value-reuse-plan-factory native-box))))
(defn planner
  ([] (planner 100))
  ([maximum]
   ((selected-factory)
    [[#'fixtures/policy fixtures/original-policy]] maximum (sorted-map) 8 8192)))
(defn plain [maximum]
  ((shapes/native-plan-factory) [[#'fixtures/policy fixtures/original-policy]]
   maximum (sorted-map) 8 8192))
(def same-root? (scheme/eval-string "(lambda (a b) (eq? (pmap-root a) (pmap-root b)))"))
(def has-collision?
  (scheme/eval-string
    "(lambda (m) (let walk ((node (pmap-root m)))
       (and (hnode? node)
         (exists (lambda (child) (or (hcoll? child) (and (hnode? child) (walk child))))
                 (vector->list (hnode-arr node))))))"))

(deftest first-repeat-does-not-retain-a-second-string-copy
  (let [first-value "kept-λ😀" second-value (subs (str "prefix" first-value) 6)
        p (planner) m (sorted-map "a" first-value)]
    (is (= first-value second-value))
    (is (not (identical? first-value second-value)))
    (is (true? (p 1 m)))
    (let [result (p 0 (sorted-map "a" second-value))]
      ;; Internal ownership witness, not a public String-identity promise:
      ;; both retained slots/root must point at the SAME charged String.
      (is (identical? first-value (get result "a")))
      (is (not (identical? second-value (get result "a"))))
      (is (= [1 (+ 1 (count first-value)) 1 1] (p 3 nil))))))

(deftest repeats-share-only-immutable-normalized-root
  (let [c (codec/open-codec)]
    (doseq [n [0 1 8 9 17 32 128] reverse? [false true]]
      (let [m (into (if reverse? (sorted-map-by #(compare %2 %1)) (sorted-map)) (fixtures/fixture n))
            p (planner) q (plain 100)]
        (is (true? (p 1 m))) (is (true? (q 1 m)))
        (let [a (p 0 m) b (p 0 (into (empty m) m)) expected (codec/write-nested c (q 0 m))]
          (is (map? a)) (is (map? b)) (is (not (identical? a b)))
          (is (same-root? a b)) (is (nil? (meta a)))
          (is (= expected (codec/write-nested c a) (codec/write-nested c b)))
          (is (= 1 (first (p 3 nil)))) (is (= 1 (nth (p 3 nil) 3))))))))

(deftest changed-values-collisions-and-fresh-string-equality
  (let [m (into (fixtures/fixture 9)
                (sorted-map "Aa" -42 "BB" 0 "flag" false "text" "quote\"/\\\nλ😀"))
        p (planner) q (plain 100) c (codec/open-codec)]
    (is (= (hash "Aa") (hash "BB")))
    (is (true? (p 1 m))) (is (true? (q 1 m)))
    (let [stable (p 0 m) expected (codec/write-nested c stable)
          fresh (subs (str "prefix" (get m "text")) 6)]
      ;; Small flat maps do not exercise materialize-root's hcoll branch.
      (is (has-collision? stable))
      (is (= (codec/write-nested c (q 0 m)) expected))
      (is (not (identical? fresh (get m "text"))))
      (is (= expected (codec/write-nested c (p 0 (assoc m "text" fresh)))))
      (doseq [value [99 true "changed" -123]]
        (let [changed (assoc m "Aa" value) v (p 0 changed)]
          (is (some? v)) (is (not (map? v)))
          (is (= (codec/write-nested c (q 0 changed)) (codec/write-nested c v)))
          (is (not= expected (codec/write-nested c v)))))
      (is (= expected (codec/write-nested c stable)))
      (is (= 1 (first (p 3 nil)))) (is (= 1 (nth (p 3 nil) 3))))))

(deftest value-character-budget-is-exact-and-full-cache-retains-view-fallback
  (let [c (codec/open-codec)]
    (doseq [size [16383 16384]]
      (let [m (sorted-map "a" (apply str (repeat size "x")))
            p (planner 20000) q (plain 20000)]
        (is (true? (p 1 m))) (is (true? (q 1 m)))
        (let [result (p 0 m)]
          (is (= (= size 16383) (map? result)))
          (is (= (codec/write-nested c (q 0 m)) (codec/write-nested c result)))
          (is (= (if (= size 16383) [1 16384] [0 0]) (subvec (p 3 nil) 0 2)))))))
  (let [p (planner)]
    (doseq [i (range 8)] (is (true? (p 1 (sorted-map (str "key-" i) i)))))
    (is (= 8 (first (p 3 nil))))
    (is (false? (p 1 (sorted-map "ninth-schema" true))))
    (is (= 8 (first (p 3 nil))))))

(deftest policy-and-invalid-values-never-bypass-admission
  (let [p (planner) m (fixtures/fixture 17)]
    (is (true? (p 1 m))) (is (map? (p 0 m)))
    (let [before (p 3 nil)]
      (doseq [bad [nil (into {} m) (assoc m "key-0" nil)
                   (assoc m "key-0" [1]) (assoc m "key-0" 1.25)
                   (assoc m "key-0" 9223372036854775807)
                   (assoc m "key-0" (apply str (repeat 101 "x")))]]
        (is (false? (p 0 bad))) (is (false? (p 1 bad))))
      (with-redefs [fixtures/policy (Object.)]
        (is (false? (p 0 m))) (is (false? (p 1 m))))
      (is (= before (p 3 nil))))
    (is (map? (p 0 m)))))

(deftest cached-output-obeys-byte-and-depth-limits
  (let [p (planner) m (fixtures/fixture 17) c (codec/open-codec)]
    (is (true? (p 1 m)))
    (let [value (p 0 m) bytes (codec/encode-prefixed-records c "" [value] identity 10000)]
      (is (= (vec bytes) (vec (codec/encode-prefixed-records c "" [(p 0 m)] identity (alength bytes)))))
      (is (thrown? Throwable (codec/encode-prefixed-records c "" [(p 0 m)] identity (dec (alength bytes)))))
      (aset-byte bytes 0 (byte 0))
      (is (not= (vec bytes) (vec (codec/encode-prefixed-records c "" [(p 0 m)] identity 10000)))))
    (doseq [depth [127 128]]
      (let [nest (fn [x] (nth (iterate vector x) depth))
            attempt (fn [x] (try [:ok (vec (codec/encode-prefixed-records c "" [(nest x)] identity 100000))]
                                (catch Throwable _ [:rejected])))]
        (is (= (attempt (into {} (map (fn [[k v]] [k (str v)])) m)) (attempt (p 0 m))))))))

(defn -main [& _]
  (let [r (run-tests 'jolt.codec.scalar-text.value-reuse-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
