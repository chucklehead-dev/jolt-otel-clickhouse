(ns otel.exporter.chdb-explicit-codec-row-adapter-test
  (:require [clojure.test :refer [deftest is]]
            [otel.exporter.chdb :as e]))

(def span {:name "codec-adapter" :start-time-unix-nano 1 :end-time-unix-nano 2
           :events [] :links [] :resource {:attributes {}} :attributes {}})

(deftest default-retains-late-root-lookup
  (let [calls (atom [])
        later (fn [_] (swap! calls conj :later) "later")
        first-writer (fn [_]
                       (swap! calls conj :first)
                       (alter-var-root #'e/nested-json-string (constantly later))
                       "first")]
    (with-redefs-fn {#'e/nested-json-string first-writer}
      (fn []
        (let [row (#'e/span-row span nil true)]
          (is (= ["first" "later"] [(nth row 22) (nth row 23)]))
          (is (= [:first :later] @calls)))))))

(deftest explicit-writer-is-per-call-not-global
  (let [seen (atom [])
        write (fn [x] (swap! seen conj x) "[]")]
    (with-redefs-fn {#'e/nested-json-string (fn [_] (throw (ex-info "default called" {})))}
      (fn []
        (let [row (#'e/span-row span nil true write)]
          (is (= ["[]" "[]"] [(nth row 22) (nth row 23)]))
          (is (= [(:events span) (:links span)] @seen)))))
    (is (= 24 (count (#'e/span-row span nil true))))))

(deftest explicit-failure-stops-before-links-and-typed-projection
  (let [calls (atom 0) typed (atom 0) marker (ex-info "synthetic stop" {})]
    (is (identical? marker
                    (try
                      (#'e/span-row span (fn [_] (swap! typed inc) {}) true
                                   (fn [_] (swap! calls inc) (throw marker)))
                      nil (catch Throwable e e))))
    (is (= 1 @calls))
    (is (= 0 @typed))))
