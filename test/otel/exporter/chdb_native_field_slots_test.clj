(ns otel.exporter.chdb-native-field-slots-test
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.java.io :as io]
            [jolt.scheme :as scheme]
            [jolt.traversal.experimental :as traversal]
            [otel.exporter.chdb.attribute-projection :as p]
            [otel.exporter.chdb.native-projection :as native]
            [otel.exporter.chdb-typed-compact-vector-test :as plans]
            [otel.exporter.chdb-attribute-projection-test :as fixture]))

(def folder (traversal/native-pmap-folder (traversal/open-traversal)))
(defrecord RecordSpan [attributes])

(defn projectors [mixed?]
  (let [{:keys [capability target vector-projector]} (plans/plans mixed?)]
    [vector-projector (p/trace-vector-projector capability target true folder)]))

(deftest exact-values-statuses-and-location-isolation
  (doseq [mixed? [false true]]
    (let [[ordinary direct] (projectors mixed?)]
      (doseq [attributes [{} nil false
                          {"checkout.complete" false "checkout.count" 9007199254740993 "checkout.name" ""}
                          {"checkout.complete" 1 "checkout.count" 9223372036854775808N "checkout.name" nil}
                          (array-map :checkout.count 7 "checkout.count" 8)
                          (sorted-map "checkout.count" 17 "checkout.name" "sorted")
                          (into {} (map (fn [i] [(str "extra-" i) i]) (range 129)))]]
        (let [span (assoc (@#'fixture/span attributes)
                         :resource {:attributes {"shared.location" "resource"}}
                         :scope {:attributes {"shared.location" "scope"}})]
          (is (= (ordinary span) (direct span)))
          (is (= (ordinary (dissoc span :resource :scope)) (direct (dissoc span :resource :scope)))))))))

(deftest live-and-preinstalled-key-policy-retains-callback-order
  (let [[ordinary direct] (projectors false)
        original @#'p/key-string
        span (@#'fixture/span (array-map "checkout.count" 7 :checkout.count 8 "undeclared" false))
        run (fn [project]
              (let [seen (atom [])]
                (with-redefs [p/key-string (fn [key] (swap! seen conj key) (original key))]
                  [(project span) @seen])))]
    (is (= (run ordinary) (run direct)))
    (let [seen (atom [])]
      (with-redefs [p/key-string (fn [key] (swap! seen conj key) (original key))]
        (let [[_ installed-after-replacement] (projectors false)]
          (is (= (ordinary span) (installed-after-replacement span)))
          (is (= 6 (count @seen))))))))

(deftest late-projector-results-and-reentry-remain-invocation-owned
  (let [[ordinary direct] (projectors false)
        span (@#'fixture/span {"checkout.complete" false "checkout.count" 7 "checkout.name" "text"})]
    (doseq [replacement [(fn [_ _] nil) (fn [_ _] '(17)) (fn [_ _] '(17 29))]]
      (with-redefs [p/projected-value replacement]
        (is (= (ordinary span) (direct span)))))
    (let [original @#'p/projected-value nested (atom nil) active? (volatile! false)]
      (with-redefs [p/projected-value
                    (fn [type values]
                      (when-not @active?
                        (vreset! active? true)
                        (reset! nested (direct (@#'fixture/span {}))))
                      (original type values))]
        (is (= (ordinary span) (direct span)))
        (is (= (ordinary (@#'fixture/span {})) @nested))))))

(deftest bounded-generated-maps-and-fresh-output
  (let [[ordinary direct] (projectors false)
        values [nil false true "" "text" -9223372036854775808 9223372036854775807
                9223372036854775808N 3.5 [] {}]]
    (dotimes [i 128]
      (let [base (array-map "checkout.name" (nth values (mod i (count values)))
                            "checkout.count" (nth values (mod (+ i 1) (count values)))
                            "checkout.complete" (nth values (mod (+ i 2) (count values))))
            attributes (if (zero? (mod i 4)) (assoc base :checkout.count i) base)
            span (@#'fixture/span attributes)
            first-result (direct span) expected (ordinary span)]
        (is (= expected first-result))
        (is (= expected (direct span)))
        (is (not (identical? first-result (direct span))))
        (is (= expected first-result))))
    (let [span (->RecordSpan {"checkout.count" 7})]
      (is (= (ordinary span) (direct span))))))

(deftest changed-core-membership-retains-indexed-fallback-effects
  (let [{:keys [capability target]} (plans/plans false)
        indexed (with-redefs [native/load-trace-field-slots! (constantly nil)]
                  (p/trace-vector-projector capability target true folder))
        direct (p/trace-vector-projector capability target true folder)
        span (@#'fixture/span (array-map "checkout.count" 7 "other" false))
        original clojure.core/contains?
        run (fn [project]
              (let [seen (atom [])]
                (with-redefs [clojure.core/contains?
                              (fn [collection key] (swap! seen conj key) (original collection key))]
                  [(project span) @seen])))]
    (is (= (run indexed) (run direct)))))

(deftest selected-fast-path-and-wrong-folder-negative-control
  (let [{:keys [capability target vector-projector]} (plans/plans false)
        span (@#'fixture/span {"checkout.complete" false "checkout.count" 7 "checkout.name" "text"})]
    (with-redefs [p/indexed-vector-projector (fn [& _] (fn [_] (throw (ex-info "fallback witness" {}))))]
      (let [direct (p/trace-vector-projector capability target true folder)]
        (is (= (vector-projector span) (direct span)))))
    (let [wrong-folder (fn [_ _ initial] initial)
          mutant (p/trace-vector-projector capability target true wrong-folder)]
      (is (not= (vector-projector span) (mutant span))))))

(deftest unsupported-plans-decline-without-classification
  (let [build (fn [locations plan]
                (native/load-trace-field-slots!
                  locations plan #'p/key-string #'clojure.core/contains? @#'p/original-key-string
                  #'p/projected-value @#'p/original-projected-value folder))]
    (is (nil? (build [] [])))
    (is (nil? (build [:span-attributes] (mapv #(vector 0 (str %) :int64) (range 9)))))
    (is (nil? (build [:span-attributes] [[0 "same" :int64] [0 "same" :boolean]])))
    (is (nil? (build [:unknown] [[0 "a" :int64]])))))

(deftest unavailable-continuation-bridge-rejects-at-construction
  (let [source (slurp (io/resource "otel/exporter/chdb/native_trace_field_slots.ss"))
        factory ((scheme/eval-string (str "(lambda (pmap-fold-seq-order) " source ")")) folder)
        probe (scheme/eval-string
                "(lambda (factory locations plan key-var contains-var original-key project-var original-projector)
                   (let ((saved jolt-invoke3))
                     (dynamic-wind
                       (lambda () (set! jolt-invoke3 (lambda (one) one)))
                       (lambda ()
                         (guard (e ((and (message-condition? e)
                                         (string=? (condition-message e) \"Runtime continuation bridge is unavailable\")) #t)
                                   (else (raise e)))
                           (factory locations plan key-var contains-var original-key project-var original-projector)
                           #f))
                       (lambda () (set! jolt-invoke3 saved)))))")]
    (is (true? (probe factory [:span-attributes] [[0 "a" :int64]]
                      #'p/key-string #'clojure.core/contains? @#'p/original-key-string
                      #'p/projected-value @#'p/original-projected-value)))))

(defn -main [& _]
  (let [result (t/run-tests 'otel.exporter.chdb-native-field-slots-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
