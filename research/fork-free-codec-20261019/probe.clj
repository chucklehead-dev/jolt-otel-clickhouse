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
(check (= "\"\\u00e9\\ud83d\\ude00\""
          (String. ((scheme/proc "ffc-json-ascii-bytes") "é😀" 100 128) "UTF-8")))
(check (= "\"y\"" (text :x/y)))
(check (= "[1,2,3]" (text [1 2 3])))
(check (= "{\"a\":1,\"b\":false}" (text (array-map "a" 1 "b" false))))
(check (= "{\"a\":1,\"z\":2}" (text (sorted-map "z" 2 "a" 1))))

;; Compare visit order against the independently constructed ordinary seq.
(doseq [m [(array-map) (array-map :a 1 :b 2)
           (into {} (map (fn [i] [(str "k" i) i]) (range 1100)))
           (into (hash-map) {"Aa" 1 "BB" 2 "AaAa" 3 "BBBB" 4})
           (hash-map "Aa" 1 "BB" 2)
           (sorted-map)
           (into (sorted-map) (map (fn [i] [(str "key-" i) i]) (range 65)))
           (into (sorted-map-by #(compare %2 %1)) (map (fn [i] [(str "key-" i) i]) (range 65)))]]
  ;; Return a Jolt vector at the bridge rather than interpreting a Scheme list.
  (let [actual ((scheme/eval-string
                 "(lambda (m) (apply jolt-vector (reverse (ffc-map-fold m (lambda (k v a) (cons (jolt-vector k v) a)) '()))))") m)]
    (check (= (mapv vec m) actual))))

;; The stock representation has array or hnode roots, never a bare hcoll.
;; Verify the whole-map two-key collision really reaches that representation.
(check (= [true true]
          ((scheme/eval-string
             "(lambda (m) (let ((r (pmap-root m)))
                (jolt-vector (hnode? r)
                  (and (hnode? r) (= (vector-length (hnode-arr r)) 1)
                       (hcoll? (vector-ref (hnode-arr r) 0))))))")
           (hash-map "Aa" 1 "BB" 2))))

;; Chez rejects surrogate code points when constructing a character. This
;; validates the input representation boundary, NOT reachability of the
;; encoder's defensive surrogate branch with an ordinary Chez string.
(check (rejects? #((scheme/proc "integer->char") 55296)))
(check (rejects? #((scheme/proc "integer->char") 57343)))

(let [armed (atom false)
      m (into (sorted-map-by (fn [a b] (when @armed (throw (ex-info "comparator called" {})))
                               (compare b a)))
              (map (fn [i] [(str "k" i) i]) (range 65)))
      expected (str "{" (clojure.string/join "," (map (fn [[k v]] (str "\"" k "\":" v)) m)) "}")]
  (reset! armed true)
  (check (= expected (text m))))
(let [m (sorted-map "a" 1)]
  ((scheme/eval-string
     "(lambda (m) (jolt-ref-put! m kw-ops empty-pmap))") m)
  (check (rejects? #(text m))))

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
(doseq [n [0 1 65530 65531 65532 65533 65534 65535 65536]
        ascii? [false true]]
  (let [s (str (apply str (repeat n "x")) "😀\n\"\\/")
        writer (if ascii? (scheme/proc "ffc-json-ascii-bytes") encode)
        bytes (writer s 262144 128)
        size (alength bytes)]
    (check (= (String. bytes "UTF-8") (String. (writer s size 128) "UTF-8")))
    (check (rejects? #(writer s (dec size) 128)))))
(let [project (scheme/proc "ffc-json-project-ascii-lines-bytes")
      calls (atom [])]
  (check (= "{\"n\":1}\n{\"n\":2}\n"
            (String. (project [1 2] (fn [n] (swap! calls conj n) {"n" n}) 100 128) "UTF-8")))
  (check (= [1 2] @calls))
  (reset! calls [])
  (check (rejects? #(project [1 2 3] (fn [n] (swap! calls conj n) (apply str (repeat 100 "x"))) 5 128)))
  (check (= [1] @calls))
  (check (= "[\"nested\"]\n"
            (String. (project [1] (fn [_]
                                   (check (= "\"nested\"" (String. (encode "nested" 100 128) "UTF-8")))
                                   ["nested"]) 100 128) "UTF-8"))))
(println (pr-str {:scope :stock-jolt-explicit-codec-research :checks @checks
                  :compiler-fork false :data-json-dependency false
                  :standalone-qualified false :durable-qualified false}))
