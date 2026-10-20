(ns jolt.traversal.leaf-block-test
  "Mechanism gate for the checkout-local kernel, not an older published pin."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.scheme :as scheme]))

(deftest one-resolution-per-leaf-not-per-element
  ;; Instrument a lexical copy only, never a runtime-global procedure. Keep
  ;; this gate separate from the compatibility tests for older published pins.
  (let [source (slurp (io/resource "jolt/traversal/kernel.ss"))
        counted (str/replace source "(pv-leaf-for v i)" "(counted-leaf v i)")
        make-fold (scheme/eval-string
                    (str "(let ((calls 0))"
                         " (define (counted-leaf v i) (set! calls (+ calls 1)) (pv-leaf-for v i))"
                         counted
                         " (lambda (v) (set! calls 0)"
                         " (let ((sum (ffc-vector-fold v (lambda (x a) (+ x a)) 0)))"
                         " (jolt-vector sum calls))))"))]
    (is (not= source counted))
    (is (= [558096 34] (make-fold (vec (range 1057)))))
    (is (= [0 0] (make-fold [])))
    (is (= [276 1] (make-fold (vec (range 24)))))))
