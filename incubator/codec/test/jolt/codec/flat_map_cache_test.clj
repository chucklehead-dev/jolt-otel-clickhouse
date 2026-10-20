(ns jolt.codec.flat-map-cache-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.codec.experimental :as codec]
            [jolt.traversal.experimental :as traversal]
            [jolt.scheme :as scheme]))

(defn fixture []
  (into {} (map (fn [i] [(str "key-" i) (str "text/\\\"\nλ😀-" i)]) (range 8))))
(defn text [bytes] (String. bytes "UTF-8"))
(defn rejects? [f] (try (f) false (catch Throwable _ true)))
(defn emit [c rows limit] (codec/encode-prefixed-records c "SQL\n" rows identity limit))

;; Mechanism-only instrumentation. Count real map walks and cache insertions
;; inside one isolated native kernel; never installed in production globals.
(def instrumented
  (delay
    (let [factory (scheme/eval-string
                    (str "(lambda (real-fold ffc-vector-fold ffc-records-fold ffc-stock-sorted-map? ffc-stock-record?)"
                         " (let ((walks 0) (stores 0) (charged 0) (real-set! hashtable-set!))"
                         " (let ((ffc-map-fold (lambda (m v a) (set! walks (+ walks 1)) (real-fold m v a)))"
                         "       (hashtable-set! (lambda (h k v) (set! stores (+ stores 1))"
                         "                         (set! charged (+ charged (bytevector-length v))) (real-set! h k v))))"
                         " (let () " @#'codec/json-source
                         " (lambda (enabled? rows limit depth)"
                         "   (set! walks 0) (set! stores 0) (set! charged 0)"
                         "   (let ((out (ffc-json-encode rows limit depth #t #t #f \"\" enabled?)))"
                         "      (jolt-vector out walks stores charged)))))))"))]
      (apply factory (traversal/native-kernels (traversal/open-traversal))))))

(deftest repeated-flat-maps-reuse-complete-bytes-not-walks
  (let [m (fixture) rows [m (with-meta m {:private :metadata}) m]
        [off walks-off stores-off] (@instrumented false rows 100000 128)
        [on walks-on stores-on] (@instrumented true rows 100000 128)]
    (is (= (text off) (text on)))
    (is (= [3 0 1 1] [walks-off stores-off walks-on stores-on]))
    (let [[_ visits] (@instrumented true [m (assoc m "key-0" "changed") m] 100000 128)]
      (is (= 2 visits)))
    (doseq [unsupported [(assoc m "key-0" false) (assoc m "key-0" {"nested" 1}) (into (sorted-map) m)]]
      (let [[a _ _] (@instrumented false [unsupported unsupported] 100000 128)
            [b _ stores] (@instrumented true [unsupported unsupported] 100000 128)]
        (is (= (text a) (text b)))
        (is (zero? stores))))))

(deftest insertion-fragment-and-retained-byte-bounds
  (let [base (fixture)
        small (mapv #(assoc base "id" (str %)) (range 500))
        [_ walks stores charged] (@instrumented true
                                  (into small [(first small) (last small)]) 1000000 128)]
    (is (= 501 walks))
    (is (= 64 stores))
    (is (<= charged 131072))
    (let [large (assoc base "large" (apply str (repeat 5000 "x")))
          [a _] (@instrumented false [large large] 1000000 128)
          [b walks stores] (@instrumented true [large large] 1000000 128)]
      (is (= (text a) (text b)))
      (is (= [2 0] [walks stores])))
    (let [rows (mapv #(assoc base "large" (str % (apply str (repeat 3000 "x")))) (range 100))
          [a _] (@instrumented false rows 1000000 128)
          [b _ stores charged] (@instrumented true rows 1000000 128)]
      (is (= (text a) (text b)))
      (is (pos? stores))
      (is (< stores 64))
      (is (<= charged 131072)))))

(deftest cached-map-does-not-bypass-current-depth-limit
  (let [m (fixture) off (codec/open-codec) on (codec/open-codec {:flat-map-cache? true})
        wrap (fn [n] (nth (iterate vector m) n))]
    ;; The first record warms the root at shallow depth; the second hits it
    ;; exactly at the maximum map depth, but its values would be too deep.
    (doseq [c [off on]]
      (is (rejects? #(emit c [m (wrap 128)] 100000))))
    (is (= (text (emit off [m (wrap 127)] 100000))
           (text (emit on [m (wrap 127)] 100000))))))

(deftest exact-budget-prefix-and-chunk-boundaries-on-hits
  (let [m (fixture) off (codec/open-codec) on (codec/open-codec {:flat-map-cache? true})]
    (doseq [prefix-size [0 1 65000 65530 65535 65536 65537]
            padding [0 1 100]]
      (let [prefix (apply str (repeat prefix-size "p"))
            rows [m (apply str (repeat padding "x")) m m]
            a (codec/encode-prefixed-records off prefix rows identity 100000)
            size (- (alength a) prefix-size)]
        (is (= (text a) (text (codec/encode-prefixed-records on prefix rows identity size))))
        (is (rejects? #(codec/encode-prefixed-records on prefix rows identity (dec size))))))))

(deftest failure-stops-producer-and-nested-calls-have-independent-storage
  (let [m (fixture) c (codec/open-codec {:flat-map-cache? true})
        single-size (- (alength (emit c [m] 100000)) 4)
        seen (atom [])
        source (lazy-seq (cons m (lazy-seq (cons m (lazy-seq (swap! seen conj :third) [m])))))]
    (is (rejects? #(codec/encode-prefixed-records c "SQL\n" source
                    (fn [x] (swap! seen conj :project) x) (+ single-size 10))))
    (is (= [:project :project] @seen))
    (let [inner (atom nil)
          a (codec/encode-prefixed-records c "" [m m]
              (fn [x] (reset! inner (emit c [x x] 100000)) x) 100000)
          expected (text a)]
      (aset-byte @inner 0 (byte 0))
      (is (= expected (text a)))
      (aset-byte a 0 (byte 0))
      (is (= expected (text (codec/encode-prefixed-records c "" [m m] identity 100000)))))))

(deftest invalid-options-reject
  (doseq [options [nil {:flat-map-cache? nil} {:flat-map-cache? "yes"} {:other true}]]
    (is (rejects? #(codec/open-codec options)))))
