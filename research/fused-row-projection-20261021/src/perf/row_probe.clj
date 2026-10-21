(ns perf.row-probe
  "Research component, not an exporter integration or policy bypass."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.java.io :as io] [clojure.string :as str] [clojure.walk :as walk]
            [perf.public-pipeline :as app] [otel.exporter.chdb :as e]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb-attribute-projection-test :as installed]
            [otel.trace :as trace] [jolt.codec.experimental :as codec]
            [jolt.scheme :as scheme]))

(def context (#'e/load-experimental-span-codec! true true true true true))
(def c (codec/open-codec))
(def row-only-projector
  ;; Reuse the prior row-view experiment as a third arm, so any benefit from
  ;; raw preparation is distinguishable from only removing the final vector.
  (let [source (slurp (io/resource "otel/exporter/chdb.clj"))
        start (str/index-of source "(defn- span-row") end (str/index-of source "(defn- log-row")
        definition (binding [*ns* (find-ns 'otel.exporter.chdb)] (read-string (subs source start end)))
        [parameters body] (last definition) replacements (atom 0)
        body (walk/postwalk
               (fn [form]
                 (if (and (vector? form) (= 24 (count form))
                          (= ['time 'trace-id 'span-id 'parent-id] (subvec form 0 4)))
                   (do (swap! replacements inc) (list* 'row-builder form)) form)) body)
        _ (assert (= 1 @replacements))
        factory (binding [*ns* (find-ns 'otel.exporter.chdb)]
                  (eval (list 'fn ['row-builder] (list 'fn parameters body))))]
    (factory (codec/row-builder c 24))))
(def native-factory
  ((scheme/eval-string (slurp "research/fused-row-projection-20261021/native-row.ss"))
   (codec/row-builder c 24)
   (fn [base suffix] (codec/append-row-fields c base suffix))
   @#'e/event-columns @#'e/link-columns))
(def target (Object.))
(def installation (#'installed/installed (app/approved) target))
(def typed (projection/trace-vector-projector (:descriptor-set installation) target true (:folder context)))
(def bytes-equal?
  (scheme/eval-string "(lambda (a b) (bytevector=? (jolt-array-vec a) (jolt-array-vec b)))"))

(defn encode
  ([mode rows] (encode mode rows (fn [_ f] f)))
  ([mode rows observe]
   (let [convert (observe :attributes (#'e/payload-attrs-converter ((:payload-cache-factory context))))
         nested (observe :nested (:nested context)) typed (observe :typed typed)
         project (case mode
                   :baseline (fn [span] (into (#'e/span-row span nil true nested convert) (typed span)))
                   :row-view (fn [span]
                               (codec/append-row-fields c
                                 (row-only-projector span nil true nested convert) (typed span)))
                   :candidate (native-factory convert nested typed))]
     (with-bindings {#'e/*timestamp-wire* :raw-ticks
                     #'e/*json-backend* :native-guarded-byte-batch
                     #'e/*experimental-attribute-transform* (:attributes context)}
       ((:encode context) "SQL\n" rows project 67108864)))))

(defn equivalent! [rows]
  (is (bytes-equal? (encode :baseline rows) (encode :candidate rows))))

(deftest ordinary-and-fallback-shapes
  (let [span (first (app/fixture 1))
        link {:span-context (trace/span-context {:trace-id "linked" :span-id "peer"})
              :attributes {"n" 7}}
        cases [span
               (assoc span :events [] :links [])
               (assoc span :events nil :links nil)
               (assoc span :events false :links false)
               (assoc span :events (vec (repeat 3 (first (:events span)))))
               (assoc span :links [link link])
               (assoc span :span-context (trace/span-context {:trace-id "record" :span-id "child"}))
               (assoc span :scope nil :resource nil :status nil :parent-span-id false)
               (assoc-in span [:span-context :trace-state] [["a" "1"] ["b" "2"]])
               (assoc-in span [:resource :attributes] {"service.name" false})
               (assoc-in span [:status :description] "quotes\"/\\\nλ😀")
               (assoc span :start-time-unix-nano 0 :end-time-unix-nano 0)
               (assoc span :start-time-unix-nano 9223372036854775807
                           :end-time-unix-nano 9223372036854775807)]]
    (doseq [[ordinal value] (map-indexed vector cases)]
      (testing (str "supported shape " ordinal) (equivalent! [value])))
    ;; The explicit nested codec rejects lists; keep that rejection rather
    ;; than interpreting two failures as successful supported-shape coverage.
    (doseq [mode [:baseline :candidate]]
      (is (= "ffc-json: unsupported value"
             (try (encode mode [(assoc span :events (apply list (:events span)))]) nil
                  (catch Exception error (ex-message error))))))
    (is (not (bytes-equal? (encode :candidate [span])
               (encode :candidate [(assoc-in span [:events 0 :name] "changed")]))))))

(deftest callback-order-and-exact-errors
  (let [rows (app/fixture 2)
        capture (fn [mode fail-at]
                  (let [seen (atom []) counts (atom {}) failure (ex-info "expected" {:control true})
                        result (try
                                 (encode mode rows
                                   (fn [kind f]
                                     (fn [value]
                                       (let [ordinal (get (swap! counts update kind (fnil inc 0)) kind)]
                                         (swap! seen conj [kind ordinal])
                                         (when (= [kind ordinal] fail-at) (throw failure))
                                         (f value)))))
                                 (catch Exception error error))]
                    {:seen @seen :result result :failure failure}))]
    (doseq [fail-at [nil [:attributes 1] [:attributes 2] [:attributes 3]
                    [:nested 1] [:nested 2] [:typed 1] [:attributes 4]]]
      (let [a (capture :baseline fail-at) b (capture :candidate fail-at)]
        (is (= (:seen a) (:seen b)))
        (if fail-at
          (do (is (identical? (:failure a) (:result a)))
              (is (identical? (:failure b) (:result b))))
          (is (bytes-equal? (:result a) (:result b))))))))

(deftest late-helper-and-policy-replacement
  (let [rows (app/fixture 2) event-columns @#'e/event-columns link-columns @#'e/link-columns
        timestamp @#'e/timestamp value-string @#'e/value-string]
    (testing "custom column helpers are used, not bypassed by native shortcuts"
      (with-redefs [e/event-columns (fn [events compact? convert]
                                     (assoc (event-columns events compact? convert) 1 ["custom-name"]))
                    e/link-columns (fn [links compact? convert]
                                     (assoc (link-columns links compact? convert) 0 ["custom-trace"]))]
        (equivalent! rows)))
    (testing "live timestamp policy remains in both ordinary/event boundaries"
      (with-redefs [e/timestamp (fn [n] (inc (timestamp n)))] (equivalent! rows)))
    (testing "changed attribute policy uses the SDK/exporter fallback"
      (with-redefs [e/value-string (fn [v] (str "changed:" (value-string v)))]
        (equivalent! rows)))
    (testing "mid-conversion policy replacement remains observable"
      (let [run (fn [mode]
                  (with-redefs [e/value-string value-string]
                    (let [changed? (atom false)]
                      (encode mode rows
                        (fn [kind f]
                          (if (= :attributes kind)
                            (fn [value]
                              (when (compare-and-set! changed? false true)
                                (alter-var-root #'e/value-string
                                  (constantly (fn [v] (str "mid:" (value-string v))))))
                              (f value)) f))))))]
        (is (bytes-equal? (run :baseline) (run :candidate)))))
    (testing "nested callbacks can replace column helpers before their call boundary"
      (let [run (fn [mode]
                  (with-redefs [e/event-columns event-columns e/link-columns link-columns]
                    (encode mode rows
                      (fn [kind f]
                        (if (= :nested kind)
                          (fn [value]
                            (alter-var-root #'e/event-columns
                              (constantly (fn [events compact? convert]
                                            (assoc (event-columns events compact? convert) 1 ["late-event"]))))
                            (alter-var-root #'e/link-columns
                              (constantly (fn [links compact? convert]
                                            (assoc (link-columns links compact? convert) 0 ["late-link"]))))
                            (f value)) f)))))]
        (is (bytes-equal? (run :baseline) (run :candidate)))))))

(deftest timestamp-rejections-remain-rejections
  (let [span (first (app/fixture 1))]
    (doseq [n [-1 nil 9223372036854775808]]
      (doseq [path [[:start-time-unix-nano] [:events 0 :timestamp-unix-nano]]]
        (let [rows [(assoc-in span path n)]
              errors (mapv (fn [mode]
                             (try (encode mode rows) nil (catch Exception error error)))
                           [:baseline :candidate])]
          (is (every? some? errors))
          (is (= (mapv ex-message errors)
                 ["Telemetry timestamp exceeds DateTime64 nanosecond domain"
                  "Telemetry timestamp exceeds DateTime64 nanosecond domain"]))
          (is (apply = (map ex-data errors))))))))

(defn -main [& _]
  (let [result (run-tests 'perf.row-probe)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
