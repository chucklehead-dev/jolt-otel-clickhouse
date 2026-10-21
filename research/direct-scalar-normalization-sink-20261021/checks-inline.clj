(load-file "research/direct-scalar-normalization-sink-20261021/inline.clj")
(require '[clojure.test :refer [deftest is run-tests]])
(def policy (Object.))
(def original-policy policy)
(def sdk (slurp (first *command-line-args*)))
(def copy-context (sink-context :copy sdk))
(def direct-context (inline-context sdk))
(def bindings [[#'policy original-policy]])
(defn input [n]
  (into {} (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "λ\"\\\n😀" 1 false i)])) (range n)))
(defn emit [context rows limit depth]
  (sink-encode context bindings 100 rows limit depth))
(defn failure [f]
  (try (f) nil (catch Throwable ex (or (.getMessage ex) ""))))

(deftest valid-domain-byte-equivalence
  (doseq [m [(input 9) (input 17) (input 128)
             (assoc (input 9) "key-0" "")
             (assoc (input 9) "key-0" (apply str (repeat 100 "x")))
             (assoc (input 9) "key-2" (scheme/eval-string "(most-negative-fixnum)"))
             (assoc (input 9) "key-2" (scheme/eval-string "(most-positive-fixnum)"))]]
    (let [expected (emit copy-context [m] 100000 128)]
      (is (bytes-equal? expected (emit direct-context [m] 100000 128)))
      (is (bytes-equal? expected (emit direct-context [m] (alength expected) 128)))
      (is (str/includes? (failure #(emit direct-context [m] (dec (alength expected)) 128)) "byte limit exceeded"))
      (is (str/includes? (failure #(emit direct-context [m] 100000 0)) "depth limit exceeded")))))

(deftest strict-rejection-is-not-sdk-fallback-equivalence
  ;; Error categories differ: this screen claims strict failure/no result, NOT
  ;; preservation of the configured SDK's error shape or generic fallback.
  (doseq [m [(input 8) (input 129) (assoc (input 9) :alias 1)
             (assoc (input 9) "key-2" nil) (assoc (input 9) "key-2" 1.25)
             (assoc (input 9) "key-2" 9223372036854775807N)
             (assoc (input 9) "key-2" [1])
             (assoc (input 9) "key-0" (apply str (repeat 101 "x")))]]
    (is (string? (failure #(emit direct-context [m] 100000 128)))))
  (let [collision (assoc (input 9) "Aa" 1 "BB" 2)]
    (is (= (hash "Aa") (hash "BB")))
    (is (false? (((:policy-maker copy-context) bindings 100) collision)))
    ;; Broader collision admission is explicitly observable, not quietly called
    ;; policy equivalence. Real SDK integration remains unimplemented.
    (is (pos? (alength (emit direct-context [collision] 100000 128))))))

(deftest output-ownership-and-live-policy
  (let [rows [(input 17)] a (emit direct-context rows 100000 128)
        b (emit direct-context rows 100000 128)]
    (aset-byte b 0 120)
    (is (not (bytes-equal? a b)))
    (is (bytes-equal? a (emit copy-context rows 100000 128)))
    (is (string? (failure #(emit direct-context [(assoc (input 17) "key-2" nil)] 100000 128))))
    (is (bytes-equal? a (emit direct-context rows 100000 128)))
    (with-redefs [policy (Object.)]
      (is (str/includes? (failure #(emit direct-context rows 100000 128)) "Scalar input declined")))))

(deftest late-failure-does-not-consume-later-record
  (let [asked (atom [])
        rows (letfn [(step [i]
                       (lazy-seq
                         (swap! asked conj i)
                         (cons (if (= i 1) (assoc (input 17) "key-2" nil) (input 17))
                               (step (inc i)))))] (step 0))]
    (is (str/includes? (failure #(emit direct-context rows 100000 128)) "unadmitted scalar"))
    (is (= [0 1] @asked))))

(let [r (run-tests 'user)]
  (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))
