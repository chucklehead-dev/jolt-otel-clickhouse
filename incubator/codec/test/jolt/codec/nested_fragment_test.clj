(ns jolt.codec.nested-fragment-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.codec.experimental :as codec]
            [jolt.traversal.experimental :as traversal]
            [jolt.scheme :as scheme]))

(defn rejects? [f] (try (f) false (catch Throwable _ true)))
(defn text [bytes] (String. bytes "UTF-8"))
(defn fixture []
  (into (sorted-map)
    (map (fn [i] [(str "key-" i)
                   (case (mod i 5) 0 false 1 nil 2 i 3 1.25 "quote\"/\\λ😀")]) (range 12))))

(def instrumented
  (delay
    (let [factory (scheme/eval-string
                    (str "(lambda (real-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?)"
                         " (let ((walks 0) (stores 0) (charged 0) (real-set! hashtable-set!))"
                         " (let ((ffc-map-fold (lambda (m v a) (set! walks (+ walks 1)) (real-fold m v a)))"
                         "       (hashtable-set! (lambda (h k v) (set! stores (+ stores 1))"
                         "                         (set! charged (+ charged (bytevector-length (if (bytevector? v) v (car v))))) (real-set! h k v))))"
                         " (let () " @#'codec/json-source
                         " (define observed #f)"
                         " (define original-new ffc-new-fragments)"
                         " (set! ffc-new-fragments (lambda () (let ((cache (original-new)))"
                         "   (set! observed cache) cache)))"
                         " (jolt-vector ffc-json-nested-scope"
                         "  (lambda () (jolt-vector walks stores charged))"
                         "  (lambda (value)"
                         "    (ffc-json-encode/shared value 100000 128 #f #f #f \"\" (ffc-new-fragments)))"
                         "  (lambda (value) (ffc-json-ascii-bytes value 100000 128))"
                         "  (lambda () (jolt-vector (hashtable? (ffc-fragments-table observed))"
                         "    (ffc-fragments-insertions observed) (ffc-fragments-retained observed)"
                         "    (bytevector? (ffc-fragments-scratch observed))"
                         "    (bytevector? (ffc-fragments-digits observed))))"
                         " ffc-json-nested-literal-scope)))))"))]
      (apply factory (traversal/native-kernels (traversal/open-traversal))))))

