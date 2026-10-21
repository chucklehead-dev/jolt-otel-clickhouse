;; Run the original suite, then the same functional/budget/mechanism oracles
;; against inlined kernels. The old depth mutant targets the extracted helper
;; and is intentionally not reused for this different source shape.
(load-file "research/template-key-prefixes-20261021/check.clj")
(load-file "research/template-key-prefixes-20261021/inline.clj")
(require '[clojure.test :as t])
(with-redefs [prefix-native inline-native
              box-view (view-factory (nth inline-native 3))
              candidate-source inline-source disabled-source inline-disabled-source]
  (binding [t/*report-counters* (ref t/*initial-report-counters*)]
    (t/test-vars [#'exact-bytes-fresh-values-and-permuted-ordinals
                  #'budgets-depth-chunks-and-ownership
                  #'cached-hits-enforce-current-depth-and-values
                  #'mechanism-cache-hits-and-monotonic-budgets])
    (prn :inline-controls @t/*report-counters*)
    (when (pos? (+ (:fail @t/*report-counters*) (:error @t/*report-counters*)))
      (System/exit 1))))
