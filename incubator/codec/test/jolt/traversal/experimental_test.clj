(ns jolt.traversal.experimental-test
  (:require [clojure.test :as t :refer [deftest is]]
            [jolt.traversal.experimental :as walk]
            [jolt.scheme :as scheme]))

(defn rejects? [f] (try (f) false (catch Throwable _ true)))

(deftest map-order-values-and-metadata
  (let [w (walk/open-traversal)]
    (doseq [m [{} {nil false :x nil} (with-meta {"x" 1} {:source :test})
               (into {"Aa" nil "BB" false} (map (fn [i] [(str "k-" i) i]) (range 100)))
               (sorted-map "c" nil "a" false "b" 0)]]
      (is (= (mapv vec m) (walk/fold-map w m (fn [k v a] (conj a [k v])) []))))
    (is (= (hash "Aa") (hash "BB")))
    (let [visits (atom 0) reduced-value (reduced :sentinel)]
      (is (identical? reduced-value
                     (walk/fold-map w {:a 1 :b 2} (fn [_ _ a] (swap! visits inc) a) reduced-value)))
      (is (= 2 @visits)))))

(deftest vector-boundaries-and-invalid-inputs
  (let [w (walk/open-traversal) calls (atom 0)]
    (doseq [n [0 1 31 32 33 1024 1057 32768 32801]]
      (let [v (vec (range n))]
        (is (= v (walk/fold-vector w v (fn [x a] (conj a x)) [])))))
    ;; Subvec and concatenation may create relaxed trees with short leaves.
    ;; Include nil/false, metadata, assoc, conj and pop; none changes visit order.
    (doseq [v [(with-meta [nil false 0] {:source :test})
               (subvec (vec (range 1100)) 17 1077)
               (into (subvec (vec (range 1100)) 31 1058) [nil false])
               (pop (conj (assoc (vec (range 1057)) 32 false) nil))
               (vec (concat (subvec (vec (range 1100)) 17 1040)
                            (subvec (vec (range 1200)) 23 1111)))]]
      (is (= v (walk/fold-vector w v (fn [x a] (conj a x)) []))))
    (doseq [value [nil [] '(1) #{1}]]
      (is (rejects? #(walk/fold-map w value (fn [& _] (swap! calls inc)) nil))))
    (doseq [value [nil {} '(1) #{1}]]
      (is (rejects? #(walk/fold-vector w value (fn [& _] (swap! calls inc)) nil))))
    (is (zero? @calls))
    (is (rejects? #(walk/fold-map nil {} (fn [& _]) nil)))
    (is (rejects? #(walk/fold-records w {} (fn [& _]) nil)))))

(deftest comparator-is-not-called-and-changed-core-ops-reject
  (let [w (walk/open-traversal) comparisons (atom 0)
        m (into (sorted-map-by (fn [a b] (swap! comparisons inc) (compare a b)))
                [["b" 2] ["a" 1]])]
    (reset! comparisons 0)
    (is (= [["a" 1] ["b" 2]] (walk/fold-map w m (fn [k v a] (conj a [k v])) [])))
    (is (zero? @comparisons))
    (with-redefs-fn {(ns-resolve 'clojure.core 'sm-ops) {}}
      (fn [] (is (rejects? #(walk/fold-map w m (fn [& _]) nil)))))))

(deftest exceptions-stop-before-later-callbacks-or-records
  (let [w (walk/open-traversal) seen (atom []) error (ex-info "stop" {})
        source (lazy-seq (cons 1 (lazy-seq (swap! seen conj :next) [2])))]
    (is (identical? error (try (walk/fold-records w source
                                (fn [x _] (swap! seen conj x) (throw error)) nil)
                               (catch Throwable x x))))
    (is (= [1] @seen))
    (reset! seen [])
    (is (identical? error
                    (try (walk/fold-vector w (vec (range 1057))
                           (fn [x a]
                             (swap! seen conj x)
                             (if (= x 32) (throw error) a)) nil)
                         (catch Throwable x x))))
    (is (= (vec (range 33)) @seen))
    (reset! seen [])
    (is (identical? error (try (walk/fold-map w (sorted-map "a" 1 "b" 2)
                                (fn [k _ _] (swap! seen conj k) (throw error)) nil)
                               (catch Throwable x x))))
    (is (= ["a"] @seen))
    (is (= [] (walk/fold-records w nil (fn [x a] (conj a x)) [])))
    (is (= [false nil 0] (walk/fold-records w (list false nil 0) (fn [x a] (conj a x)) [])))))

(deftest nested-folds-and-native-composition-are-isolated
  (let [w (walk/open-traversal)
        [m v r sorted?] (walk/native-kernels w)
        native ((scheme/eval-string
                  "(lambda (map-fold vector-fold records-fold)
                     (lambda (m) (map-fold m (lambda (k v a) (+ v a)) 0)))") m v r)]
    (is (= 3 (native {"a" 1 "b" 2})))
    (is (sorted? (sorted-map "a" 1)))
    (is (not (sorted? {})))
    (is (= [3 7] (walk/fold-vector w [{"a" 1 "b" 2} {"c" 7}]
                                   (fn [m a] (conj a (native m))) [])))
    (is (= 3 ((walk/native-pmap-folder w) {:a 1 :b 2} (fn [_ v a] (+ a v)) 0)))
    (is (rejects? #((walk/native-pmap-folder w) (sorted-map "a" 1) (fn [& _]) 0)))
    (is (false? ((scheme/eval-string "(lambda () (top-level-bound? 'ffc-map-fold))"))))))

(defn -main [& _]
  ;; Separate process gate: traversal must not require/initialize JSON machinery.
  (assert (nil? (find-ns 'jolt.codec.experimental)))
  (let [result (t/run-tests 'jolt.traversal.experimental-test)]
    (assert (= 5 (:test result)))
    (assert (nil? (find-ns 'jolt.codec.experimental)))
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
