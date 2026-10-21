(ns otel.exporter.chdb-native-declared-attributes-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as scheme]
            [otel.exporter.chdb.attribute-projection :as p]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.native-projection :as native]
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

(defn- legacy-projected-value [type inputs]
  ;; Frozen pre-change rule; don't derive this oracle from the new macro.
  (let [codes (registry/status-code-lookup) fallback (@#'p/default-value type)]
    (cond
      (empty? inputs) [fallback (:absent codes)]
      (not= 1 (count inputs)) [fallback (:invalid codes)]
      (and (= :string type) (= "" (first inputs))) ["" (:present-empty codes)]
      (@#'p/valid-value? type (first inputs)) [(first inputs) (:valid codes)]
      :else [fallback (:invalid codes)])))

(defn- projection-verdict [f]
  (try [:ok (f)]
       (catch Throwable error [:error (class error) (.getMessage error) (ex-data error)])))

(deftest pair-and-continuation-retain-legacy-classification
  (doseq [type [:string :boolean :int64 :unknown]
          inputs [nil [] [nil] [false] [true] [""] ["é😀"] [0] [-1] [1.0]
                  [-9223372036854775808] [9223372036854775807]
                  [9223372036854775808N] [[1]] [{:nested true}] [1 2]
                  (list 42) (list "")]]
    (is (= (projection-verdict #(legacy-projected-value type inputs))
           (projection-verdict #(@#'p/projected-value type inputs))
           (projection-verdict #(@#'p/projected-value type inputs (fn [value status] [value status])))))))

(deftest continuation-mode-has-an-actual-arity-witness-and-live-fallback
  (let [[emit counts]
        (scheme/eval-string
          (str "(let ((two 0) (three 0) (old2 jolt-invoke2) (old3 jolt-invoke3)) "
               "(let ((jolt-invoke2 (lambda (f a b) (set! two (+ two 1)) (old2 f a b))) "
               "(jolt-invoke3 (lambda (f a b c) (set! three (+ three 1)) (old3 f a b c)))) "
               "(jolt-vector "
               (slurp (io/resource "otel/exporter/chdb/native_projected_values.ss"))
               " (lambda () (jolt-vector two three)))))"))
        plan [[0 "a" :int64] [0 "b" :string] [0 "c" :boolean]]
        collected [{"a" [7] "b" [""] "c" [false]}]
        original @#'p/original-projected-value]
    (is (= [7 3 "" 2 false 3] (emit plan collected #'p/projected-value original)))
    (is (= [0 3] (counts)))
    (is (= [7 3 "" 2 false 3] (emit plan collected #'p/projected-value)))
    (is (= [3 3] (counts)))
    (with-redefs [p/projected-value (fn [_ _] (list :replacement))]
      (is (= [:replacement nil :replacement nil :replacement nil]
             (emit plan collected #'p/projected-value original))))
    (is (= [6 3] (counts)))))

(deftest continuation-runtime-is-qualified-before-returning-a-factory
  (let [without-three
        (scheme/eval-string
          "(lambda (run bad)
             (let ((old jolt-invoke3))
               (dynamic-wind jolt-finally-in
                 (lambda () (set! jolt-invoke3 bad) (jolt-invoke0 run))
                 (lambda () (set! jolt-invoke3 old)))))")
        wrong-arity (scheme/eval-string "(lambda (f a b) #f)")]
    (doseq [bad [false wrong-arity]]
      (is (thrown? Throwable
            (without-three
              #(native/load-positional-output! #'p/projected-value @#'p/original-projected-value) bad)))
      ;; A legacy factory needs no 3-argument bridge and must still be usable.
      (let [legacy (without-three #(native/load-positional-output! #'p/projected-value) bad)]
        (is (= [7 3] (legacy [[0 "a" :int64]] [{"a" [7]}])))))))

(deftest retained-native-continuation-never-borrows-output-storage
  (let [source (slurp (io/resource "otel/exporter/chdb/native_projected_values.ss"))
        unsafe (str/replace source
                 "(jolt-invoke3 projector type inputs return-pair)"
                 (str "(jolt-invoke3 projector type inputs "
                      "(lambda (v s) (vector-set! out offset v) "
                      "(vector-set! out (fx+ offset 1) s) (values v s)))"))
        invoke-late (scheme/eval-string
                      "(lambda (f) (let-values (((v s) (jolt-invoke2 f 99 98))) (jolt-vector v s)))")
        original @#'p/projected-value
        plan [[0 "a" :int64] [0 "b" :int64] [0 "c" :int64]]
        collected [{"a" [1] "b" [2] "c" [3]}]
        expected [1 3 2 3 3 3]]
    (is (not= source unsafe) "unsafe control must really replace the continuation")
    (doseq [[mutant? text] [[false source] [true unsafe]]]
      (let [emit (scheme/eval-string text) retained (atom nil)
            observed (fn
                       ([type inputs] (original type inputs))
                       ([type inputs continuation]
                        (reset! retained continuation)
                        (original type inputs continuation)))
            result (with-redefs [p/projected-value observed]
                     ;; Unit-only trusted identity arms the actual 3-arg path.
                     (emit plan collected #'p/projected-value observed))]
        (is (= expected result))
        (is (= [99 98] (invoke-late @retained)))
        ;; Don't snapshot with (vec result): on a vector that aliases itself.
        (is (= mutant? (not= expected result)))))))

(deftest continuation-mode-observes-codebook-induced-root-change-and-reentry
  (let [original @#'p/projected-value lookup registry/status-code-lookup
        plan [[0 "a" :int64] [0 "b" :int64] [0 "c" :int64]]
        collected [{"a" [1] "b" [2] "c" [3]}]
        run (fn [fast?]
              (let [emit (if fast?
                           (native/load-positional-output! #'p/projected-value @#'p/original-projected-value)
                           (native/load-positional-output! #'p/projected-value))
                    effects (atom []) nested (atom nil)]
                (with-redefs [p/projected-value original
                              registry/status-code-lookup
                              (fn []
                                (swap! effects conj :lookup)
                                (alter-var-root #'p/projected-value
                                  (constantly (fn [_ inputs]
                                                (swap! effects conj (first inputs))
                                                (list (* 10 (first inputs))))))
                                (reset! nested (emit [[0 "b" :int64]] collected))
                                (lookup))]
                  [(emit plan collected) @effects @nested])))]
    (is (= [[1 3 20 nil 30 nil] [:lookup 2 2 3] [20 nil]] (run false)))
    (is (= (run false) (run true)))))

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
                (emit plan collected #'p/projected-value))
            direct (emit plan collected #'p/projected-value @#'p/original-projected-value)]
        (is (= 3 (- (seals) before)))
        (is (= (vec (mapcat (fn [[_ k type]] (original type (get (first collected) k []))) plan)) a))
        (is (= a direct))
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
        ;; This test owns the indexed fallback's mechanism, not whichever
        ;; faster projector a supported input may now select by default.
        indexed (with-redefs [native/load-trace-field-slots! (fn [& _] nil)
                              p/indexed-vector-projector
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
    (let [wrong (with-redefs [native/load-trace-field-slots! (fn [& _] nil)
                             p/indexed-vector-projector
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

(deftest indexed-fallback-projector-selects-small-map-collector
  (let [{:keys [capability target]} (vectors/plans false)
        selected (atom 0)
        collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?)
        ;; Keep the old collector's branch witness live. The new field-slot
        ;; path has its own fallback-throws and wrong-folder controls.
        project (with-redefs [native/load-trace-field-slots! (fn [& _] nil)]
                  (p/trace-vector-projector capability target true))]
    (with-redefs [p/small-declared-collector
                  (delay (fn [attrs wanted]
                           (let [out (collect attrs wanted)]
                             (when out (swap! selected inc)) out)))]
      (is (vector? (project (@#'fixture/span {"checkout.count" 7}))))
      (is (pos? @selected)))))

(deftest owned-string-collector-preserves-shape-order-and-duplicate-fallback
  (let [fast (scheme/eval-string
               (slurp (io/resource "otel/exporter/chdb/native_declared_strings.ss")))
        collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?
                                                @#'p/original-key-string)]
    (doseq [n [0 1 8 9 16 128]
            wanted [#{} #{"0" "7" "Aa" "BB"}]]
      (let [input (into {} (map #(vector (str %) %) (range n)))
            input (if (> n 8) (assoc input "Aa" 101 "BB" 102) input)]
        (when (<= (count input) 128)
          (let [expected (portable-collect input wanted)
                actual (fast input wanted #'p/key-string #'clojure.core/contains?
                             @#'p/original-key-string)]
            (is (map? actual))
            (is (= expected actual)) (is (= (vec expected) (vec actual)))
            (is (= (class expected) (class actual)))
            (is (= (meta expected) (meta actual)))))))
    (let [input {"checkout.count" 1 :checkout.count 2} wanted #{"checkout.count"}]
      (is (false? (fast input wanted #'p/key-string #'clojure.core/contains?
                       @#'p/original-key-string)))
      (is (= {"checkout.count" [1 2]} (collect input wanted))))))

(deftest owned-string-collector-rejects-preinstalled-policy-and-preserves-effects
  (let [input (into {} (map #(vector (str %) %) (range 16))) wanted #{"0" "7"}
        fast (scheme/eval-string
               (slurp (io/resource "otel/exporter/chdb/native_declared_strings.ss")))
        original @#'p/original-key-string]
    (doseq [changed [:normalizer :membership]]
      (let [effects (atom [])
            replacements
            (case changed
              :normalizer {#'p/key-string (fn [key] (swap! effects conj [:key key]) (original key))}
              :membership {#'clojure.core/contains?
                           (fn [_ key] (swap! effects conj [:member key]) (= key "7"))})]
        (with-redefs-fn replacements
          (fn []
            ;; Even a replacement installed before construction is not stock.
            (let [collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains? original)]
              (is (false? (fast input wanted #'p/key-string #'clojure.core/contains? original)))
              (is (empty? @effects))
              (let [out (collect input wanted)]
                (is (= 16 (count @effects)))
                (is (= (mapv first input) (mapv second @effects)))
                (is (= (if (= changed :membership) {"7" [7]} {"0" [0] "7" [7]}) out))))))))))

(deftest owned-string-collector-unsupported-inputs-run-generic-callbacks-once
  (let [fast (scheme/eval-string
               (slurp (io/resource "otel/exporter/chdb/native_declared_strings.ss")))
        original @#'p/original-key-string]
    (doseq [input [{"0" 1 :0 2} (into {} (map #(vector (str %) %) (range 129)))]
            wanted [#{"0"} (set (map str (range 9)))]]
      (is (false? (fast input wanted #'p/key-string #'clojure.core/contains? original))))
    (let [calls (atom []) input {"before" 1 :alias 2 "after" 3}
          collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains? original)]
      (with-redefs [p/key-string (fn [key] (swap! calls conj key) (original key))]
        (is (= (portable-collect input #{"alias"}) (collect input #{"alias"})))
        (is (= (concat (map first input) (map first input)) @calls))))))

(deftest owned-string-collector-actually-selects-fast-path-and-keeps-fallback
  (with-redefs [native/declared
                "(lambda (m wanted key-var contains-var) (error 'control \"generic collector reached\"))"]
    (let [collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains?
                                                  @#'p/original-key-string)]
      (is (= {"count" [7]} (collect {"count" 7 "other" false} #{"count"})))
      (is (= {"7" [7]} (collect (into {} (map #(vector (str %) %) (range 16))) #{"7"})))
      (with-redefs [p/key-string (fn [key] key)]
        (is (thrown? Throwable (collect {"count" 7} #{"count"}))))
      (is (thrown? Throwable (collect {:count 7} #{"count"}))))))

(deftest owned-string-collector-declines-changed-string-predicate
  (let [fast (scheme/eval-string
               (slurp (io/resource "otel/exporter/chdb/native_declared_strings.ss")))
        original-string? string?
        result (with-redefs [clojure.core/string? (fn [value] (original-string? value))]
                 (fast {"count" 7} #{"count"} #'p/key-string #'clojure.core/contains?
                       @#'p/original-key-string))]
    (is (false? result))))

(deftest owned-string-collector-wanted-boundaries-and-bound-vars-remain-live
  (let [factory (scheme/eval-string
                  (slurp (io/resource "otel/exporter/chdb/native_declared_strings.ss")))
        original @#'p/original-key-string
        fast (factory #'p/key-string #'clojure.core/contains? original)
        input (into {} (map #(vector (str %) %) (range 16)))
        eight (set (map str (range 8)))]
    (is (= (portable-collect input eight) (fast input eight)))
    (is (= (vec (portable-collect input eight)) (vec (fast input eight))))
    (is (false? (fast input (conj eight "8"))))
    (is (false? (fast input #{"0" :non-string})))
    (with-redefs [p/key-string (fn [key] key)]
      (is (false? (fast input eight))))
    (is (= (portable-collect input eight) (fast input eight)))))

(deftest owned-string-collector-post-scan-policy-change-declines-before-fallback
  (let [source (slurp (io/resource "otel/exporter/chdb/native_declared_strings.ss"))
        changed (atom 0) calls (atom []) original @#'p/original-key-string
        mutation (fn [] (swap! changed inc)
                   (alter-var-root #'p/key-string
                     (constantly (fn [key] (swap! calls conj key) (original key)))))
        injected (str "(lambda (change) (let ((fold pmap-fold-seq-order) (calls 0)) "
                      "(let ((pmap-fold-seq-order (lambda (m f init) "
                      "(let ((out (fold m f init))) (set! calls (+ calls 1)) "
                      "(when (= calls 2) (jolt-invoke0 change)) out)))) " source ")))" )
        fast-factory ((scheme/eval-string injected) mutation)
        input (into {} (map #(vector (str %) %) (range 16))) wanted #{"7"}]
    (with-redefs [p/key-string original]
      (let [fast (fast-factory #'p/key-string #'clojure.core/contains? original)]
        (is (false? (fast input wanted)))
        (is (= 1 @changed)) (is (empty? @calls))
        ;; The declined pure scan has not run a converter. Its normal fallback
        ;; therefore runs the new converter exactly once for each input entry.
        (let [collect (native/load-declared-collector! #'p/key-string #'clojure.core/contains? original)]
          (is (= {"7" [7]} (collect input wanted)))
          (is (= (mapv first input) @calls)))))))

(defn -main [& _]
  (let [r (clojure.test/run-tests 'otel.exporter.chdb-native-declared-attributes-test)]
    (System/exit (if (and (pos? (:test r)) (zero? (+ (:fail r) (:error r)))) 0 1))))
