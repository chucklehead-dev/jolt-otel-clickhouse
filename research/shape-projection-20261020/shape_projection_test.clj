(ns shape-projection-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [shape-projection :as shape] [jolt.host :as host]
            [jolt.traversal.experimental :as traversal]
            [jolt.scheme :as scheme] [otel.any-value :as any]))

(def policy (Object.))
(def original-policy policy)
(def prototype (sorted-map "a" 1 "b" false "c" "text"))
(defn fixture [n]
  (into (sorted-map) (map (fn [i] [(str "field-" i) (case (mod i 3) 0 "λ😀" 1 false i)])) (range n)))
(defn plan [m]
  (shape/compile-plan (vec (keys m)) [[#'policy original-policy]] 100 prototype))
(defn oracle [m]
  (into {} (map (fn [[k v]] [k (any/try-scalar-string v)])) m))

(deftest exact-kind-order-metadata-and-fresh-values
  (doseq [n [0 1 8 9 16 32 128]]
    (let [m (with-meta (fixture n) {:private "not-output"}) p (plan m)
          result (p m) again (p m) expected (oracle m)]
      (is (= expected result)) (is (= (type expected) (type result)))
      (is (= (vec expected) (vec result))) (is (nil? (meta result)))
      (is (not (identical? result again)))
      (is (= {:private "not-output"} (meta m)))
      (when (pos? n)
        (let [changed (assoc m "field-0" "different-value")]
          (is (= (oracle changed) (p changed)))
          (is (not= result (p changed)))))))
  (doseq [m [(assoc (fixture 16) "Aa" 1 "BB" 2)
             (into (sorted-map-by #(compare %2 %1)) (fixture 16))]]
    (is (= (vec (oracle m)) (vec ((plan m) m)))))
  (let [[lo hi] (scheme/eval-string "(jolt-vector (most-negative-fixnum) (most-positive-fixnum))")
        m (sorted-map "lo" lo "hi" hi)]
    (is (= (oracle m) ((plan m) m)))))

(deftest unsupported-shapes-decline-and-recover
  (let [m (fixture 16) p (plan m)]
    (doseq [bad [nil {} (into {} m) (dissoc m "field-0")
                 (assoc m "extra" 1) (-> m (dissoc "field-0") (assoc "other" 1))
                 (assoc m "field-0" nil) (assoc m "field-0" 1.25)
                 (assoc m "field-0" 9223372036854775807)
                 (assoc m "field-0" [1])
                 (assoc m "field-0" (apply str (repeat 101 "x")))
                 (into (sorted-map-by #(compare %2 %1)) m)]]
      (is (nil? (p bad))))
    (is (= (oracle m) (p m)))
    (with-redefs [policy (Object.)] (is (nil? (p m))))
    (is (= (oracle m) (p m)))
    (let [p2 (with-redefs [policy (Object.)] (plan m))]
      (is (= (oracle m) (p2 m))))))

(deftest malformed-nodes-counts-and-ops-decline-without-callbacks
  (let [p (plan (fixture 1))]
    (doseq [[field value] [[:cnt -1] [:cnt 0] [:cnt "one"]
                           [:tree [nil "field-0" 1 nil]]
                           [:tree [nil "field-0" 1 nil nil :extra]]
                           [:tree [nil :key 1 nil nil]]
                           [:tree [nil "field-0" 1 :not-node nil]]]]
      (let [m (fixture 1)] (host/ref-put! m field value) (is (nil? (p m))))))
  (let [m (fixture 16) p (plan m) calls (atom 0)]
    (host/ref-put! m :ops (assoc (host/ref-get m :ops) :seq (fn [& _] (swap! calls inc))))
    (is (nil? (p m))) (is (zero? @calls)))
  (let [armed (atom false) calls (atom 0)
        m (into (sorted-map-by (fn [a b] (when @armed (swap! calls inc) (throw (ex-info "comparison" {})))
                                             (compare b a))) (fixture 16))
        p (plan m) expected (oracle m)]
    (reset! armed true) (is (= expected (p m))) (is (zero? @calls)))
  ;; A cyclic 5-field tree is rejected before an unbounded child traversal.
  (let [cycle (scheme/eval-string "(let ((v (jolt-vector #f \"field-0\" 1 jolt-nil jolt-nil))) (vector-set! (pvec-tail v) 3 v) v)")
        m (fixture 1) p (plan m)]
    (host/ref-put! m :tree cycle) (is (nil? (p m)))))

(deftest no-transient-insertion-after-plan-construction
  (let [[factory reads] (scheme/eval-string
                         (str "(let ((calls 0) (put tmap-put!))"
                              " (let ((tmap-put! (lambda (a b c) (set! calls (+ calls 1)) (put a b c))))"
                              " (jolt-vector " shape/source " (lambda () calls))))"))
        m (fixture 16) convert (factory [[#'policy original-policy]] 100 prototype (vec (keys m)))
        initial (reads)]
    (is (= 16 initial))
    (dotimes [_ 4] (is (= (oracle m) (convert m))))
    (is (= initial (reads)))))

(deftest invalid-plans-reject
  (doseq [ks [["same" "same"] [:not-string] (vec (repeat 129 "x"))]]
    (is (thrown? Throwable (shape/compile-plan ks [] 100 prototype)))))

(defn sdk-bindings []
  [[#'any/try-scalar-string @#'any/scalar-text-helper]
   [#'any/canonicalize @#'any/scalar-text-canonicalizer]
   [#'any/default-limits @#'any/scalar-default-limits]
   [#'any/string-bytes @#'any/original-string-bytes]
   [#'any/min-int64 @#'any/scalar-map-min-int64]
   [#'any/max-int64 @#'any/scalar-map-max-int64]])

(deftest real-sdk-root-cache-composition-and-policy-fences
  (let [m (fixture 16)
        p (shape/compile-plan (vec (keys m)) (sdk-bindings)
            (quot (:max-bytes @#'any/scalar-default-limits) 4) @#'any/scalar-sorted-map-prototype)
        cache (#'any/load-scalar-string-map-payload-cache
                (#'any/scalar-string-map-payload-bindings) 64 65536
                (traversal/native-pmap-folder (traversal/open-traversal)))
        calls (atom 0)
        convert (shape/cached-converter cache [(fn [x] (swap! calls inc) (p x))]
                  @#'any/original-try-scalar-string-map @#'any/original-try-sorted-scalar-string-map)]
    (is (some? cache))
    (let [one (convert m) two (convert m)]
      (is (= (oracle m) one two)) (is (not (identical? one two)))
      (is (= 1 @calls)))
    (let [independent (into (sorted-map) m)]
      (is (= (oracle m) (convert independent))) (is (= 2 @calls)))
    (is (= (oracle (assoc m "other" true)) (convert (assoc m "other" true))))
    (doseq [root [#'any/try-scalar-string #'any/canonicalize #'any/default-limits
                  #'any/string-bytes #'any/min-int64 #'any/max-int64
                  #'any/try-scalar-string-map #'any/try-sorted-scalar-string-map]]
      (with-redefs-fn {root (Object.)} #(is (nil? (convert m)))))
    (is (= (oracle m) (convert m)))))

(defn -main [& _]
  (let [result (run-tests 'shape-projection-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
