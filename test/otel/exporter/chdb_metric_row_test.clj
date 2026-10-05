(ns otel.exporter.chdb-metric-row-test
  (:require [clojure.test :as test :refer [deftest is]]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-test-support :as support]
            [otel.sdk.export :as export]))

(deftest sdk-builds-each-point-directly-with-one-projection
  (let [resource {:attributes {"service.name" "row-test"}}
        scope {:name "scope" :version "1"}
        points [{:value 1 :attributes {"point" 1}}
                {:value 2 :attributes {"point" 2}}]
        histogram {:count 3 :sum 6 :bucket-counts [1 2]
                   :explicit-bounds [5] :attributes {"point" 3}}
        calls (atom []) captured (atom nil)
        project (fn [context]
                  (swap! calls conj context)
                  {"TypedValue" (get-in context [:point :attributes "point"])})
        state (atom (support/exporter-state
                     {:closed-signals #{} :durable? false
                      :typed-metric-projectors
                      {:gauge project :sum project :histogram project}}))
        target (exporter/->ChdbExporter {} false #{:metrics} state)]
    (with-redefs [exporter/metric-rows
                  (fn [& _] (throw (ex-info "SDK must not construct one-point lazy collections" {})))
                  exporter/export-metric-rows!
                  (fn [_ _ rows] (reset! captured rows))]
      (is (true? (export/export-metrics!
                  target resource
                  [{:scope scope
                    :metrics [{:type :gauge :name "g" :data-points points}
                              {:type :sum :name "s" :monotonic? true
                               :temporality :cumulative :data-points [(first points)]}
                              {:type :histogram :name "h" :temporality :cumulative
                               :explicit-bounds [5] :data-points [histogram]}]}]))))
    (is (nil? (exporter/last-error target)))
    (is (= [:gauge :gauge :sum :histogram] (mapv :_type @captured)))
    (is (= [1 2 1 3] (mapv #(get % "TypedValue") @captured)))
    (is (= [1.0 2.0 1.0 nil] (mapv #(get % "Value") @captured)))
    (is (= [nil nil 2 2] (mapv #(get % "AggregationTemporality") @captured)))
    (is (= [nil nil true nil] (mapv #(get % "IsMonotonic") @captured)))
    (is (= [1 2] (get (last @captured) "BucketCounts")))
    (is (= [5] (get (last @captured) "ExplicitBounds")))
    (is (= [{:resource resource :scope scope :point (first points)}
            {:resource resource :scope scope :point (second points)}
            {:resource resource :scope scope :point (first points)}
            {:resource resource :scope scope :point histogram}] @calls))))

(defn -main [& _]
  (let [result (test/run-tests 'otel.exporter.chdb-metric-row-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
