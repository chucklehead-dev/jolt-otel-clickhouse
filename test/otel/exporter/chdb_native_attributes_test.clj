(ns otel.exporter.chdb-native-attributes-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [jolt.scheme :as scheme]
            [clojure.data.json :as json]
            [jdbc.core :as jdbc]
            [jdbc.chdb.json-each-row :as encoder]
            [otel.exporter.chdb :as exporter]))

(defn convert [backend input]
  (with-bindings {#'exporter/*json-backend* backend}
    (@#'exporter/attrs input)))

(defn native? [] (string? (System/getProperty "jolt.version")))

(deftest hash-value-clone-positive-parity-and-input-ownership
  (when (native?)
    (let [clone (scheme/eval-string
                  (slurp (io/resource "otel/exporter/chdb/native_attribute_value_clone.ss")))]
      (doseq [n [9 16 40 128]]
        (let [input (with-meta (into {} (map (fn [i] [(str "key-" i) i]) (range n))) {:input true})
              before (vec input) stock (convert :configured input)
              actual (clone input #'exporter/key-string #'exporter/value-string)]
          (is (map? actual))
          (is (= stock actual)) (is (= (vec stock) (vec actual)))
          (is (= (class stock) (class actual))) (is (= (meta stock) (meta actual)))
          (is (= (hash stock) (hash actual)))
          (is (= (json/write-str stock) (json/write-str actual)))
          (is (= before (vec input))) (is (= {:input true} (meta input)))))
      (doseq [input [(into {} (map (fn [i] [(str "key-" i) i]) (range 8)))
                     (into {} (map (fn [i] [(str "key-" i) i]) (range 129)))
                     (into {"Aa" 1 "BB" 2} (map (fn [i] [(str "key-" i) i]) (range 16)))]]
        (is (false? (clone input #'exporter/key-string #'exporter/value-string)))))))

(deftest hash-value-clone-key-change-failure-reentry-and-live-vars
  (when (native?)
    (let [input (into {} (map (fn [i] [(str "key-" i) i]) (range 16)))
          stock-key @#'exporter/key-string stock-value @#'exporter/value-string
          run (fn [backend fail?]
                (let [effects (atom []) visits (atom 0)]
                  (with-redefs [exporter/key-string
                                (fn [k] (swap! effects conj [:key k]) (stock-key k))
                                exporter/value-string
                                (fn [v]
                                  (swap! effects conj [:value v])
                                  (when (= 1 (swap! visits inc))
                                    (when fail? (throw (ex-info "clone controlled failure" {:fixture true})))
                                    (swap! effects conj [:nested (convert backend {:child "value"})])
                                    (alter-var-root #'exporter/key-string
                                      (constantly (fn [k] (swap! effects conj [:changed-key k]) "normalized"))))
                                  (stock-value v))]
                    [(try (let [out (convert backend input)]
                            [(vec out) (json/write-str out) (class out) (meta out)])
                          (catch Throwable e (ex-data e))) @effects])))]
      (doseq [fail? [false true]]
        (is (= (run :configured fail?) (run :native-guarded-byte-batch fail?))))
      (is (identical? stock-key @#'exporter/key-string))
      (is (identical? stock-value @#'exporter/value-string)))))

(deftest hash-value-clone-normalization-and-layout-matrix
  (when (native?)
    (let [stock-key @#'exporter/key-string]
      (doseq [n [8 9 16 31 32 64 128 129]
              key-mode [:strings :mixed :colliding]
              normalize [stock-key
                         (fn [k] (subs (str " " (stock-key k)) 1))
                         (fn [k] (str "group-" (mod (count (stock-key k)) 3)))]]
        (let [input (into (if (= key-mode :colliding) {"Aa" 1 "BB" 2} {})
                          (map (fn [i] [(if (and (= key-mode :mixed) (even? i))
                                          (keyword (str "key-" i)) (str "key-" i))
                                        (nth [nil false 42 "é/😀" [true 7]] (mod i 5))]) (range n)))
              before (vec input)]
          (with-redefs [exporter/key-string normalize]
            (let [stock (convert :configured input) actual (convert :native-guarded-byte-batch input)]
              (is (= stock actual)) (is (= (vec stock) (vec actual)))
              (is (= (class stock) (class actual)))
              (is (= (json/write-str stock) (json/write-str actual)))
              (is (= before (vec input))))))))))

(deftest native-slot-parity-class-order-metadata-and-fallback
  (when (native?)
    (doseq [input [nil false {} (with-meta (array-map :a 1 "a" 2 :ns/key "β😀") {:source true})
                   (array-map :empty nil :nested [true "é😀"] :negative -1)
                   (apply array-map (mapcat (fn [i] [(keyword (str "k" i)) i]) (range 8)))
                   (apply array-map (mapcat (fn [i] [(keyword (str "k" i)) i]) (range 9)))
                   (hash-map :a 1 :b 2)
                   (into {} (map (fn [n] [(str "key" n) n]) (range 40)))
                   (sorted-map :a 1 :b false) [[:a 1] ["a" 2]] (list [:a 1] [:b 2])]]
      (let [stock (convert :configured input) fast (convert :native-guarded-byte-batch input)]
        (is (= stock fast)) (is (= (vec stock) (vec fast)))
        (is (= (class stock) (class fast))) (is (= (meta stock) (meta fast)))
        (is (= (json/write-str stock) (json/write-str fast)))))))

(deftest wide-hash-collision-normalization-keeps-exact-seq-order
  (when (native?)
    (let [input (into (hash-map "Aa" 1 "BB" 2)
                      (map (fn [n] [(str "extra-" n) n]) (range 14)))
          old-key @#'exporter/key-string old-value @#'exporter/value-string
          observe (fn [backend]
                    (let [effects (atom [])]
                      (with-redefs [exporter/key-string
                                    (fn [k] (swap! effects conj [:key k])
                                      (if (contains? #{"Aa" "BB"} k) "collision" (old-key k)))
                                    exporter/value-string
                                    (fn [v] (swap! effects conj [:value v]) (old-value v))]
                        [(convert backend input) @effects])))
          stock (observe :configured)
          fast (observe :native-guarded-byte-batch)
          ;; Isolated lexical mutant, never replace a global runtime walker.
          wrong (scheme/eval-string
                  (str "(let ((pmap-fold-seq-order pmap-fold-fwd)) "
                       (slurp (io/resource "otel/exporter/chdb/native_attributes.ss")) ")"))]
      (is (= (hash "Aa") (hash "BB")))
      (is (= stock fast))
      (is (= (json/write-str (first stock)) (json/write-str (first fast))))
      (with-redefs [exporter/small-attribute-transform
                    (delay (fn [m] (wrong m #'exporter/key-string #'exporter/value-string)))]
        (is (not= stock (observe :native-guarded-byte-batch)))))))

(deftest native-path-is-positive-and-other-backends-stay-generic
  (when (native?)
    (let [input (array-map :a 1 :b 2) calls (atom 0) old transient]
      (with-redefs [clojure.core/transient (fn [m] (swap! calls inc) (old m))]
        (is (= {"a" "1" "b" "2"} (convert :native-guarded-byte-batch input)))
        (is (zero? @calls))
        (doseq [backend [:configured :native-guarded :native-guarded-string-cache]]
          (convert backend input))
        (is (= 3 @calls))
        (convert :native-guarded-byte-batch (apply array-map (interleave (range 9) (range 9))))
        (is (= 3 @calls))
        (convert :native-guarded-byte-batch (into {} (map (fn [n] [n n]) (range 129))))
        (is (= 4 @calls))))))

(deftest unavailable-native-projection-rejects-before-storage
  (when (native?)
    (let [error (ex-info "Unavailable projection fixture" {:fixture true}) opens (atom 0)]
      (with-redefs [exporter/small-attribute-transform (delay (throw error))
                    encoder/open-encoder (fn [_] {}) encoder/close! (fn [_] :closed)
                    jdbc/connection (fn [& _] (swap! opens inc))]
        (is (identical? error (try (exporter/exporter {:json-backend :native-guarded-byte-batch})
                                  (catch Throwable e e))))
        (is (zero? @opens))))))

(deftest changed-private-converter-retains-generic-path
  (when (native?)
    (let [calls (atom 0) old @#'exporter/array-map-attrs]
      (with-redefs [exporter/array-map-attrs (fn [m] (swap! calls inc) (old m))]
        (is (= {"a" "1"} (convert :native-guarded-byte-batch {:a 1})))
        (is (= 1 @calls))))))

(deftest converter-order-live-root-mutation-reentry-and-failure
  (when (native?)
    (let [input (array-map :a 1 :b 2) key-var #'exporter/key-string
          stock-key @key-var stock-value @#'exporter/value-string
          run (fn [backend failure?]
                (let [effects (atom [])]
                  (with-redefs [exporter/key-string (fn [k] (swap! effects conj [:key k]) (stock-key k))
                                exporter/value-string
                                (fn [v]
                                  (swap! effects conj [:value v])
                                  (when (= v 1)
                                    (when failure? (throw (ex-info "controlled failure" {:fixture true})))
                                    (swap! effects conj [:child (convert backend {:child "child"})])
                                    (alter-var-root key-var (constantly
                                      (fn [k] (swap! effects conj [:new-key k]) (str "new-" (stock-key k))))))
                                  (stock-value v))]
                    [(try (convert backend input) (catch Throwable e (ex-data e))) @effects])))]
      (doseq [failure? [false true]]
        (is (= (run :configured failure?) (run :native-guarded-byte-batch failure?))))
      (is (identical? stock-key @key-var))
      (is (identical? stock-value @#'exporter/value-string)))))

(deftest classifier-boundary-is-observed-before-conversion
  (when (native?)
    (require 'jolt.scheme)
    ;; Delegate every real classifier. The hook runs only for this input and
    ;; changes a converter at the second original classifier boundary.
    (let [observe ((ns-resolve 'jolt.scheme 'eval-string)
                   "(lambda (run watched hook)
                      (let ((old jolt-instance-site) (seen '()))
                        (dynamic-wind jolt-finally-in
                          (lambda ()
                            (set! jolt-instance-site
                              (lambda (site t value)
                                (when (eq? value watched)
                                  (set! seen (cons t seen)) (jolt-invoke0 hook))
                                (old site t value))))
                          (lambda () (jolt-vector (jolt-invoke0 run)))
                          (lambda () (set! jolt-instance-site old)))
                        (make-pvec (list->vector (reverse seen)))))")
          input (array-map :a 1 :b 2) key-var #'exporter/key-string
          stock-key @key-var
          run (fn [backend]
                (let [count (atom 0) converted (atom nil)]
                  (with-redefs [exporter/key-string stock-key]
                    (let [calls (observe #(reset! converted (convert backend input)) input
                                  #(when (= 2 (swap! count inc))
                                     (alter-var-root key-var (constantly (fn [k] (str "observed-" (stock-key k)))))))]
                      [calls @converted @count]))))
          baseline (run :configured) candidate (run :native-guarded-byte-batch)]
      (is (= 2 (last baseline)))
      (is (= {"observed-a" "1" "observed-b" "2"} (second baseline)))
      (is (= baseline candidate))
      (is (identical? stock-key @key-var)))))
