(ns otel.exporter.chdb-preloaded-scalar-test
  ;; Deliberately do not require the exporter here: helper replacement must
  ;; precede its first load. Run this namespace in its own process.
  (:require [clojure.test :as test :refer [deftest is]]
            [otel.any-value :as any]))

(deftest preloaded-helper-is-not-a-default-identity-witness
  (let [stock any/try-scalar-string calls (atom [])]
    (with-redefs [any/try-scalar-string
                  (fn [value]
                    (swap! calls conj value)
                    (let [result (stock value)]
                      (alter-var-root
                        (ns-resolve 'otel.exporter.chdb 'key-string)
                        (constantly (fn [key] (str "changed-" key))))
                      result))]
      (require 'otel.exporter.chdb)
      (let [attrs (ns-resolve 'otel.exporter.chdb 'attrs)
            key-var (ns-resolve 'otel.exporter.chdb 'key-string)
            original-key @key-var]
        (try
          (is (= {"a" "first" "changed-b" "second"}
                 (attrs (array-map "a" "first" "b" "second"))))
          (is (= ["first" "second"] @calls)
              "no speculative invocation or fallback retry")
          (finally (alter-var-root key-var (constantly original-key))))))))

(defn -main [& _]
  (let [result (test/run-tests 'otel.exporter.chdb-preloaded-scalar-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
