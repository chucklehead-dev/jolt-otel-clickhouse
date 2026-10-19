(require '[jolt.scheme :as scheme] '[clojure.edn :as edn])
(def here (first *command-line-args*))
(scheme/eval-string (slurp (str here "/traversal.ss")))
(scheme/eval-string (slurp (str here "/json.ss")))
(def encode (scheme/proc "ffc-json-bytes"))
(defn text [x] (String. (encode x 67108864 128) "UTF-8"))
(def checks (atom 0))
(defn check [x] (assert x (str "check " (inc @checks))) (swap! checks inc))
(defn rejects? [f] (try (f) false (catch Throwable _ true)))

;; Exact values, signs, digit scratch boundaries, Unicode and escaping.
(doseq [x [nil true false 0 1 -1 9223372036854775807 -9223372036854775808
           18446744073709551615 18446744073709551616 -9223372036854775809
           "" "a\"b\\c/d\n\t\r\b\f" "é😀" :x/y 'x/y [] {}]]
  (let [s (text x)]
    (check (string? s))
    (when (or (boolean? x) (integer? x)) (check (= x (edn/read-string s))))))
(check (= "null" (text nil)))
(check (= "\"é😀\"" (text "é😀")))
(check (= "\"y\"" (text :x/y)))
(check (= "[1,2,3]" (text [1 2 3])))
(check (= "{\"a\":1,\"b\":false}" (text (array-map "a" 1 "b" false))))

;; Compare visit order against the independently constructed ordinary seq.
(doseq [m [(array-map) (array-map :a 1 :b 2)
           (into {} (map (fn [i] [(str "k" i) i]) (range 1100)))
           (into (hash-map) {"Aa" 1 "BB" 2 "AaAa" 3 "BBBB" 4})]]
  ;; Return a Jolt vector at the bridge rather than interpreting a Scheme list.
  (let [actual ((scheme/eval-string
                 "(lambda (m) (apply jolt-vector (reverse (ffc-map-fold m (lambda (k v a) (cons (jolt-vector k v) a)) '()))))") m)]
    (check (= (mapv vec m) actual))))

;; Observe exception prefixes and nested folds while effects run, not just the
;; final set of visited entries. Force a real HAMT collision bucket above.
(let [m (into (hash-map) (map (fn [i] [(str "k" i) i]) (range 65)))
      seen (atom []) marker (ex-info "synthetic stop" {})
      fold (scheme/proc "ffc-map-fold")]
  (check (try
           (fold m (fn [k v a]
                     (swap! seen conj [k v])
                     (when (= 7 (count @seen)) (throw marker))
                     (check (= 3 (fold {:a 1 :b 2} (fn [_ v n] (+ n v)) 0)))
                     a) nil)
           false
           (catch Throwable e (identical? marker e))))
  (check (= (vec (take 7 m)) @seen)))

;; Limits, unsupported values, failure isolation, output ownership.
(check (rejects? #(encode [1 2] 4 128)))
(check (= "[1,2]" (String. (encode [1 2] 5 128) "UTF-8")))
(check (rejects? #(encode [[[1]]] 100 1)))
(check (rejects? #(encode (Object.) 100 128)))
(check (rejects? #(encode {nil 1} 100 128)))
(check (rejects? #(encode Double/NaN 100 128)))
(check (rejects? #(encode 1 -1 128)))
(let [a (encode [1] 100 128) b (encode [2] 100 128)]
  (aset-byte b 1 (byte 57))
  (check (= "[1]" (String. a "UTF-8")))
  (check (= "[9]" (String. b "UTF-8"))))
(doseq [n [0 1 65533 65534 65535 65536 131071]]
  (let [s (apply str (repeat n "x")) bytes (encode s (+ n 2) 128)]
    (check (= (+ n 2) (alength bytes)))
    (check (= (str "\"" s "\"") (String. bytes "UTF-8")))
    (check (rejects? #(encode s (+ n 1) 128)))))
(println (pr-str {:scope :stock-jolt-explicit-codec-research :checks @checks
                  :compiler-fork false :data-json-dependency false
                  :standalone-qualified false :durable-qualified false}))
