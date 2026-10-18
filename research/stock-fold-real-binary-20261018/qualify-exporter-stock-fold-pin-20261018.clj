(println :exporter-stock-fold-pin :entered)
(require '[clojure.test :as test] '[jolt.scheme :as scheme]
         '[otel.exporter.chdb-sdk-scalar-map-test]
         '[otel.exporter.chdb-sdk-sorted-scalar-map-test]
         '[otel.exporter.chdb-native-attributes-test]
         '[otel.exporter.chdb-native-declared-attributes-test]
         '[otel.exporter.chdb-sorted-event-encoding-test]
         '[otel.exporter.chdb-dependency-test :as dependency])
(let [mode (first *command-line-args*)
      _ (assert (contains? #{"present" "absent"} mode))
      actual (scheme/eval-string "(top-level-bound? 'stock-sorted-map?)")]
  (assert (= (= "present" mode) actual))
  (mapv (fn [[namespace expected-tests]]
          (let [r (test/run-tests namespace)]
            (assert (= expected-tests (:test r)))
            (assert (zero? (+ (:fail r) (:error r))))))
        [['otel.exporter.chdb-sdk-scalar-map-test 4]
         ['otel.exporter.chdb-sdk-sorted-scalar-map-test 5]
         ['otel.exporter.chdb-native-attributes-test 21]
         ['otel.exporter.chdb-native-declared-attributes-test 24]
         ['otel.exporter.chdb-sorted-event-encoding-test 2]]))
(let [observed (atom 0)]
  (dependency/run (fn [label expected actual]
                    (swap! observed inc)
                    (assert (= expected actual) (str "dependency control: " label))))
  (assert (= 58 @observed))
  (prn {:gate :exporter-stock-fold-pin :complete true :passed true
        :source-overlay false :dependency-checks @observed :tests 56}))
(System/exit 0)
