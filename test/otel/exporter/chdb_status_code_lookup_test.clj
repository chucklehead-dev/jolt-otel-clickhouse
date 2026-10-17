(ns otel.exporter.chdb-status-code-lookup-test
  (:require [clojure.test :refer [deftest is]]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.attribute-registry :as registry]))

(deftest public-codebook-is-unchanged
  (let [codes registry/status-codes]
    (is (= [[:absent 1] [:historical-untyped 0] [:invalid 4]
            [:present-empty 2] [:valid 3]] (mapv vec codes)))
    (is (= codes (registry/status-code-lookup)))
    (is (identical? codes registry/status-codes))
    (is (not (identical? codes (registry/status-code-lookup))))))

(deftest replacement-codebooks-remain-live
  (doseq [codes [{:absent 11 :present-empty 12 :valid 13 :invalid 14}
                (sorted-map :absent 21 :present-empty 22 :valid 23 :invalid 24)
                nil]]
    (with-redefs [registry/status-codes codes]
      (is (identical? codes (registry/status-code-lookup)))
      (doseq [[type values status default]
              [[:string [] :absent ""] [:boolean [] :absent false]
               [:int64 [] :absent 0] [:string [""] :present-empty ""]
               [:string ["v"] :valid "v"] [:boolean [false] :valid false]
               [:int64 [-9223372036854775808] :valid -9223372036854775808]
               [:int64 [9223372036854775807] :valid 9223372036854775807]
               [:int64 [9223372036854775808N] :invalid 0]
               [:boolean [nil] :invalid false] [:string [false] :invalid ""]
               [:int64 [1 1] :invalid 0]]]
        (is (= [default (get codes status)]
               (#'projection/projected-value type values))))))
  (is (= [false 3] (#'projection/projected-value :boolean [false]))))

(deftest replacements-installed-before-projector-reload-keep-observable-lookups
  (let [comparisons (atom 0)
        codes (into (sorted-map-by (fn [a b] (swap! comparisons inc) (compare a b)))
                    registry/status-codes)]
    (with-redefs [registry/status-codes codes]
      (require 'otel.exporter.chdb.attribute-projection :reload)
      (reset! comparisons 0)
      (is (= [false 3] (#'projection/projected-value :boolean [false])))
      (is (pos? @comparisons))
      (is (identical? codes (registry/status-code-lookup)))))
  (is (= [false 3] (#'projection/projected-value :boolean [false]))))
