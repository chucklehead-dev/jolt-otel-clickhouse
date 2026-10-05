(ns otel.exporter.chdb-metric-row-test
  (:require [clojure.test :as test :refer [deftest is]]
            [clojure.data.json :as json]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-test-support :as support]
            [otel.sdk.export :as export]))

;; Frozen construction body from exporter 5fbea8b. Keep this reference separate
;; from the optimized builder so equality cannot pass by calling it twice.
(defn legacy-metric-row [resource scope metric point projectors]
  (let [projector (get projectors (:type metric))
        context {:resource resource :scope scope :point point}]
    (merge
     {"Exemplars.FilteredAttributes" [] "Exemplars.TimeUnix" []
      "Exemplars.Value" [] "Exemplars.SpanId" [] "Exemplars.TraceId" []}
     {"ResourceAttributes" (#'exporter/attrs (:attributes resource))
      "ResourceSchemaUrl" (or (:schema-url resource) "")
      "ScopeName" (or (:name scope) "") "ScopeVersion" (or (:version scope) "")
      "ScopeAttributes" (#'exporter/attrs (:attributes scope))
      "ScopeDroppedAttrCount" 0 "ScopeSchemaUrl" (or (:schema-url scope) "")
      "ServiceName" (#'exporter/service-name resource "")
      "MetricName" (:name metric) "MetricDescription" (or (:description metric) "")
      "MetricUnit" (or (:unit metric) "")
      "Attributes" (#'exporter/attrs (:attributes point))
      "StartTimeUnix" (#'exporter/metric-timestamp (or (:start-time-unix-nano point) 0))
      "TimeUnix" (#'exporter/metric-timestamp (or (:time-unix-nano point) 0))
      "Flags" 0}
     (case (:type metric)
       :gauge (merge {"Value" (double (:value point))} (if projector (projector context) {}))
       :sum (merge {"Value" (double (:value point))
                    "AggregationTemporality" (#'exporter/temporality-code (:temporality metric))
                    "IsMonotonic" (boolean (:monotonic? metric))}
                   (if projector (projector context) {}))
       :histogram (merge {"Count" (:count point) "Sum" (double (:sum point))
                          "BucketCounts" (:bucket-counts point) "ExplicitBounds" (:explicit-bounds metric)
                          "Min" (double (or (:min point) 0.0)) "Max" (double (or (:max point) 0.0))
                          "AggregationTemporality" (#'exporter/temporality-code (:temporality metric))}
                         (if projector (projector context) {}))))))

(deftest direct-construction-retains-values-and-exact-json-wire
  (doseq [kind [:gauge :sum :histogram]
          attributes [{} {:enabled false :nested [nil "é😀"]}
                      (array-map :key "first" "key" "last")]
          projected? [false true]]
    (let [resource {:attributes {:service.name "metric-parity" :enabled false}}
          scope {:name "scope" :attributes attributes}
          metric {:type kind :name "metric" :temporality :delta
                  :monotonic? true :explicit-bounds [0 5]}
          point {:value 7 :sum 9 :count 3 :bucket-counts [1 1 1]
                 :time-unix-nano 1700000000000000000 :attributes attributes}
          projectors (when projected?
                       {kind (fn [_] {"TypedValue" 9223372036854775807
                                      "Value" 42 "ServiceName" "projector-last"})})
          expected (legacy-metric-row resource scope metric point projectors)
          actual (#'exporter/metric-row resource scope metric point projectors)]
      (is (= expected actual))
      (is (= (json/write-str expected) (json/write-str actual))))))

(deftest conversions-and-projector-retain-observable-order
  (let [resource {:attributes {:service.name "order"}}
        scope {:attributes {:scope "scope"}}
        metric {:type :gauge :name "gauge"}
        point {:value 7 :attributes {:point "point"}}
        events (atom [])
        original-attrs @#'exporter/attrs
        original-time @#'exporter/metric-timestamp
        projectors {:gauge (fn [_] (swap! events conj :projector) {})}
        observe (fn [builder]
                  (reset! events [])
                  (with-redefs [exporter/attrs
                                (fn [value] (swap! events conj [:attrs value]) (original-attrs value))
                                exporter/metric-timestamp
                                (fn [value] (swap! events conj [:time value]) (original-time value))]
                    (builder resource scope metric point projectors))
                  @events)]
    (let [expected (observe legacy-metric-row)
          actual (observe @#'exporter/metric-row)]
      (is (= expected actual))
      (is (= :projector (last actual)))
      (is (= 1 (count (filter #{:projector} actual)))))))

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
                  exporter/export-metric-groups!
                  (fn [_ _ groups] (reset! captured groups))]
      (is (true? (export/export-metrics!
                  target resource
                  [{:scope scope
                    :metrics [{:type :gauge :name "g" :data-points points}
                              {:type :sum :name "s" :monotonic? true
                               :temporality :cumulative :data-points [(first points)]}
                              {:type :histogram :name "h" :temporality :cumulative
                               :explicit-bounds [5] :data-points [histogram]}]}]))))
    (is (nil? (exporter/last-error target)))
    (is (= #{:gauge :sum :histogram} (set (keys @captured))))
    (let [rows (vec (mapcat @captured [:gauge :sum :histogram]))]
      (is (every? #(not (contains? % :_type)) rows))
      (is (= [1 2 1 3] (mapv #(get % "TypedValue") rows)))
      (is (= [1.0 2.0 1.0 nil] (mapv #(get % "Value") rows)))
      (is (= [nil nil 2 2] (mapv #(get % "AggregationTemporality") rows)))
      (is (= [nil nil true nil] (mapv #(get % "IsMonotonic") rows)))
      (is (= [1 2] (get (last rows) "BucketCounts")))
      (is (= [5] (get (last rows) "ExplicitBounds"))))
    (is (= [{:resource resource :scope scope :point (first points)}
            {:resource resource :scope scope :point (second points)}
            {:resource resource :scope scope :point (first points)}
            {:resource resource :scope scope :point histogram}] @calls))))

(defn legacy-row-groups [resource collected projectors]
  (let [rows (vec (for [{:keys [scope metrics]} collected
                       metric metrics point (:data-points metric)]
                   (assoc (#'exporter/metric-row resource scope metric point projectors)
                          :_type (:type metric))))]
    (into {} (for [kind [:gauge :sum :histogram]
                   :let [selected (vec (map #(dissoc % :_type)
                                            (filter #(= kind (:_type %)) rows)))]
                   :when (seq selected)]
               [kind selected]))))

(deftest routed-groups-retain-legacy-wire-and-projector-order
  (let [resource {:attributes {:service.name "routing"}}
        point (fn [id] {:value id :sum id :count id :bucket-counts [id]
                       :attributes {:id id :escaped "é/\n"}})
        metric (fn [kind ids] {:type kind :name (name kind) :explicit-bounds [5]
                              :temporality :delta :data-points (mapv point ids)})
        collected [{:scope {:name "first"}
                    :metrics [(metric :sum [1 2]) (metric :gauge [3])]}
                   {:scope {:name "second"}
                    :metrics [(metric :histogram [4]) (metric :sum [5])
                              (metric :gauge [6 7])]}]]
    (doseq [projected? [false true]]
      (let [events (atom [])
            projectors (when projected?
                         (zipmap [:gauge :sum :histogram]
                                 (repeat (fn [context]
                                           (swap! events conj context)
                                           {"TypedValue" (get-in context [:point :value])}))))
            expected (legacy-row-groups resource collected projectors)
            expected-events @events
            _ (reset! events [])
            actual (#'exporter/metric-row-groups resource collected projectors)]
        (is (= expected actual))
        (doseq [kind [:gauge :sum :histogram]]
          (is (= (mapv json/write-str (get expected kind))
                 (mapv json/write-str (get actual kind)))))
        (is (= expected-events @events))
        (when projected?
          (is (= (range 1 8) (map #(get-in % [:point :value]) @events))))))
    (is (= {} (#'exporter/metric-row-groups resource nil nil)))
    (is (= {} (#'exporter/metric-row-groups resource
                [{:scope {} :metrics [(metric :gauge [])]}] nil)))))

(defn -main [& _]
  (let [result (test/run-tests 'otel.exporter.chdb-metric-row-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
