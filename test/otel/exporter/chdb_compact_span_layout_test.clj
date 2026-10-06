(ns otel.exporter.chdb-compact-span-layout-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.sdk.export :as sdk-export]
            [otel.exporter.chdb-compact-wire-test :as wire]))

(def columns (into schema/clickstack-trace-insert-columns ["EventsJSON" "LinksJSON"]))

(defn legacy-span-row [span]
  ;; Independent pre-change map construction; not the new shared layout body.
  (let [context (:span-context span) scope (:scope span) resource (:resource span)
        events (or (:events span) []) links (or (:links span) [])]
    (merge {"Timestamp" (#'exporter/timestamp (:start-time-unix-nano span))
            "TraceId" (or (:trace-id context) "") "SpanId" (or (:span-id context) "")
            "ParentSpanId" (or (:parent-span-id span) "")
            "TraceState" (#'exporter/trace-state-string (:trace-state context))
            "SpanName" (:name span) "SpanKind" (#'exporter/otel-enum-string (:kind span) :internal)
            "ServiceName" (#'exporter/service-name resource "unknown_service:jolt")
            "ResourceAttributes" (#'exporter/attrs (:attributes resource))
            "ScopeName" (or (:name scope) "") "ScopeVersion" (or (:version scope) "")
            "SpanAttributes" (#'exporter/attrs (:attributes span))
            "Duration" (max 0 (- (:end-time-unix-nano span) (:start-time-unix-nano span)))
            "StatusCode" (#'exporter/otel-enum-string (get-in span [:status :code]) :unset)
            "StatusMessage" (or (get-in span [:status :description]) "")
            "EventsJSON" (json/write-str events) "LinksJSON" (json/write-str links)}
           (#'exporter/event-columns events) (#'exporter/link-columns links))))

(def base-span
  {:name "span/é😀" :start-time-unix-nano 1 :end-time-unix-nano 12
   :span-context {:trace-id "trace-left" :span-id "span-right"}
   :scope {:name "scope-right"} :resource {:attributes {"service.name" "service-left"}}
   :kind :server :status {:code :error}})

(deftest direct-span-layout-matches-legacy-values-and-json
  (doseq [events [[] [{:timestamp-unix-nano 3 :name "event" :attributes {:nested [1 true]}}]]
          links [[] [{:span-context {:trace-id "link-trace" :span-id "link-span"
                                    :trace-state [["key" "value"]]} :attributes {:count 4}}]]
          attributes [{} {:nested {"v" [nil true 9007199254740993]} :unicode "é😀\n/"}]]
    (let [span (assoc base-span :events events :links links :attributes attributes)
          legacy (legacy-span-row span)
          named (#'exporter/span-row span nil)
          direct (#'exporter/span-row span nil true)]
      (is (= legacy named))
      (is (= (mapv legacy columns) direct))
      (is (= (json/write-str (mapv legacy columns)) (json/write-str direct))))))

(deftest span-column-binding-canary
  (is (= (mapv (legacy-span-row base-span) columns)
         (#'exporter/span-row base-span nil true))))

(deftest direct-span-conversion-order-and-typed-precedence
  (let [span (assoc base-span :resource {:attributes {:nested [1 true]}}
                             :attributes {:nested {"v" 2}}
                             :events [{:timestamp-unix-nano 3 :attributes {:event 1}}]
                             :links [{:attributes {:link 2}}])
        old-attrs @#'exporter/attrs old-write json/write-str
        observe (fn [build]
                  (let [effects (atom [])]
                    (with-redefs [exporter/attrs (fn [source] (swap! effects conj [:attrs source]) (old-attrs source))
                                  json/write-str (fn [value] (swap! effects conj [:json value]) (old-write value))]
                      [(build span) @effects])))]
    (is (= (observe #(mapv (legacy-span-row %) columns))
           (observe #(#'exporter/span-row % nil true))))
    (let [seen (atom nil) projector (fn [raw] (reset! seen raw) {"SpanName" "typed"})]
      (is (= "typed" (get (#'exporter/span-row span projector true) "SpanName")))
      (is (identical? span @seen)))))

(defn state-for-span []
  (atom {:durable? true :insert-format :json-compact-each-row
         :span-insert-columns columns
         :compact-plans {"otel_traces" (wire/confirmed-plan :connection "otel_traces" columns)}}))

(deftest direct-span-selection-is-closed
  (let [snapshot @(state-for-span)]
    (is (#'exporter/direct-span-layout? snapshot))
    (doseq [changed [(assoc snapshot :durable? false)
                     (assoc snapshot :insert-format :json-each-row)
                     (assoc snapshot :typed-span-projector identity)
                     (assoc snapshot :span-insert-columns (conj columns "Extra"))
                     (assoc snapshot :span-insert-columns (assoc columns 1 (nth columns 2) 2 (nth columns 1)))]]
      (is (not (#'exporter/direct-span-layout? changed))))))

(deftest sdk-selects-the-closed-span-path-without-changing-ordinary-selection
  (doseq [direct? [true false]]
    (let [state (state-for-span) calls (atom [])]
      (swap! state assoc :durable? direct? :closed-signals #{} :in-flight 0)
      (with-redefs [exporter/insert-direct-span-batch! (fn [& _] (swap! calls conj :direct))
                    exporter/insert-batch! (fn [& _] (swap! calls conj :maps))]
        (is (true? (sdk-export/export-spans!
                     (exporter/->ChdbExporter :connection false #{:spans} state) [base-span]))))
      (is (= [(if direct? :direct :maps)] @calls))
      (is (zero? (:in-flight @state))))))

(deftest direct-span-insert-retains-confirmation-and-overflow-order
  (let [state (state-for-span) requests (atom []) effects (atom [])
        expected (str (#'exporter/compact-insert-query "otel_traces" columns)
                      " FORMAT JSONCompactEachRow\n"
                      (json/write-str (mapv (legacy-span-row base-span) columns)) "\n")]
    (with-redefs [exporter/execute-durable-sql!
                  (fn [connection sql] (swap! requests conj [connection sql]) {:status :committed})]
      (#'exporter/insert-direct-span-batch! :connection state [base-span])
      (is (= [[:connection expected]] @requests))
      (reset! requests [])
      (with-redefs [exporter/max-insert-bytes 1]
        (let [spans (lazy-seq (cons base-span (lazy-seq (swap! effects conj :later) (list base-span))))]
          (is (= {:limit 1} (wire/error-data #(#'exporter/insert-direct-span-batch! :connection state spans))))))
      (is (empty? @effects))
      (is (empty? @requests)))))

(deftest span-plan-change-during-json-precedes-native-mutation
  (let [state (state-for-span) requests (atom 0) old-write json/write-str]
    (with-redefs [exporter/execute-durable-sql! (fn [& _] (swap! requests inc))
                  json/write-str (fn [value]
                                   (swap! state assoc :insert-format :json-each-row)
                                   (old-write value))]
      (is (= {:type :otel.exporter.chdb/invalid-direct-span-plan}
             (wire/error-data #(#'exporter/insert-direct-span-batch! :connection state [base-span])))))
    (is (zero? @requests))))

(deftest shared-metric-boundary-rechecks-after-custom-serialization
  (let [columns (:histogram schema/clickstack-metric-insert-columns)
        state (atom {:durable? true :insert-format :json-compact-each-row
                     :compact-plans {"otel_metrics_histogram"
                                     (wire/confirmed-plan :connection "otel_metrics_histogram" columns)}})
        requests (atom 0)
        value (reify json/JSONWriter
                (-write [_ sink _]
                  (swap! state assoc :insert-format :json-each-row)
                  (.write sink "[1]")))
        row (#'exporter/metric-row {} {} {:type :histogram :explicit-bounds [1.0]}
                                  {:count 1 :sum 1 :bucket-counts value} nil true)]
    (with-redefs [exporter/execute-durable-sql! (fn [& _] (swap! requests inc))]
      (is (= {:type :otel.exporter.chdb/invalid-direct-metric-plan}
             (wire/error-data #(#'exporter/insert-direct-metric-batch!
                                 :connection state :histogram columns [row])))))
    (is (zero? @requests))))
