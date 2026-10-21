(load-file "research/direct-scalar-map-owner-20261021/context.clj")
(require '[clojure.test :refer [deftest is run-tests]])

(deftest actual-exporter-fallback-and-bytes-remain-equivalent
  (let [contexts {:baseline (owner-context false) :direct (owner-context true)}
        encode (rich-encoder contexts) ordinary (app/fixture 8)]
    (doseq [rows [ordinary
                 (assoc-in ordinary [0 :attributes "flag"] true)
                 (assoc-in ordinary [0 :attributes "nested"] [1 true nil])
                 (assoc-in ordinary [0 :attributes "floating"] 1.25)
                 (assoc-in ordinary [0 :attributes] {"small" 1})
                 (update-in ordinary [0 :attributes] assoc "Aa" 1 "BB" 2)
                 (assoc-in ordinary [0 :attributes "large"]
                           (apply str (repeat (inc (any/default-string-value-max-chars)) "x")))]]
      (is (bytes-equal? (encode :baseline rows) (encode :direct rows))))
    (doseq [policy [#'exporter/key-string #'exporter/value-string]]
      (with-redefs-fn {policy (fn [v] (str "custom:" v))}
        #(is (bytes-equal? (encode :baseline ordinary) (encode :direct ordinary)))))
    (let [original (encode :baseline ordinary) calls (atom 0)
          helper @#'any/original-try-scalar-string-map]
      (with-redefs-fn {#'any/original-try-scalar-string-map
                      (fn [m] (swap! calls inc) (helper m))}
        #(do (is (bytes-equal? original (encode :direct ordinary)))
             (is (= 0 @calls) "admitted ordinary maps must not run the copying helper"))))))

(let [r (run-tests 'user)]
  (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))
