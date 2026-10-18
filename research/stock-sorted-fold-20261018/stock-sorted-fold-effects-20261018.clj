(require '[clojure.test :refer [deftest is run-tests]]
         '[clojure.data.json :as json] '[clojure.data.json.jolt-native :as native]
         '[jolt.host :as host] '[jolt.scheme :as scheme])
(load-file "/home/chuck/ai-src/evidence/install-stock-sorted-fold-probe-20261018.clj")

(defn probe-write [value out]
  ((stock-sorted-fold-diagnostic-factory) value out json/default-write-options
                                        @#'json/native-writer-stock))
(defn probe-string [value]
  (let [out (java.io.StringWriter.)] (probe-write value out) (.toString out)))
(def admitted-tree (scheme/proc "task-stock-sorted-scalar-tree"))

(deftest stock-admission-boundaries-and-custom-ops
  (doseq [n [0 1 8 17 64 128]]
    (let [value (into (sorted-map) (map (fn [i] [(str "key-" i) (case (mod i 3) 0 "λ/😀\n" 1 false i)]) (range n)))]
      ;; Empty tree is Scheme-truthy jolt-nil, not the #f decline sentinel.
      (if (zero? n) (is (nil? (admitted-tree value)))
        (is (vector? (admitted-tree value))))
      (is (= (json/write-str value) (probe-string value)))))
  (doseq [value [(sorted-map "x" nil) (sorted-map "x" 1.5)
                 (sorted-map "x" []) (sorted-map :x 7)
                 (into (sorted-map) (map (fn [i] [(str i) i]) (range 129)))]]
    (is (false? (admitted-tree value)))
    (is (= (json/write-str value) (probe-string value))))
  (doseq [value [(/ 1 3) (scheme/eval-string "(+ (most-positive-fixnum) 1)")]]
    (is (false? (admitted-tree (sorted-map "value" value)))))
  (let [value (sorted-map "a" 7)]
    (host/ref-put! value :cnt 2)
    (is (false? (admitted-tree value))))
  (let [value (sorted-map "x" 7) ops (host/ref-get value :ops) calls (atom 0)]
    (host/ref-put! value :ops (assoc ops :seq (fn [_] (swap! calls inc) (seq [["custom" false]]))))
    (is (false? (admitted-tree value)))
    (is (zero? @calls))
    (is (= "{\"custom\":false}" (probe-string value)))
    (is (= 1 @calls))))

(deftest armed-comparator-is-never-invoked
  (let [armed (atom false) calls (atom 0)
        value (sorted-map-by (fn [a b] (when @armed (swap! calls inc)
                                        (throw (ex-info "unexpected comparator" {})))
                              (compare b a)) "a" "first" "b" "second")]
    (reset! armed true)
    (is (vector? (admitted-tree value)))
    (is (= "{\"b\":\"second\",\"a\":\"first\"}" (probe-string value)))
    (is (zero? @calls))))

(deftest live-scalar-writer-prefix-edit-reentry-and-throw
  (doseq [mode [:append :clear :reenter :throw :replace-writer :replace-ops]]
    (let [run (fn [probe?]
                (let [out (java.io.StringWriter.) effects (atom []) calls (atom 0)
                      value (sorted-map "a" "first" "b" "second")
                      original (nth @#'json/native-writer-stock 6)
                      replacement (fn [v writer options]
                                    (swap! effects conj [:replacement v (.toString writer)])
                                    (original v writer options))]
                  (try
                    (extend java.lang.String json/JSONWriter
                      {:-write
                       (fn [v writer options]
                         (swap! effects conj [:writer v (.toString writer)])
                         (when (= 1 (swap! calls inc))
                           (case mode
                             :append (.write writer " ")
                             :clear (do (.setLength (.getBuffer writer) 0) (.write writer "edited "))
                             :reenter (.write writer (probe-string (sorted-map "inner" false)))
                             :throw (throw (ex-info "synthetic scalar failure" {}))
                             :replace-writer (extend java.lang.String json/JSONWriter {:-write replacement})
                             :replace-ops (host/ref-put! value :ops
                                            (assoc (host/ref-get value :ops) :seq
                                                   (fn [_] (throw (ex-info "unexpected seq restart" {})))))))
                         (original v writer options))})
                    [(try (if probe? (probe-write value out) (json/write value out))
                          :ok (catch Throwable error (.getMessage error)))
                     (.toString out) @effects]
                    (finally (extend java.lang.String json/JSONWriter {:-write original})))))]
      (is (= (run false) (run true)) (str "scalar callback contract: " mode)))))

(deftest positive-fold-and-disabled-helper-control
  (let [runner (scheme/eval-string
                 "(lambda (run disable?)
                    (let ((base task-stock-sorted-fold)
                          (admit task-stock-sorted-scalar-tree) (calls 0))
                      (dynamic-wind
                        (lambda ()
                          (set! task-stock-sorted-fold
                            (lambda (tree visit acc) (set! calls (+ calls 1))
                              (base tree visit acc)))
                          (when disable? (set! task-stock-sorted-scalar-tree (lambda (m) #f))))
                        (lambda () (run))
                        (lambda () (set! task-stock-sorted-fold base)
                                   (set! task-stock-sorted-scalar-tree admit)))
                      calls))")
        value (sorted-map "a" 7 "b" false "c" "value")
        render #(is (= (json/write-str value) (probe-string value)))]
    (is (pos? (runner render false)))
    (is (zero? (runner render true)))))

(let [result (run-tests 'user)]
  (System/exit (if (and (pos? (:test result)) (zero? (+ (:fail result) (:error result)))) 0 1)))
