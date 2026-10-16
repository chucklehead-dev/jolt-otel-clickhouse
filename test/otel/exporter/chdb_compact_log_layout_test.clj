(ns otel.exporter.chdb-compact-log-layout-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb-compact-wire-test :as wire]))

(def columns schema/clickstack-log-insert-columns)
(def base-record
  {:timestamp-unix-nano 12 :observed-timestamp-unix-nano 34
   :trace-id "trace-left" :span-id "span-right" :trace-flags 257
   :severity-text "WARN" :severity-number 13 :body "é😀\n"
   :resource {:schema-url "resource-url" :attributes {"service.name" "service-left"}}
   :scope {:schema-url "scope-url" :name "scope-right" :version "v2"
           :attributes {:scope true}} :attributes {:log 42} :event-name "event-left"})

(defn legacy-log-row [record]
  (let [scope (:scope record) resource (:resource record)]
    {"Timestamp" (#'exporter/timestamp (#'exporter/log-timestamp-nanos record))
     "TraceId" (or (:trace-id record) "") "SpanId" (or (:span-id record) "")
     "TraceFlags" (#'exporter/uint8 (:trace-flags record))
     "SeverityText" (or (:severity-text record) "")
     "SeverityNumber" (#'exporter/uint8 (:severity-number record))
     "ServiceName" (#'exporter/service-name resource "")
     "Body" (#'exporter/value-string (:body record))
     "ResourceSchemaUrl" (or (:schema-url resource) "")
     "ResourceAttributes" (#'exporter/attrs (:attributes resource))
     "ScopeSchemaUrl" (or (:schema-url scope) "")
     "ScopeName" (or (:name scope) "") "ScopeVersion" (or (:version scope) "")
     "ScopeAttributes" (#'exporter/attrs (:attributes scope))
     "LogAttributes" (#'exporter/attrs (:attributes record))
     "EventName" (or (:event-name record) "")}))

(deftest direct-log-parity
  (doseq [body [nil "" "é😀\n" 9007199254740993 true {:nested [1 nil true]}]
          record [base-record {} nil false]]
    (let [record (if (map? record) (assoc record :body body) record)
          legacy (legacy-log-row record) direct (#'exporter/log-row record nil true)]
      (is (= legacy (#'exporter/log-row record nil)))
      (is (= (mapv legacy columns) direct))
      (is (= (json/write-str (mapv legacy columns)) (json/write-str direct))))))

(deftest log-column-binding-canary
  (is (= (mapv (legacy-log-row base-record) columns)
         (#'exporter/log-row base-record nil true))))

(deftest conversion-order-and-projector-precedence
  (let [old-attrs @#'exporter/attrs old-value @#'exporter/value-string
        observe (fn [build]
                  (let [effects (atom [])]
                    (with-redefs [exporter/attrs (fn [x] (swap! effects conj [:attrs x]) (old-attrs x))
                                  exporter/value-string (fn [x] (swap! effects conj [:body x]) (old-value x))]
                      [(build) @effects])))]
    (is (= (observe #(mapv (legacy-log-row base-record) columns))
           (observe #(#'exporter/log-row base-record nil true))))
    (let [seen (atom nil) projector (fn [raw] (reset! seen raw) {"Body" "typed"})]
      (is (= "typed" (get (#'exporter/log-row base-record projector true) "Body")))
      (is (identical? base-record @seen)))))

(defn log-state []
  (atom {:durable? true :insert-format :json-compact-each-row
         :log-insert-columns columns
         :compact-plans {"otel_logs" (wire/confirmed-plan :connection "otel_logs" columns)}}))

(deftest closed-selection-and-routing
  (let [snapshot @(log-state)]
    (is (#'exporter/direct-log-layout? snapshot))
    (doseq [changed [(assoc snapshot :durable? false)
                     (assoc snapshot :insert-format :json-each-row)
                     (assoc snapshot :typed-log-projector identity)
                     (assoc snapshot :log-insert-columns (vec (reverse columns)))]]
      (is (not (#'exporter/direct-log-layout? changed)))))
  (doseq [direct? [true false]]
    (let [state (log-state) calls (atom [])]
      (swap! state assoc :durable? direct?)
      (with-redefs [exporter/insert-direct-log-batch! (fn [& _] (swap! calls conj :direct))
                    exporter/insert-batch! (fn [& _] (swap! calls conj :maps))]
        (#'exporter/insert-untyped-log-records! :connection state [base-record]))
      (is (= [(if direct? :direct :maps)] @calls)))))

(deftest confirmed-sql-overflow-and-post-conversion-fence
  (let [state (log-state) requests (atom []) effects (atom [])
        expected (str (#'exporter/compact-insert-query "otel_logs" columns)
                      " FORMAT JSONCompactEachRow\n"
                      (json/write-str (mapv (legacy-log-row base-record) columns)) "\n")]
    (with-redefs [exporter/execute-durable-sql! (fn [connection sql] (swap! requests conj [connection sql]))]
      (#'exporter/insert-direct-log-batch! :connection state [base-record])
      (is (= [[:connection expected]] @requests))
      (reset! requests [])
      (with-redefs [exporter/max-insert-bytes 1]
        (is (= {:limit 1}
               (wire/error-data #(#'exporter/insert-direct-log-batch!
                                   :connection state
                                   (lazy-seq (cons base-record
                                                   (lazy-seq (swap! effects conj :later)
                                                             (list base-record)))))))))
      (is (empty? @effects)) (is (empty? @requests))
      (let [old-value @#'exporter/value-string]
        (with-redefs [exporter/value-string (fn [x]
                                             (swap! state assoc :insert-format :json-each-row)
                                             (old-value x))]
          (is (= {:type :otel.exporter.chdb/invalid-direct-log-plan}
                 (wire/error-data #(#'exporter/insert-direct-log-batch! :connection state [base-record]))))))
      (is (empty? @requests)))))
