(ns otel.exporter.chdb-native-declared-attributes-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [jolt.scheme :as scheme]
            [otel.exporter.chdb.attribute-projection :as p]
            [otel.exporter.chdb.native-attributes :as native]
            [otel.exporter.chdb-typed-compact-vector-test :as vectors]
            [otel.exporter.chdb-attribute-projection-test :as fixture]
            [otel.exporter.chdb-compact-span-layout-test :as spans]))

(defn observe [f span]
  (let [calls (atom []) original @#'p/key-string]
    (with-redefs [p/key-string (fn [key] (swap! calls conj key) (original key))]
      [(f span) @calls])))

(defn portable-collect [attrs wanted]
  (reduce (fn [out [key value]]
            (let [normalized (@#'p/key-string key)]
              (if (contains? wanted normalized)
                (assoc out normalized (conj (get out normalized []) value)) out)))
          {} attrs))

(deftest positional-output-is-sealed-once-and-remains-independent
  (let [[emit seals]
        (scheme/eval-string
          (str "(let ((count 0) (old make-pvec)) "
               "(let ((make-pvec (case-lambda "
               "((v) (set! count (+ count 1)) (old v)) "
               "((v kind) (set! count (+ count 1)) (old v kind))))) "
               "(jolt-vector "
               (slurp (io/resource "otel/exporter/chdb/native_projected_values.ss"))
               " (lambda () count))))"))
        original @#'p/projected-value]
    (doseq [n [0 1 3 8 16 17 32 33 65]]
      (let [plan (mapv (fn [i] [0 (str i) :int64]) (range n))
            collected [(into {} (map (fn [i] [(str i) [i]]) (range n)))]
            before (seals)
            a (emit plan collected #'p/projected-value)
            b (with-redefs [p/projected-value (fn [_ values] [(- (first values)) 99])]
                (emit plan collected #'p/projected-value))]
        (is (= 2 (- (seals) before)))
        (is (= (vec (mapcat (fn [[_ k type]] (original type (get (first collected) k []))) plan)) a))
        (is (= (vec (mapcat (fn [i] [(- i) 99]) (range n))) b))
        (is (= (vec (mapcat (fn [[_ k type]] (original type (get (first collected) k []))) plan)) a))
        (when (pos? n)
          (is (= 0 (first a)))
          (is (= :changed (first (assoc a 0 :changed)))))))))

(deftest positional-output-preserves-live-pair-destructuring-and-reentry
  (let [emit (native/load-positional-output! #'p/projected-value)
        plan [[0 "a" :int64] [0 "b" :int64] [0 "c" :int64]]
        collected [{"a" [1] "b" [2] "c" [3]}]
        original @#'p/projected-value
        effects (atom []) nested (atom nil)
        replacement (fn [_ values] (swap! effects conj (first values))
                      (case (first values) 2 (list 20) 3 nil))]
    (with-redefs [p/projected-value
                  (fn [type values]
                    (swap! effects conj (first values))
                    (alter-var-root #'p/projected-value (constantly replacement))
                    (reset! nested (emit [[0 "b" :int64]] collected))
                    (original type values))]
      (is (= [1 3 20 nil nil nil] (emit plan collected)))
      (is (= [1 2 2 3] @effects))
      (is (= [20 nil] @nested)))
    (with-redefs [p/projected-value (fn [& _] (throw (ex-info "controlled output failure" {})))]
      (is (thrown? clojure.lang.ExceptionInfo (emit plan collected))))
    (let [visited (atom [])]
      (with-redefs [p/projected-value
                    (fn [type values]
                      (swap! visited conj (first values))
                      (when (= 2 (first values))
                        (throw (ex-info "partial output failure" {})))
                      (original type values))]
        (is (thrown? clojure.lang.ExceptionInfo (emit plan collected)))
        (is (= [1 2] @visited))))
    (is (= [1 3 2 3 3 3] (emit plan collected)))
    (is (= [20 nil] @nested))))

(deftest wide-small-output-avoids-transient-setup-with-positive-promotion-control
  (let [[collect setups]
        (scheme/eval-string
         (str "(let ((count 0) (old jolt-transient-new)) "
              "(let ((jolt-transient-new (lambda (m) (set! count (+ count 1)) (old m)))) "
              "(jolt-vector "
              (slurp (io/resource "otel/exporter/chdb/native_declared_attributes.ss"))
              " (lambda () count))))"))
        input (into {} (map (fn [n] [(str n) n]) (range 32)))
        before (vec input)]
    (doseq [n [0 1 2 8]]
      (let [wanted (set (map str (range n)))]
        (is (= (portable-collect input wanted)
               (collect input wanted #'p/key-string #'clojure.core/contains?)))
        (is (zero? (setups)))))
    (let [wanted (set (map str (range 9)))]
      (is (= (portable-collect input wanted)
             (collect input wanted #'p/key-string #'clojure.core/contains?)))
      (is (= 1 (setups))))
    (is (= before (vec input)))))

(deftest unknown-normalized-keys-and-changing-membership-retain-ordered-fallback
  (let [collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?)
        input (into {} (map (fn [n] [(str n) n]) (range 16)))
        order (mapv first input) original @#'p/key-string membership contains?
        unusual (nth order 4)
        ;; Native collection intentionally holds the live contains? Var.
        ;; Jolt can inline an ordinary core contains? call, so use an explicit
        ;; Var invocation for this changed-root oracle, not the compiled helper.
        reference (fn [attrs wanted]
                    (reduce (fn [out [key value]]
                              (let [normalized (@#'p/key-string key)]
                                (if (@#'clojure.core/contains? wanted normalized)
                                  (assoc out normalized (conj (get out normalized []) value)) out)))
                            {} attrs))
        run (fn [f changing?]
              (let [effects (atom [])]
                (with-redefs [p/key-string
                              (fn [key]
                                (swap! effects conj [:key key])
                                (if (= key unusual) :non-string (original key)))
                              clojure.core/contains?
                              (fn [wanted key]
                                (swap! effects conj [:member key])
                                (when changing?
                                  (alter-var-root #'clojure.core/contains?
                                    (constantly (fn [_ next-key]
                                                  (swap! effects conj [:new-member next-key]) true))))
                                (membership wanted key))]
                  [(f input (conj (set order) :non-string)) @effects])))]
    (doseq [changing? [false true]]
      (is (= (run reference changing?) (run collect changing?))))))

(deftest changed-membership-can-exceed-the-compiled-wanted-capacity
  (let [collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?)
        input (into {} (map (fn [n] [(str n) n]) (range 16)))
        expected (into {} (map (fn [[key value]] [key [value]]) input))]
    (doseq [n [0 1 2]]
      (let [wanted (set (map str (range n))) calls (atom [])
            actual (with-redefs [clojure.core/contains?
                                (fn [_ key] (swap! calls conj key) true)]
                     (collect input wanted))]
        (is (= expected actual))
        (is (= (mapv first input) @calls))))))

(deftest indexed-plan-is-selected-once-and-retains-live-status-projection
  (let [{:keys [capability target vector-projector]} (vectors/plans false)
        builds (atom 0) original-builder @#'p/indexed-vector-projector
        indexed (with-redefs [p/indexed-vector-projector
                              (fn [& args] (swap! builds inc) (apply original-builder args))]
                  (p/trace-vector-projector capability target true))
        span (@#'fixture/span {"checkout.count" 7 "checkout.complete" false "checkout.name" ""})
        original @#'p/projected-value
        run (fn [projector failure?]
              (let [effects (atom [])
                    replacement (fn [type values]
                                  (swap! effects conj [:new type]) (original type values))]
                (with-redefs [p/projected-value
                              (fn [type values]
                                (swap! effects conj [:old type])
                                (alter-var-root #'p/projected-value (constantly replacement))
                                (when failure? (throw (ex-info "status failure" {:fixture true})))
                                (original type values))]
                  [(try (projector span) (catch clojure.lang.ExceptionInfo _ :failed)) @effects])))]
    (is (= 1 @builds))
    (is (= (vector-projector span) (indexed span)))
    (doseq [failure? [false true]]
      (is (= (run vector-projector failure?) (run indexed failure?))))
    (is (= (vector-projector span) (indexed span)))
    ;; Reversing physical field order is a real rejected control, not a green
    ;; plan-construction check disconnected from the produced vector.
    (let [wrong (with-redefs [p/indexed-vector-projector
                             (fn [locations wanted plan]
                               (original-builder locations wanted (vec (reverse plan))))]
                  (p/trace-vector-projector capability target true))]
      (is (not= (vector-projector span) (wrong span))))))

(deftest wide-declared-collection-preserves-order-duplicates-and-promotion
  (let [collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?)
        original @#'p/key-string
        input (into (hash-map "Aa" nil "BB" false)
                    (map (fn [n] [(str "extra-" n) n]) (range 126)))
        wanted (conj (set (map #(str "extra-" %) (range 12))) "collision")
        run (fn [f]
              (let [effects (atom [])]
                (with-redefs [p/key-string
                              (fn [k] (swap! effects conj k)
                                (if (contains? #{"Aa" "BB"} k) "collision" (original k)))]
                  [(f input wanted) @effects])))
        stock (run portable-collect)
        fast (run collect)
        wrong (scheme/eval-string
               (str "(let ((pmap-fold-seq-order pmap-fold-fwd)) "
                    (slurp (io/resource "otel/exporter/chdb/native_declared_attributes.ss")) ")"))]
    (is (= (hash "Aa") (hash "BB")))
    (is (= stock fast))
    (is (= (vec (first stock)) (vec (first fast))))
    (is (= (class (first stock)) (class (first fast))))
    (is (= 2 (count (get (first fast) "collision"))))
    ;; Reject the tempting but differently ordered HAMT walk locally.
    (is (not= stock (run #(wrong %1 %2 #'p/key-string #'clojure.core/contains?))))
    (is (false? (collect (assoc input "over-bound" 1) wanted)))
    (is (false? (collect (sorted-map "Aa" 1 "BB" 2) wanted)))))

(deftest wide-declared-live-converters-reentry-and-failure
  (let [collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?)
        original @#'p/key-string input (into {} (map (fn [n] [(str n) n]) (range 16)))
        first-key (ffirst (seq input)) wanted #{"0" "1"}
        run (fn [f failure?]
              (let [effects (atom []) nested (atom nil)
                    replacement (fn [k] (swap! effects conj [:new k]) (original k))]
                (with-redefs [p/key-string
                              (fn [k]
                                (swap! effects conj [:old k])
                                (alter-var-root #'p/key-string (constantly replacement))
                                (reset! nested (f {"0" 99} wanted))
                                (when failure? (throw (ex-info "controlled failure" {:fixture true})))
                                (original k))]
                  [(try (f input wanted) (catch clojure.lang.ExceptionInfo _ :failed))
                   @effects @nested])))]
    (is (= first-key (ffirst (seq input))))
    (doseq [failure? [false true]]
      (is (= (run portable-collect failure?) (run collect failure?))))
    (is (= (portable-collect input wanted) (collect input wanted)))))

(deftest native-collection-is-equivalent-and-declines-other-map-layouts
  (doseq [mixed? [false true]]
    (let [{:keys [capability target columns map-projector vector-projector]} (vectors/plans mixed?)
          native-projector (p/trace-vector-projector capability target true)
          inputs [{}
                  (array-map "checkout.count" 9007199254740993 "checkout.name" "" "checkout.complete" false)
                  (array-map "checkout.count" nil "checkout.complete" "wrong" :unused {:x 1})
                  (array-map :unused "keep" :checkout.count 7 "checkout.count" 7)
                  (into {} (map (fn [n] [(str "unused-" n) n]) (range 20)))
                  (into (hash-map "Aa" 1 "BB" 2)
                        (map (fn [n] [(str "unused-" n) n]) (range 20)))
                  (array-map "shared.location" "span")]]
      (doseq [attrs inputs]
        (let [span (assoc (@#'fixture/span attrs)
                         :resource {:attributes (array-map "shared.location" "resource")}
                         :scope {:attributes (array-map "shared.location" "")})]
          (is (= (observe vector-projector span) (observe native-projector span)))
          (is (= (mapv (map-projector span) (subvec columns (count spans/columns)))
                 (native-projector span))))))))

(deftest direct-small-map-collection-preserves-duplicates-and-live-vars
  (let [collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?)
        wanted #{"checkout.count"}
        attrs (array-map :unused "keep" :checkout.count nil "checkout.count" false)]
    (is (= {"checkout.count" [nil false]} (collect attrs wanted)))
    (is (= {} (collect (into {} (map (fn [n] [(str n) n]) (range 20))) wanted)))
    (is (false? (collect (into {} (map (fn [n] [(str n) n]) (range 129))) wanted)))
    (let [calls (atom []) original @#'p/key-string
          replacement (fn [key] (swap! calls conj [:new key]) (original key))
          first-key (fn [key]
                      (swap! calls conj [:old key])
                      (alter-var-root #'p/key-string (constantly replacement))
                      (original key))]
      (with-redefs [p/key-string first-key]
        (is (= {"checkout.count" [nil false]} (collect attrs wanted)))
        (is (= [[:old :unused] [:new :checkout.count] [:new "checkout.count"]] @calls))))))

(deftest reentrant-and-failed-collections-do-not-share-storage
  (let [collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?)
        original @#'p/key-string wanted #{"checkout.count"}
        nested (atom nil)
        attrs (array-map :checkout.count 1 :unused 0 "checkout.count" 2)]
    (with-redefs [p/key-string (fn [key]
                                (when (= :unused key)
                                  (reset! nested (collect {"checkout.count" 999} wanted)))
                                (original key))]
      (is (= {"checkout.count" [1 2]} (collect attrs wanted))))
    (is (= {"checkout.count" [999]} @nested))
    (with-redefs [p/key-string (fn [_] (throw (ex-info "converter failed" {:reason :test})))]
      (is (thrown? clojure.lang.ExceptionInfo (collect attrs wanted))))
    (is (= {"checkout.count" [1 2]} (collect attrs wanted)))
    (is (= {"checkout.count" [999]} @nested))))

(deftest actual-native-projector-selects-small-map-collector
  (let [{:keys [capability target]} (vectors/plans false)
        selected (atom 0)
        collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?)
        project (p/trace-vector-projector capability target true)]
    (with-redefs [p/small-declared-collector
                  (delay (fn [attrs wanted]
                           (let [out (collect attrs wanted)]
                             (when out (swap! selected inc)) out)))]
      (is (vector? (project (@#'fixture/span {"checkout.count" 7}))))
      (is (pos? @selected)))))
