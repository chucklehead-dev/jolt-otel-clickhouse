(ns otel.exporter.chdb-portable-projection-filter-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [otel.exporter.chdb.attribute-projection :as projection]))

(defn- collect [attributes wanted]
  (@#'projection/values-by-key attributes wanted))

(deftest retain-only-wanted-values-but-normalize-every-entry
  (let [entries (vec (concat [["count" 7] [:count 7]]
                             (map (fn [n] [(str "noise-" n) n]) (range 40))
                             [["count " "near miss"] ["Count" "near miss"]]))
        seen (atom []) original @#'projection/key-string
        result (with-redefs-fn
                 {#'projection/key-string
                  (fn [key] (swap! seen conj key) (original key))}
                 #(collect entries #{"count"}))
        ;; Known-old algorithm: projected rows would match, but this resource
        ;; oracle must reject retaining all the unrelated intermediate groups.
        unfiltered (reduce (fn [out [k v]]
                             (update out (original k) (fnil conj []) v)) {} entries)]
    (is (= (mapv first entries) @seen))
    (is (= {"count" [7 7]} result))
    (is (> (count unfiltered) (count result)))
    (is (= {} (collect entries #{})))
    (is (= {} (collect nil #{"count"})))))

(deftest non-string-replacement-normalizers-retain-ordinary-accumulation
  (with-redefs-fn {#'projection/key-string (constantly :custom)}
    #(is (= {:custom [1 2]} (collect [["a" 1] ["b" 2]] #{"count"})))))

(deftest legacy-span-projector-keeps-declared-values
  (with-redefs [projection/confirmed-span-fields
                (fn [& _] [{:location :span-attributes :key "count" :type :int64
                            :physical {:value-column "v" :status-column "s"}}])]
    (is (= {"v" 7 "s" 3}
           ((projection/span-projector :capability :target) {"count" 7 "ignored" 99})))))

(defn- field [location key type]
  {:location location :key key :type type
   :physical {:value-column (str (name location) "-" key)
              :status-column (str (name location) "-" key "-status")}})

(deftest multiple-fields-share-one-location-extraction
  (let [fields [(field :span-attributes "a" :int64)
                (field :span-attributes "b" :int64)
                (field :resource-attributes "c" :int64)]
        seen (atom []) original @#'projection/attributes-at]
    (with-redefs-fn
      {#'projection/confirmed-span-fields (fn [& _] fields)
       #'projection/attributes-at
       (fn [record location] (swap! seen conj location) (original record location))}
      #(do ((projection/trace-projector :capability :target)
             {:attributes {"a" 1 "b" 2} :resource {:attributes {"c" 3}}})
           (is (= [:span-attributes :resource-attributes] @seen))))))

(deftest trace-map-and-portable-vector-retain-location-and-status-semantics
  (let [fields [(field :resource-attributes "shared" :string)
                (field :scope-attributes "shared" :boolean)
                (field :span-attributes "shared" :int64)]
        noise (mapv (fn [n] [(str "noise-" n) :invalid-if-selected]) (range 40))
        record {:resource {:attributes (conj noise ["shared" ""])}
                :scope {:attributes (conj noise [:shared false])}
                :attributes (into [["shared" 7]] (conj noise [:shared 7]))}]
    (with-redefs [projection/confirmed-span-fields (fn [& _] fields)]
      (let [map-project (projection/trace-projector :capability :target)
            vector-project (projection/trace-vector-projector :capability :target)
            result (map-project record)]
        (is (= ["" 2 false 3 0 4] (vector-project record)))
        (is (= ["" 2 false 3 0 4]
               (vec (mapcat (fn [{:keys [physical]}]
                              [(get result (:value-column physical))
                               (get result (:status-column physical))]) fields))))
        (is (= 6 (count result)))
        (is (= ["" 1 false 1 0 1] (vector-project {})))))))

(deftest log-and-metric-projectors-filter-at-each-location
  (let [log-fields [(field :log-attributes "count" :int64)]
        metric-fields [(field :resource-attributes "count" :int64)
                       (field :scope-attributes "count" :int64)
                       (field :metric-attributes "count" :int64)]
        attrs (fn [n] {"count" n "ignored" false "count " :invalid})]
    (with-redefs [projection/confirmed-log-fields (fn [& _] log-fields)
                  projection/confirmed-gauge-fields (fn [& _] metric-fields)
                  projection/confirmed-sum-fields (fn [& _] metric-fields)
                  projection/confirmed-histogram-fields (fn [& _] metric-fields)]
      (is (= {"log-attributes-count" 11 "log-attributes-count-status" 3}
             ((projection/log-projector :capability :target) {:attributes (attrs 11)})))
      (doseq [factory [projection/gauge-projector projection/sum-projector
                       projection/histogram-projector]]
        (let [project (factory :capability :target)
              result (project {:resource {:attributes (attrs 1)}
                               :scope {:attributes (attrs 2)}
                               :point {:attributes (attrs 3)}})]
          (is (= [1 2 3] (mapv #(get result (get-in % [:physical :value-column])) metric-fields)))
          (is (= [3 3 3] (mapv #(get result (get-in % [:physical :status-column])) metric-fields)))
          (is (= 6 (count result))))))))

(defn run-checks! []
  (let [r (run-tests 'otel.exporter.chdb-portable-projection-filter-test)]
    (when (pos? (+ (:fail r) (:error r)))
      (throw (ex-info "Portable typed projection filtering failed" r)))
    r))

(defn -main [& _] (run-checks!))
