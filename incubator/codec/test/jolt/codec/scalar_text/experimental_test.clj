(ns jolt.codec.scalar-text.experimental-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [jolt.codec.scalar-text.experimental :as shapes]
            [jolt.host :as host] [jolt.scheme :as scheme]))

(def policy (Object.))
(def original-policy policy)
(def prototype (sorted-map "a" 7 "b" "text" "c" false))
(def native-nodes?
  ((scheme/eval-string "(lambda (m) (eq? (record-type-uid (record-rtd m)) 'chez-scoll-v1))") prototype))
(def altered-native-copy
  (when native-nodes?
    (scheme/eval-string
      "(lambda (m field value)
         (make-scoll* #f #f (scoll-kind m) (scoll-tag m)
           (if (eq? field (keyword #f \"tree\")) value (scoll-tree m))
           (if (eq? field (keyword #f \"cnt\")) value (scoll-cnt m))
           (scoll-cmp m) (scoll-cmp-fn m)
           (if (eq? field (keyword #f \"ops\")) value (scoll-ops m))))")))
(defn altered-copy [m field value]
  (if native-nodes?
    (altered-native-copy m field value)
    (let [copy (with-meta m {:test-copy true})]
      (host/ref-put! copy field value) copy)))
(defn planner
  ([] (planner 8 8192))
  ([count chars] ((shapes/native-plan-factory) [[#'policy original-policy]] 100 prototype count chars)))
(defn fixture [n]
  (into (sorted-map) (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "λ😀" 1 false i)])) (range n)))
(defn oracle [m] (into {} (map (fn [[k v]] [k (str v)])) m))

(deftest inferred-shapes-preserve-values-kind-order-and-freshness
  (doseq [n [0 1 8 9 16 32 128]]
    (let [p (planner) m (with-meta (fixture n) {:not-output "metadata"})]
      (is (false? (p 0 m))) (is (true? (p 1 m)))
      (let [one (p 0 m) two (p 0 (into (sorted-map) m))]
        (is (= (oracle m) one two)) (is (= (type (oracle m)) (type one)))
        (is (= (vec (oracle m)) (vec one))) (is (nil? (meta one)))
        (is (not (identical? one two))) (is (= {:not-output "metadata"} (meta m))))))
  (doseq [m [(assoc (fixture 16) "Aa" 1 "BB" 2)
             (into (sorted-map-by #(compare %2 %1)) (fixture 16))]]
    (let [p (planner)]
      (is (true? (p 1 m))) (is (= (vec (oracle m)) (vec (p 0 m)))))))

(deftest bounded-learning-stops-without-affecting-existing-lookups
  (let [p (planner 1 3) a (sorted-map "abc" true) b (sorted-map "def" false)]
    (is (true? (p 1 a))) (is (= [1 3] (p 2 nil)))
    (is (false? (p 1 b))) (is (false? (p 0 b)))
    (is (= {"abc" "false"} (p 0 (assoc a "abc" false))))
    (is (= [1 3] (p 2 nil))))
  (let [p (planner 8 3)]
    (is (false? (p 1 (sorted-map "abcd" 1))))
    (is (= [0 0] (p 2 nil)))
    (is (true? (p 1 (sorted-map "ab" 1))))
    (is (false? (p 1 (sorted-map "cd" 2))))
    (is (= [1 2] (p 2 nil))))
  (let [p (planner 0 0)]
    (is (false? (p 1 (sorted-map)))) (is (= [0 0] (p 2 nil)))))

(deftest unsupported-inputs-and-live-policy-changes-decline
  (let [p (planner) m (fixture 16)]
    (is (true? (p 1 m)))
    (doseq [bad [nil {} (into {} m) (assoc m "key-0" nil)
                 (assoc m "key-0" 1.25) (assoc m "key-0" [1])
                 (assoc m "key-0" 9223372036854775807)
                 (assoc m "key-0" (apply str (repeat 101 "x")))
                 (fixture 129) (sorted-map :not-string 1)]]
      (is (false? (p 0 bad))) (is (false? (p 1 bad))))
    (with-redefs [policy (Object.)]
      (is (false? (p 0 m))) (is (false? (p 1 m))))
    (is (= (oracle m) (p 0 m)))
    (is (= 1 (first (p 2 nil))))
    (let [p2 (with-redefs [policy (Object.)] (planner))]
      (is (true? (p2 1 m))) (is (= (oracle m) (p2 0 m))))))

(deftest malformed-and-custom-inputs-never-run-callbacks
  (when native-nodes?
    (doseq [source ["(vector #f \"a\" 1 jolt-nil)"
                    "(vector 'red \"a\" 1 jolt-nil jolt-nil)"
                    "(vector #f 7 1 jolt-nil jolt-nil)"
                    "(vector #f \"a\" (jolt-vector 1) jolt-nil jolt-nil)"
                    "(vector #f \"a\" 1 #t jolt-nil)"]]
      (let [p (planner) bad (altered-copy (sorted-map "a" 1) :tree (scheme/eval-string source))]
        (is (false? (p 1 bad))) (is (= [0 0] (p 2 nil))))))
  (doseq [[field value] [[:cnt -1] [:cnt "one"] [:cnt 0]
                         [:tree [nil "a" 1 nil]] [:tree [nil "a" 1 :bad nil]]
                         [:tree [nil "a" 1 [nil "a" 2 nil nil] nil]]]]
    (let [p (planner) m (altered-copy (sorted-map "a" 1) field value)]
      (is (false? (p 1 m))) (is (= [0 0] (p 2 nil)))))
  (let [p (planner) m (fixture 16) calls (atom 0)]
    (let [bad (altered-copy m :ops (assoc (host/ref-get m :ops) :seq (fn [& _] (swap! calls inc))))]
      (is (false? (p 1 bad))) (is (zero? @calls))))
  (let [p (planner) m (fixture 1)
        cycle (scheme/eval-string
                (if native-nodes?
                  "(let ((v (vector #f \"key-0\" 1 jolt-nil jolt-nil))) (vector-set! v 3 v) v)"
                  "(let ((v (jolt-vector #f \"key-0\" 1 jolt-nil jolt-nil))) (vector-set! (pvec-tail v) 3 v) v)"))]
    (is (false? (p 1 (altered-copy m :tree cycle)))) (is (= [0 0] (p 2 nil)))))

(deftest invalid-options-reject
  (doseq [[count chars] [[-1 1] [9 1] [1 -1] [1 8193] [1.0 1]]]
    (is (thrown? Throwable (planner count chars)))))

(deftest checked-node-access-avoids-repeated-public-index-validation
  ;; Count actual generic indexing in an isolated kernel, not wall time. The
  ;; old implementation is the non-vacuity control: four reads per tree node.
  ;; Every candidate node retains its representation/size guards before access.
  ;; The instrumented alternative counts one indexed read per accessor on
  ;; either runtime; it is not a timing comparison between different runtimes.
  (doseq [n [0 1 17 128] old? [false true]]
    (let [source (if old?
                   (reduce (fn [s [name index]]
                             (str/replace s (str "(" name " node)")
                               (str "(counted-node-ref node " index ")")))
                     @#'shapes/source
                     [["sc-nd-key" 1] ["sc-nd-val" 2] ["sc-nd-left" 3] ["sc-nd-right" 4]])
                   @#'shapes/source)
          [factory reset-count! reads]
          (scheme/eval-string
            (str "(let ((reads 0) (original-nth pvec-nth!))"
                 " (let ((counted-node-ref (lambda (v i) (set! reads (+ reads 1))"
                 " (if (vector? v) (vector-ref v i) (original-nth v i)))))"
                 " (let ((factory (" source " #f)))"
                 " (jolt-vector factory (lambda () (set! reads 0)) (lambda () reads)))))"))
          p (factory [[#'policy original-policy]] 100 prototype 8 8192)
          m (fixture n)]
      (is (true? (p 1 m)))
      (reset-count!)
      (is (= (oracle m) (p 0 m)))
      (is (= (if old? (* 4 n) 0) (reads))))))

(deftest learned-lookups-keep-node-guards-and-vector-view-semantics
  (let [p (planner) original (sorted-map "a" 1)]
    (is (true? (p 1 original)))
    (doseq [tree [nil [] [nil "a" 1 nil] [nil "a" 1 :bad nil]
                 [nil "wrong-key" 1 nil nil] [nil "a" [1] nil nil]]]
      (let [m (altered-copy (sorted-map "a" 1) :tree tree)]
        (is (false? (p 0 m)))))
    (let [tree (if native-nodes?
                 (scheme/eval-string "(vector #f \"a\" 1 jolt-nil jolt-nil)")
                 (subvec [0 nil "a" 1 nil nil 0] 1 6))
          m (altered-copy (sorted-map "a" 1) :tree tree)]
      (is (= {"a" "1"} (p 0 m))))
    (is (= [1 1] (p 2 nil)))))

(deftest public-mutation-contract-is-runtime-specific
  (let [m (sorted-map "a" 1)]
    (if native-nodes?
      (do (is (thrown? Throwable (host/ref-put! m :cnt 0)))
          (is (= {"a" 1} m)))
      (do (host/ref-put! m :cnt 0)
          (is (= 0 (host/ref-get m :cnt)))))))

(defn -main [& _]
  (let [r (run-tests 'jolt.codec.scalar-text.experimental-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