(deftest fused-scope-reuses-escaped-fragments-with-logical-bounds-and-clear
  (let [[_ stats _ render state open] @instrumented [write close] (open)
        c (codec/open-codec) m (fixture) before (stats)
        ordinary (codec/write-nested c m) limit (count ordinary)
        one (write m limit 128) two (write m limit 128)]
    (is (= (codec/write-nested c ordinary) (text (render one)) (text (render two))))
    (is (= [1 1] (mapv - (subvec (stats) 0 2) (subvec before 0 2))))
    (is (rejects? #(write m (dec limit) 128)))
    (is (rejects? #(write [m] 100000 1)))
    (is (not= (text (render one)) (text (render (write (assoc m "key-0" true) 100000 128)))))
    (close)
    (is (= [false 0 0 false false] (state)))
    (is (rejects? #(write m 100000 128)))
    (is (= (codec/write-nested c ordinary) (text (render one))))
    (let [held (atom nil) marker (ex-info "stop" {})]
      (is (identical? marker
            (try (codec/with-nested-literal-writer c
                   (fn [w] (reset! held w) (w m) (throw marker)))
                 nil (catch Throwable error error))))
      (is (rejects? #(@held m))))))

(deftest maps-share-only-complete-scalar-fragments
  (let [[open stats _ render state] @instrumented
        [write close] (open) c (codec/open-codec) m (fixture)
        before (stats) first (write m 100000 128)
        second (write (with-meta m {:source :test}) 100000 128)]
    (is (= (codec/write-nested c (codec/prepare-nested-text c m))
           (text (render first)) (text (render second))))
    (is (= [1 1] (mapv - (subvec (stats) 0 2) (subvec before 0 2))))
    (let [changed (assoc m "key-0" true)]
      (is (not= (text (render first)) (text (render (write changed 100000 128))))))
    (is (every? true? [(nth (state) 0) (pos? (nth (state) 1)) (pos? (nth (state) 2))
                       (nth (state) 3) (nth (state) 4)]))
    (close)
    (is (= [false 0 0 false false] (state)))
    (is (= (codec/write-nested c (codec/prepare-nested-text c m)) (text (render first))))
    (doseq [value [m [] nil false]] (is (rejects? #(write value 100000 128))))
    (let [[_ _ bad-mode] @instrumented] (is (rejects? #(bad-mode m))))))

(deftest public-scope-clears-on-return-and-original-exception
  (let [c (codec/open-codec) retained (atom nil) marker (ex-info "synthetic-stop" {})
        value (codec/with-nested-text-writer c
                (fn [write] (reset! retained write) (write (fixture))))]
    (is (= (codec/write-nested c (codec/prepare-nested-text c (fixture)))
           (codec/write-nested c value)))
    (is (rejects? #(@retained [])))
    (is (identical? marker
          (try (codec/with-nested-text-writer c
                 (fn [write] (reset! retained write) (write (fixture)) (throw marker)))
               nil (catch Throwable error error))))
    (is (rejects? #(@retained (fixture))))
    (is (rejects? #(codec/with-nested-text-writer nil identity)))
    (is (rejects? #(codec/with-nested-text-writer c nil)))
    (let [values [-9223372036854775808 0 18446744073709551615 1 -9 nil false 1.25]
          encoded (codec/with-nested-text-writer c (fn [write] (mapv write values)))]
      (doseq [[value output] (map vector values encoded)]
        (is (= (codec/write-nested c (codec/prepare-nested-text c value))
               (codec/write-nested c output)))))))

(deftest shared-hits-retain-current-depth-size-and-stock-map-guards
  (let [[open _ _ render] @instrumented [write close] (open) m (fixture)
        c (codec/open-codec) expected (codec/write-nested c m)
        limit (count expected)]
    (write m 100000 128)
    (is (= (codec/write-nested c expected) (text (render (write m limit 128)))))
    (is (rejects? #(write m (dec limit) 128)))
    (is (rejects? #(write (assoc m "novel" "longer") (dec limit) 128)))
    (is (rejects? #(write "x" 0 128)))
    (is (rejects? #(write (nth (iterate vector m) 128) 100000 128)))
    (is (= (codec/write-nested c (codec/prepare-nested-text c (nth (iterate vector m) 127)))
           (text (render (write (nth (iterate vector m) 127) 100000 128)))))
    (with-redefs-fn {(ns-resolve 'clojure.core 'sm-ops) {}}
      (fn [] (is (rejects? #(write m 100000 128)))))
    ;; A caught failure cannot contaminate later independent output.
    (is (= (codec/write-nested c expected) (text (render (write m limit 128)))))
    (close)))

(deftest insertion-retention-and-large-fragment-bounds
  (let [[open stats] @instrumented [write close] (open)
        base (into {} (fixture)) start (stats)]
    (doseq [i (range 100)] (write (assoc base "id" i) 100000 128))
    (let [delta (mapv - (stats) start)]
      (is (= 64 (nth delta 1)))
      (is (<= (nth delta 2) 131072)))
    (close)
    (let [[write close] (open) start (stats)
          padding (apply str (repeat 3500 "p"))
          large (apply str (repeat 3000 "x"))]
      (doseq [i (range 100)] (write [padding (assoc base "id" i "large" large)] 100000 128))
      (let [[_ stores charged] (mapv - (stats) start)]
        (is (pos? stores)) (is (< stores 64)) (is (<= charged 131072)))
      (close))
    (let [[write close] (open) start (stats)
          m (assoc base "large" (apply str (repeat 5000 "x")))]
      (write m 100000 128) (write m 100000 128)
      (is (zero? (- (nth (stats) 1) (nth start 1))))
      (close))))

(deftest nested-scopes-and-outer-output-own-their-bytes
  (let [c (codec/open-codec {:flat-map-cache? true}) m (fixture)
        encode (fn []
                 (codec/with-nested-text-writer c
                   (fn [write]
                     (let [a (write m) b (codec/with-nested-text-writer c #(% m))]
                       (codec/encode-prefixed-records c "" [a b] vector 100000)))))
        bytes (encode) expected (text bytes)]
    (aset-byte bytes 0 (byte 0))
    (is (= expected (text (encode))))
    (is (= expected
           (text (codec/encode-prefixed-records c ""
                   [(codec/prepare-nested-text c m) (codec/prepare-nested-text c m)] vector 100000))))
    (codec/with-nested-text-writer c
      (fn [write]
        (let [first (write m)
              large {"text" (apply str (repeat 70000 "y"))}
              second (write large)]
          (is (rejects? #(write [m (Object.)])))
          (is (= (codec/write-nested c (codec/prepare-nested-text c m)) (codec/write-nested c first)))
          (is (= (codec/write-nested c (codec/prepare-nested-text c large)) (codec/write-nested c second)))
          (is (= (codec/write-nested c first) (codec/write-nested c (write m)))))))))

(defn -main [& _]
  (let [r (clojure.test/run-tests 'jolt.codec.nested-fragment-test)]
    (assert (= 5 (:test r)))
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
