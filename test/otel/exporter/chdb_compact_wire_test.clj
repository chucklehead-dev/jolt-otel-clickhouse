(ns otel.exporter.chdb-compact-wire-test
  (:require [clojure.test :as test :refer [deftest is]]
            [clojure.data.json :as json]
            [jdbc.core :as jdbc]
            [jdbc.chdb :as native-driver]
            [otel.exporter.chdb :as exporter]
            [otel.sdk.export :as sdk-export]
            [otel.exporter.chdb.compact-format :as compact-format]
            [otel.exporter.chdb-wire-test-config :as wire-config]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb-benchmark :as benchmark]))

(defn error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn confirmed-plan
  ([connection columns] (confirmed-plan connection "otel_logs" columns))
  ([connection table columns]
  (with-redefs [jdbc/fetch (fn [& _] (mapv #(hash-map :name % :type "String") columns))]
    (compact-format/confirm! connection table columns
                            (zipmap columns (repeat "String"))))))

(deftest empty-span-columns-avoid-transient-folds
  (let [calls (atom 0) original mapv]
    (with-redefs [clojure.core/mapv
                  (fn [& args] (swap! calls inc) (apply original args))]
      (#'exporter/event-columns [] true)
      (#'exporter/link-columns [] true))
    (is (zero? @calls))))

(deftest empty-span-columns-retain-layout-and-json-calls
  (doseq [compact? [false true]]
    (let [span (#'benchmark/span "empty-vectors" 1)
          calls (atom []) original json/write-str
          row (with-redefs [json/write-str
                            (fn [value & options]
                              (swap! calls conj value)
                              (apply original value options))]
                (#'exporter/span-row span nil compact?))]
      (is (= [[] []] @calls))
      (is (= (if compact? [[] [] []]
               {"Events.Timestamp" [] "Events.Name" [] "Events.Attributes" []})
             (#'exporter/event-columns [] compact?)))
      (is (= (if compact? [[] [] [] []]
               {"Links.TraceId" [] "Links.SpanId" []
                "Links.TraceState" [] "Links.Attributes" []})
             (#'exporter/link-columns [] compact?)))
      (is (= ["[]" "[]"]
             (if compact? (subvec row 22 24)
               ((juxt #(get % "EventsJSON") #(get % "LinksJSON")) row))))))
  (doseq [compact? [false true]]
    (let [effects (atom [])
          events (lazy-seq (swap! effects conj :events) (list {:timestamp-unix-nano 7 :name "event"}))
          links (lazy-seq (swap! effects conj :links) (list {:span-context {:trace-id "trace" :span-id "span"}}))]
      (is (= (#'exporter/event-columns [{:timestamp-unix-nano 7 :name "event"}] compact?)
             (#'exporter/event-columns events compact?)))
      (is (= (#'exporter/link-columns [{:span-context {:trace-id "trace" :span-id "span"}}] compact?)
             (#'exporter/link-columns links compact?)))
      (is (= [:events :links] @effects)))))

(deftest direct-metric-layout-retains-conversion-and-wire-binding
  (let [resource {:attributes {:service.name "service-left/é😀" :nested [1 true]}}
        scope {:name "scope-right" :attributes {:flag true}}]
    (doseq [kind [:gauge :sum :histogram]
            value [0 1 123 9007199254740993]
            temporality [:delta :cumulative nil]]
      (let [metric {:type kind :name "metric" :unit "{item}" :temporality temporality
                    :monotonic? true :explicit-bounds [1.0 10.0]}
            point {:value value :count value :sum value :bucket-counts [1 2 3]
                   :min 1 :max 10 :attributes {:route "/x" :count value}}
            columns (get schema/clickstack-metric-insert-columns kind)
            row (#'exporter/metric-row resource scope metric point nil)
            expected (mapv row columns)
            actual (#'exporter/metric-row resource scope metric point nil true)]
        (is (= expected actual))
        (is (= (json/write-str expected) (json/write-str actual)))))))

(deftest direct-metric-selection-is-closed
  (let [snapshot {:durable? true :insert-format :json-compact-each-row}]
    (is (#'exporter/direct-metric-layout? snapshot))
    (doseq [changed [(assoc snapshot :durable? false)
                     (assoc snapshot :insert-format :json-each-row)
                     (assoc snapshot :typed-metric-projectors {:gauge identity})
                     (assoc snapshot :typed-metric-columns {:gauge ["typed"]})]]
      (is (not (#'exporter/direct-metric-layout? changed))))
    (with-redefs [schema/clickstack-metric-insert-columns
                  (update schema/clickstack-metric-insert-columns :gauge
                          #(assoc % 2 (nth % 7) 7 (nth % 2)))]
      (is (not (#'exporter/direct-metric-layout? snapshot))))))

(deftest sdk-selects-direct-metrics-only-for-the-closed-durable-layout
  (doseq [direct? [true false]]
    (let [state (atom {:durable? direct? :insert-format :json-compact-each-row
                      :closed-signals #{} :in-flight 0})
          target (exporter/->ChdbExporter :connection false #{:metrics} state)
          observed (atom nil)]
      (with-redefs [exporter/export-metric-groups!
                    (fn
                      ([_ _ groups] (reset! observed {:direct? false :row (first (:gauge groups))}))
                      ([_ _ groups selected] (reset! observed {:direct? selected :row (first (:gauge groups))})))]
        (is (true? (sdk-export/export-metrics! target {} [{:scope {} :metrics
                                                         [{:type :gauge :data-points [{:value 1}]}]}]))))
      (is (= direct? (:direct? @observed)))
      (is (= direct? (vector? (:row @observed))))
      (is (zero? (:in-flight @state))))))

(deftest typed-metric-projector-keeps-raw-context-and-last-wins
  (let [resource {:attributes {"service.name" "resource"}}
        scope {:attributes {"scope" "value"}}
        point {:value 1 :attributes {"point" "value"}}
        effects (atom []) old-attrs @#'exporter/attrs
        projector (fn [context]
                    (swap! effects conj :projector)
                    (is (= {:resource resource :scope scope :point point} context))
                    {"Value" 42})]
    (with-redefs [exporter/attrs (fn [attrs]
                                 (swap! effects conj attrs) (old-attrs attrs))]
      (is (= 42 (get (#'exporter/metric-row resource scope {:type :gauge} point
                                      {:gauge projector} true) "Value"))))
    (is (= [(:attributes resource) (:attributes scope) (:attributes point) :projector]
           @effects))))

(deftest direct-metric-commit-and-overflow-use-existing-boundaries
  (let [columns (:gauge schema/clickstack-metric-insert-columns)
        resource {:attributes {"service.name" "service-left"}}
        metric {:type :gauge :name "metric"}
        row (#'exporter/metric-row resource {:name "scope-right"} metric {:value 1} nil true)
        state (atom {:durable? true :insert-format :json-compact-each-row
                     :compact-plans {"otel_metrics_gauge" (confirmed-plan :connection "otel_metrics_gauge" columns)}})
        requests (atom []) effects (atom [])
        later (reify json/JSONWriter (-write [_ sink _]
                                     (swap! effects conj :later) (.write sink "null")))]
    (with-redefs [exporter/execute-durable-sql!
                  (fn [connection sql] (swap! requests conj [connection sql]) {:status :committed})]
      (#'exporter/insert-direct-metric-batch! :connection state :gauge columns [row])
      (is (= [[:connection (str (#'exporter/compact-insert-query "otel_metrics_gauge" columns)
                               " FORMAT JSONCompactEachRow\n" (json/write-str row) "\n")]]
             @requests))
      (reset! requests [])
      (with-redefs [exporter/max-insert-bytes 1]
        (is (= {:limit 1}
               (error-data #(#'exporter/insert-direct-metric-batch!
                              :connection state :gauge columns [row (assoc row 2 later)])))))
      (is (empty? @effects))
      (is (empty? @requests))
      (swap! state assoc :insert-format :json-each-row)
      (is (= {:type :otel.exporter.chdb/invalid-direct-metric-plan}
             (error-data #(#'exporter/insert-direct-metric-batch! :connection state :gauge columns [row]))))
      (is (empty? @requests)))))

(deftest native-fixture-format-selection-is-closed
  (is (= :json-each-row (wire-config/parse-format [])))
  (is (= :json-each-row (wire-config/parse-format ["json-each-row"])))
  (is (= :json-compact-each-row (wire-config/parse-format ["json-compact-each-row"])))
  (doseq [arguments [["unknown"] ["json-compact-each-row" "extra"] [nil]]]
    (is (= {:type :otel.exporter.chdb-wire-test-config/invalid-format}
           (error-data #(wire-config/parse-format arguments))))))

(deftest format-validation-precedes-resource-acquisition
  (with-redefs [jdbc/connection (fn [& _] (throw (Exception. "must not open")))]
    (doseq [format [:raw-binary "JSONCompactEachRow" nil (Object.)]]
      (is (= {:type :otel.exporter.chdb/invalid-insert-format}
             (error-data #(exporter/exporter {:insert-format format})))))))

(deftest compact-projection-retains-schema-order-and-all-values
  (let [columns ["z" "x" "nil" "boolean" "nested"]
        row {"x" "é😀/?\n" "z" 9223372036854775807
             "nil" nil "boolean" false "nested" {"k" [1 2.5 true]}}
        wire (#'exporter/insert-payload :json-compact-each-row columns [row])
        decoded (json/read-str wire)]
    (is (= (json/read-str (json/write-str row)) (zipmap columns decoded)))
    (is (= [9223372036854775807 "é😀/?\n" nil false {"k" [1 2.5 true]}]
           (first (#'exporter/compact-rows columns [row]))))))

(deftest benchmark-metric-rows-retain-complete-projection
  (let [groups (#'exporter/metric-row-groups
                (#'benchmark/resource "compact-wire") (#'benchmark/metrics 0 4) nil)]
    (doseq [kind [:gauge :sum :histogram]
            :let [columns (get schema/clickstack-metric-insert-columns kind)]
            row (get groups kind)]
      (is (= (json/read-str (json/write-str row))
             (zipmap columns
                     (json/read-str (#'exporter/insert-payload
                                     :json-compact-each-row columns [row]))))))))

(deftest same-type-fields-retain-their-column-binding
  ;; Native coercion cannot detect two String columns accidentally swapped.
  (is (= ["service-left" "scope-right"]
         (json/read-str (#'exporter/insert-payload
                         :json-compact-each-row ["left" "right"]
                         [{"right" "scope-right" "left" "service-left"}])))))

(deftest physical-column-plan-is-closed-and-safely-quoted
  (is (= "insert into otel_logs (`Scope.Name`, `odd``column`)"
         (#'exporter/compact-insert-query "otel_logs" ["Scope.Name" "odd`column"])))
  (doseq [columns [nil [] ["x" "x"] [:x]]]
    (is (= {:type :otel.exporter.chdb/invalid-compact-columns}
           (error-data #(doall (#'exporter/compact-rows columns []))))))
  (is (= {:type :otel.exporter.chdb/invalid-compact-table}
         (error-data #(#'exporter/compact-insert-query "unowned_table" ["x"])))))

(deftest projection-rejects-missing-extra-and-falsey-rows
  (doseq [row [{"other" 1} {"x" 1 "extra" 2} {} nil false]]
    (is (= {:type :otel.exporter.chdb/invalid-compact-row}
           (error-data #(doall (#'exporter/compact-rows ["x"] [row])))))))

(deftest overflow-does-not-realize-the-following-row
  (let [visited (atom [])
        rows (lazy-seq
               (swap! visited conj :first)
               (cons {"x" "abc"}
                     (lazy-seq (swap! visited conj :second) (list {"x" "later"}))))]
    (with-redefs [exporter/max-insert-bytes 7]
      (is (= {:limit 7}
             (error-data #(#'exporter/insert-payload :json-compact-each-row ["x"] rows)))))
    (is (= [:first] @visited))))

(deftest compact-durable-insert-retains-one-confirmed-request
  (let [calls (atom []) state (atom {:durable? true :insert-format :json-compact-each-row
                                   :compact-plans {"otel_logs" (confirmed-plan :connection ["x"])}})]
    (with-redefs [exporter/execute-durable-sql!
                  (fn [connection sql] (swap! calls conj [connection sql]) {:status :committed})]
      (#'exporter/insert-batch! :connection state "otel_logs" ["x"] "ignored" [{"x" "?"}]))
    (is (= [[:connection "insert into otel_logs (`x`) FORMAT JSONCompactEachRow\n[\"?\"]\n"]] @calls))))

(deftest default-durable-wire-is-unchanged
  (let [calls (atom []) state (atom {:durable? true})]
    (with-redefs [exporter/execute-durable-sql!
                  (fn [_ sql] (swap! calls conj sql) {:status :committed})]
      (#'exporter/insert-batch! nil state "otel_logs" ["x"] "original query" [{"x" 1}]))
    (is (= ["original query FORMAT JSONEachRow\n{\"x\":1}\n"] @calls))))

(deftest compact-ordinary-route-uses-the-query-api-with-an-explicit-plan
  (let [calls (atom []) state (atom {:durable? false :insert-format :json-compact-each-row
                                   :compact-plans {"otel_logs" (confirmed-plan :connection ["x"])}})]
    (with-redefs [jdbc/execute! (fn [connection sql] (swap! calls conj [connection sql]))
                  native-driver/insert-json-rows! (fn [& _] (throw (Exception. "wrong format transport")))]
      (#'exporter/insert-batch! :connection state "otel_logs" ["x"] "ignored" [{"x" 1}]))
    (is (= [[:connection "insert into otel_logs (`x`) FORMAT JSONCompactEachRow\n[1]\n"]] @calls))))

(deftest compact-ordinary-metric-failure-still-precedes-every-driver-call
  (let [calls (atom 0)
        groups (#'exporter/metric-row-groups
                 (#'benchmark/resource "compact-wire") (#'benchmark/metrics 0 1) nil)
        groups (assoc groups :histogram [(assoc (first (:histogram groups)) "Count" -1)])
        state (atom {:durable? false :insert-format :json-compact-each-row})]
    (with-redefs [jdbc/execute! (fn [& _] (swap! calls inc))]
      (is (= {:type :otel.exporter.chdb/invalid-ordinary-row}
             (error-data #(#'exporter/export-metric-groups! nil state groups)))))
    (is (zero? @calls))))

(deftest typed-null-and-status-slots-retain-declared-order
  (let [columns ["TypedStatus" "TypedValue" "Generic"]
        row {"Generic" {"field" "historical"} "TypedValue" nil "TypedStatus" 3}]
    (is (= [3 nil {"field" "historical"}]
           (first (#'exporter/compact-rows columns [row]))))))

(deftest missing-field-rejection-never-enters-the-driver
  (let [calls (atom 0) state (atom {:durable? true :insert-format :json-compact-each-row
                                  :compact-plans {"otel_logs" (confirmed-plan nil ["x"])}})]
    (with-redefs [exporter/execute-durable-sql! (fn [& _] (swap! calls inc))]
      (is (= {:type :otel.exporter.chdb/invalid-compact-row}
             (error-data #(#'exporter/insert-batch! nil state "otel_logs" ["x"] "ignored" [{"other" 1}])))))
    (is (zero? @calls))))

(deftest schema-confirmation-binds-source-order-and-normalizes-only-whitespace
  (let [queries (atom [])
        columns ["x" "y"] types {"x" "String" "y" "Map(String,String)"}]
    (with-redefs [jdbc/fetch (fn [connection sql]
                              (swap! queries conj [connection sql])
                              [{:name "y" :type "Map(String, String)"}
                               {:name "extra" :type "Bool"}
                               {:name "x" :type "String"}])]
      (let [plan (compact-format/confirm! :connection "otel_logs" columns types)]
        (is (nil? (compact-format/require-order! {"otel_logs" plan} :connection "otel_logs" columns)))
        (is (= "#<confirmed compact schema>" (str plan)))))
    (is (= [[:connection "DESCRIBE TABLE otel_logs"]] @queries))))

(deftest unproven-missing-wrong-and-duplicate-types-fail-closed
  (doseq [observed [[] [{:name "x" :type "UInt8"}]
                   [{:name "x" :type nil}]
                   [{:name "x" :type "String"} {:name "x" :type "String"}]]]
    (with-redefs [jdbc/fetch (fn [& _] observed)]
      (is (= {:type :otel.exporter.chdb.compact-format/schema-not-confirmed}
             (error-data #(compact-format/confirm! nil "otel_logs" ["x"] {"x" "String"}))))))
  (with-redefs [jdbc/fetch (fn [& _] (throw (ex-info "secret provider detail" {:secret true})))]
    (let [error (try (compact-format/confirm! nil "otel_logs" ["x"] {"x" "String"})
                    (catch Throwable error error))]
      (is (= "Compact telemetry schema was not confirmed" (.getMessage error)))
      (is (= {:type :otel.exporter.chdb.compact-format/schema-not-confirmed} (ex-data error)))
      (is (nil? (.getCause error))))))

(deftest invalid-plan-never-queries-the-driver
  (let [calls (atom 0)]
    (with-redefs [jdbc/fetch (fn [& _] (swap! calls inc))]
      (doseq [[table columns types] [["other" ["x"] {"x" "String"}]
                                     ["otel_logs" [] {}]
                                     ["otel_logs" ["x" "x"] {"x" "String"}]
                                     ["otel_logs" ["x"] {}]]]
        (is (= {:type :otel.exporter.chdb.compact-format/schema-not-confirmed}
               (error-data #(compact-format/confirm! nil table columns types)))))
      (is (zero? @calls)))))

(deftest missing-or-reordered-confirmation-never-enters-the-writer
  (let [calls (atom 0)]
    (doseq [plans [nil {} {"otel_logs" (confirmed-plan nil ["y" "x"])}
                   {"otel_logs" {:columns ["x" "y"]}}]]
      (with-redefs [exporter/execute-durable-sql! (fn [& _] (swap! calls inc))]
        (is (= {:type :otel.exporter.chdb.compact-format/schema-not-confirmed}
               (error-data #(#'exporter/insert-batch!
                             nil (atom {:durable? true :insert-format :json-compact-each-row
                                        :compact-plans plans})
                             "otel_logs" ["x" "y"] "ignored" [{"x" "left" "y" "right"}]))))))
    (is (zero? @calls))))

(deftest all-physical-trace-and-typed-plans-have-declared-types
  (is (= (set (conj schema/clickstack-trace-insert-columns "EventsJSON" "LinksJSON"))
         (set (keys schema/clickstack-trace-insert-types))))
  (is (= {"b" "Bool" "b_status" "UInt8" "i" "Int64" "i_status" "UInt8"
          "s" "String" "s_status" "UInt8"}
         (compact-format/typed-types
          [{:type :boolean :physical {:value-column "b" :status-column "b_status"}}
           {:type :int64 :physical {:value-column "i" :status-column "i_status"}}
           {:type :string :physical {:value-column "s" :status-column "s_status"}}]))))

(deftest a-confirmed-order-is-not-reusable-on-another-connection
  (is (= {:type :otel.exporter.chdb.compact-format/schema-not-confirmed}
         (error-data #(compact-format/require-order!
                       {"otel_logs" (confirmed-plan :original ["x"])}
                       :other "otel_logs" ["x"])))))

(defn -main [& _]
  (let [result (test/run-tests 'otel.exporter.chdb-compact-wire-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
