(load-file "research/direct-scalar-normalization-sink-20261021/prototype.clj")
(require '[clojure.test :refer [deftest is run-tests]])
(def policy (Object.))
(def original-policy policy)
(def sdk (slurp (first *command-line-args*)))
(def contexts (into {} (map (fn [kind] [kind (sink-context kind sdk)]) [:copy :sink :unquoted-mutant])))
(def bindings [[#'policy original-policy]])
(defn emit [kind rows limit depth]
  (sink-encode (contexts kind) bindings 100 rows limit depth))
(defn input [n]
  (into {} (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "λ\"\\\n😀" 1 false i)])) (range n)))
(defn verdict [f]
  (try [:ok (f)]
       (catch Throwable ex [:error (.getMessage ex) (ex-data ex)])))

(deftest admitted-values-and-boundaries
  (let [low (scheme/eval-string "(most-negative-fixnum)")
        high (scheme/eval-string "(most-positive-fixnum)")]
    (doseq [m [(input 9) (input 17) (input 128)
               (assoc (input 9) "key-0" "")
               (assoc (input 9) "key-0" (apply str (repeat 100 "x")))
               (assoc (input 9) "key-2" low)
               (assoc (input 9) "key-2" high)
               (assoc (input 9) "key-1" true)
               (with-meta (input 9) {:not-a-field "metadata"})
               (assoc (input 9) "quote\"slash/\\key" -42)]]
      (let [expected (emit :copy [m] 100000 128)
            actual (emit :sink [m] 100000 128)]
        (is (bytes-equal? expected actual))
        (is (bytes-equal? expected (emit :sink [m] (alength expected) 128)))
        (is (= :error (first (verdict #(emit :sink [m] (dec (alength expected)) 128)))))
        (is (= :error (first (verdict #(emit :sink [m] 100000 0)))))))))

(deftest unsupported-input-retains-original-decline
  (doseq [m [(input 8) (input 129) (sorted-map "a" 1)
             (assoc (input 9) :alias 1)
             (assoc (input 9) "key-2" nil)
             (assoc (input 9) "key-2" 1.25)
             (assoc (input 9) "key-2" 9223372036854775807N)
             (assoc (input 9) "key-2" [1])
             (assoc (input 9) "key-0" (apply str (repeat 101 "x")))]]
    (let [baseline (verdict #(emit :copy [m] 100000 128))]
      (is (= :error (first baseline)))
      (is (= baseline (verdict #(emit :sink [m] 100000 128)))))))

(deftest policy-freshness-ownership-and-mutant
  (let [rows [(input 17)] baseline (emit :copy rows 100000 128)
        a (emit :sink rows 100000 128) b (emit :sink rows 100000 128)]
    (is (not (bytes-equal? baseline (emit :unquoted-mutant rows 100000 128))))
    (aset-byte b 0 120)
    (is (bytes-equal? baseline a))
    (is (not (bytes-equal? a b)))
    (doseq [kind [:copy :sink]]
      (with-redefs [policy (Object.)]
        (is (= :error (first (verdict #(emit kind rows 100000 128)))))))
    (is (bytes-equal? baseline (emit :sink rows 100000 128)))
    (is (zero? (alength (emit :copy [] 0 128))))
    (is (zero? (alength (emit :sink [] 0 128))))))

(deftest failure-does-not-request-later-records
  (doseq [kind [:copy :sink]]
    (let [asked (atom [])
          rows (letfn [(step [i]
                         (lazy-seq
                           (swap! asked conj i)
                           (cons (input 17) (step (inc i)))))] (step 0))]
      (is (= :error (first (verdict #(emit kind rows 1 128)))))
      (is (= [0] @asked)))))

(let [r (run-tests 'user)]
  (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))
