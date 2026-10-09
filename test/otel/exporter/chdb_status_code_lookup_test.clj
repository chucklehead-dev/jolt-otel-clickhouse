(ns otel.exporter.chdb-status-code-lookup-test
  (:require [clojure.test :refer [deftest is]]
            [otel.exporter.chdb.attribute-projection :as p]
            [otel.exporter.chdb.attribute-registry :as registry]
            [otel.exporter.chdb.native-attributes :as native]))

(deftest public-codebook-keeps-its-object-values-and-sorted-order
  (let [codes registry/status-codes]
    (is (= [[:absent 1] [:historical-untyped 0] [:invalid 4]
            [:present-empty 2] [:valid 3]] (mapv vec codes)))
    (is (= [false 3] (@#'p/projected-value :boolean [false])))
    (is (identical? codes registry/status-codes))))

(deftest replacement-codebooks-are-used-without-a-stale-stock-cache
  (doseq [codes [{:absent 11 :present-empty 12 :valid 13 :invalid 14}
                (sorted-map :absent 21 :present-empty 22 :valid 23 :invalid 24)]]
    (with-redefs [registry/status-codes codes]
      (doseq [[type values expected]
              [[:string [] ["" (:absent codes)]]
               [:boolean [] [false (:absent codes)]]
               [:int64 [] [0 (:absent codes)]]
               [:string [""] ["" (:present-empty codes)]]
               [:string ["value"] ["value" (:valid codes)]]
               [:boolean [false] [false (:valid codes)]]
               [:boolean [true] [true (:valid codes)]]
               [:int64 [-9223372036854775808] [-9223372036854775808 (:valid codes)]]
               [:int64 [9223372036854775807] [9223372036854775807 (:valid codes)]]
               [:int64 [9223372036854775808N] [0 (:invalid codes)]]
               [:boolean [nil] [false (:invalid codes)]]
               [:string [false] ["" (:invalid codes)]]
               [:int64 [1 1] [0 (:invalid codes)]]]]
        (is (= expected (@#'p/projected-value type values))))))
  (is (= [false 3] (@#'p/projected-value :boolean [false])))
  (with-redefs [registry/status-codes nil]
    (is (= [false nil] (@#'p/projected-value :boolean [false])))))

(deftest native-output-sees-a-codebook-change-between-fields
  (let [emit (native/load-positional-output! #'p/projected-value)
        original @#'p/projected-value
        codes registry/status-codes
        calls (atom 0)]
    (with-redefs [registry/status-codes codes
                  p/projected-value
                  (fn [type values]
                    (let [result (original type values)]
                      (when (= 1 (swap! calls inc))
                        (alter-var-root #'registry/status-codes
                                        (constantly {:valid 13})))
                      result))]
      (is (= [7 3 false 13]
             (emit [[0 "count" :int64] [0 "flag" :boolean]]
                   [{"count" [7] "flag" [false]}]))))
    (is (= 2 @calls))
    (is (identical? codes registry/status-codes))))
