(ns otel.exporter.chdb-property-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hegel.core :as h]
            [hegel.generator :as g]
            [hegel.stateful :as hs]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.schema :as schema]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as sdk-logs]))

(def ^:private signals [:spans :logs :metrics])

(defn- fail! [origin message data]
  (throw (ex-info message (assoc data :hegel/origin origin))))

(defn- check! [condition origin message data]
  (when-not condition (fail! origin message data)))

(defn- signal-export! [exporter signal]
  (case signal
    :spans (export/export-spans! exporter [])
    :logs (sdk-logs/export-logs! exporter [])
    :metrics (export/export-metrics! exporter {} [])))

(defn- signal-shutdown! [exporter signal]
  (case signal
    :spans (export/shutdown-exporter! exporter)
    :logs (sdk-logs/shutdown-log-exporter! exporter)
    :metrics (export/shutdown-metric-exporter! exporter)))

(defn- lifecycle-step [op signal]
  (fn [{:keys [exporter expected closed] :as state}]
    (let [actual (case op
                   :export (signal-export! exporter signal)
                   :shutdown (signal-shutdown! exporter signal))
          expected-result (if (= op :shutdown)
                            true
                            (and (contains? expected signal)
                                 (not (contains? closed signal))))]
      (check! (= expected-result actual)
              "otel-exporter/lifecycle-result"
              "signal operation disagreed with the lifecycle model"
              {:operation op :signal signal
               :expected expected-result :actual actual})
      (cond-> state (= op :shutdown) (update :closed conj signal)))))

(defn- lifecycle-invariant
  [{:keys [exporter expected closed close-count]}]
  (let [should-close? (every? closed expected)
        snapshot @(:state exporter)]
    (and (= (if should-close? 1 0) @close-count)
         (= should-close? (:connection-closed? snapshot))
         (= closed (:closed-signals snapshot)))))

(defn- lifecycle-property []
  (h/run-test!
   {:name "otel exporter per-signal lifecycle"
    :database "" :verbosity :quiet :derandomize? true
    :test-cases 80 :stateful-step-count 36}
   (fn [_]
     (let [expected (h/draw! (g/set {:min-size 1 :max-size 3}
                                    (g/sampled-from signals)))
           close-count (atom 0)
           connection {:close (fn [_] (swap! close-count inc))}
           exporter (chdb-export/->ChdbExporter
                     connection true expected
                     (atom {:closed-signals #{}
                            :connection-closed? false
                            :last-error nil}))]
       (hs/run!
        {:initial-state {:exporter exporter :expected expected
                         :closed #{} :close-count close-count}
         :rules [(hs/rule :export-spans (lifecycle-step :export :spans))
                 (hs/rule :export-logs (lifecycle-step :export :logs))
                 (hs/rule :export-metrics (lifecycle-step :export :metrics))
                 (hs/rule :shutdown-spans (lifecycle-step :shutdown :spans))
                 (hs/rule :shutdown-logs (lifecycle-step :shutdown :logs))
                 (hs/rule :shutdown-metrics (lifecycle-step :shutdown :metrics))]
         :invariants [(hs/invariant :owned-connection-closes-once
                                    lifecycle-invariant)]})))))

(defn- wire-json-property []
  (h/run-test!
   {:name "otel exporter JSON safety and correlation"
    :database "" :verbosity :quiet :derandomize? true :test-cases 160}
   (fn [_]
     (let [trace-id (h/draw! (g/string {:min-size 32 :max-size 32
                                        :alphabet "0123456789abcdef"}))
           span-id (h/draw! (g/string {:min-size 16 :max-size 16
                                       :alphabet "0123456789abcdef"}))
           attr-key (h/draw! (g/string {:max-size 24}))
           text (h/draw! (g/string {:max-size 80}))
           trace-flags (h/draw! (g/integer 0 1024))
           severity-number (h/draw! (g/integer 0 1024))
           nested [text {"line\nbreak" text}]
           attributes {attr-key nested :plain text}
           event {:name text :timestamp-unix-nano 1000000002
                  :attributes attributes}
           link {:span-context {:trace-id trace-id :span-id span-id
                                :trace-state [["vendor" "state"]]}
                 :attributes {:relation text}}
           span {:span-context {:trace-id trace-id :span-id span-id
                                :trace-state [["root" "sampled"]]}
                 :parent-span-id "" :name text :kind :server
                 :start-time-unix-nano 1000000001
                 :end-time-unix-nano 1000000011
                 :status {:code :ok :description text}
                 :scope {:name text :version "1"}
                 :resource {:attributes {:service.name text}}
                 :attributes attributes :events [event] :links [link]}
           log {:timestamp-unix-nano 0
                :observed-time-unix-nano 1000000002
                :trace-id trace-id :span-id span-id :trace-flags trace-flags
                :severity-text "INFO" :severity-number severity-number :body nested
                :event-name text
                :scope {:name text :version "1" :schema-url "scope-schema"
                        :attributes attributes}
                :resource {:schema-url "resource-schema"
                           :attributes {:service.name text :resource attributes}}
                :attributes attributes}
           captured (atom [])
           exporter (chdb-export/->ChdbExporter
                     {} false #{:spans :logs :metrics}
                     (atom {:closed-signals #{}
                            :connection-closed? false :last-error nil}))]
       (with-redefs [jdbc/execute!
                     (fn [_ statement]
                       (let [[table payload]
                             (str/split
                              statement #" FORMAT JSONEachRow\n" 2)]
                         (swap! captured conj
                                [table (vec (remove str/blank?
                                                    (str/split-lines payload)))])
                         0))]
         (check! (export/export-spans! exporter [span])
                 "otel-exporter/span-export" "span export failed" {})
         (check! (sdk-logs/export-logs! exporter [log])
                 "otel-exporter/log-export" "log export failed" {}))
       (let [[[span-table span-lines] [log-table log-lines]] @captured
             span-wire (json/read-str (first span-lines))
             log-wire (json/read-str (first log-lines))]
         (check! (= "insert into otel_traces" span-table)
                 "otel-exporter/table-routing" "signal used the wrong table" {})
         (check! (= (str "insert into otel_logs ("
                         (str/join ", " schema/clickstack-log-insert-columns)
                         ")")
                    log-table)
                 "otel-exporter/log-insert-columns"
                 "log insert columns differ from the pinned collector order" {})
         (check! (= [trace-id span-id trace-id span-id]
                    [(get span-wire "TraceId") (get span-wire "SpanId")
                     (get log-wire "TraceId") (get log-wire "SpanId")])
                 "otel-exporter/correlation" "wire rows lost correlation IDs" {})
         (check! (= (json/write-str nested) (get log-wire "Body"))
                 "otel-exporter/log-body" "structured log body was not JSON-safe" {})
         (check! (= "1.000000002"
                    (get log-wire "Timestamp"))
                 "otel-exporter/log-observed-time"
                 "zero event time did not fall back to observed time" {})
         (check! (= [(bit-and trace-flags 0xff)
                     (bit-and severity-number 0xff)]
                    [(get log-wire "TraceFlags")
                     (get log-wire "SeverityNumber")])
                 "otel-exporter/log-uint8"
                 "log uint8 fields differ from collector conversion" {})
         (check! (= ["resource-schema" "scope-schema" text "1" text]
                    [(get log-wire "ResourceSchemaUrl")
                     (get log-wire "ScopeSchemaUrl")
                     (get log-wire "ScopeName")
                     (get log-wire "ScopeVersion")
                     (get log-wire "EventName")])
                 "otel-exporter/log-metadata"
                 "canonical log metadata was lost" {})
         (check! (= (json/write-str nested)
                    (get-in log-wire ["LogAttributes" attr-key]))
                 "otel-exporter/log-attributes"
                 "structured log attributes were not string-normalized" {})
         (check! (= 1 (count (json/read-str (get span-wire "EventsJSON"))))
                 "otel-exporter/span-events" "span event JSON did not round-trip" {})
         (check! (= [text] (get span-wire "Events.Name"))
                 "otel-exporter/nested-event-name" "nested event name was lost" {})
         (check! (= [trace-id] (get span-wire "Links.TraceId"))
                 "otel-exporter/nested-link-trace" "nested link trace ID was lost" {})
         (check! (= (json/write-str nested)
                    (get-in span-wire ["Events.Attributes" 0 attr-key]))
                 "otel-exporter/nested-event-attributes"
                 "nested event attributes were not string-normalized" {})
         (check! (= ["vendor=state"] (get span-wire "Links.TraceState"))
                 "otel-exporter/nested-link-state" "nested link trace state was not raw" {})
         (check! (= "root=sampled" (get span-wire "TraceState"))
                 "otel-exporter/trace-state" "span trace state was not raw" {})
         (check! (= ["Server" "Ok"]
                    [(get span-wire "SpanKind") (get span-wire "StatusCode")])
                 "otel-exporter/trace-enums"
                 "span enum strings differ from collector pdata values" {})
         (check! (= (json/write-str nested)
                    (get-in span-wire ["SpanAttributes" attr-key]))
                 "otel-exporter/attributes" "structured attribute did not round-trip" {}))))))

(defn- metric-wire-property []
  (h/run-test!
   {:name "otel exporter canonical metric wire rows"
    :database "" :verbosity :quiet :derandomize? true :test-cases 120}
   (fn [_]
     (let [text (h/draw! (g/string {:max-size 48}))
           value (h/draw! (g/integer -1000 1000))
           resource {:schema-url "resource-schema"
                     :attributes {:service.name text :resource.value value}}
           scope {:name text :version "1" :schema-url "scope-schema"
                  :attributes {:scope.value value}}
           collected
           [{:scope scope
             :metrics
             [{:type :gauge :name "g" :description text :unit "1"
               :data-points [{:attributes {:k value}
                              :time-unix-nano 2000000002 :value value}]}
              {:type :sum :name "s" :description text :unit "1"
               :temporality :delta :monotonic? false
               :data-points [{:attributes {:k value}
                              :start-time-unix-nano 1000000001
                              :time-unix-nano 2000000002 :value value}]}
              {:type :histogram :name "h" :description text :unit "ms"
               :temporality :cumulative :explicit-bounds [10.0]
               :data-points [{:attributes {:k value}
                              :start-time-unix-nano 1000000001
                              :time-unix-nano 2000000002 :count 1
                              :sum value :bucket-counts [1 0]
                              :min value :max value}]}]}]
           captured (atom [])
           exporter (chdb-export/->ChdbExporter
                     {} false #{:metrics}
                     (atom {:closed-signals #{}
                            :connection-closed? false :last-error nil}))]
       (with-redefs [jdbc/execute!
                     (fn [_ statement]
                       (let [[query payload]
                             (str/split statement #" FORMAT JSONEachRow\n" 2)]
                         (swap! captured conj
                                [query (json/read-str
                                        (first (remove str/blank?
                                                       (str/split-lines payload))))])
                         0))]
         (check! (export/export-metrics! exporter resource collected)
                 "otel-exporter/metric-export" "metric export failed" {}))
       (let [rows (into {} (map (fn [[query row]]
                                  [(get row "MetricName") [query row]]))
                        @captured)]
         (check! (= #{"g" "s" "h"} (set (keys rows)))
                 "otel-exporter/metric-routing"
                 "supported metric kinds did not each produce one row" {})
         (doseq [[kind metric-name] [[:gauge "g"] [:sum "s"] [:histogram "h"]]]
           (let [[query row] (get rows metric-name)]
             (check! (= (str "insert into " (get schema/metric-table-names kind)
                             " (" (str/join ", "
                                             (get schema/clickstack-metric-insert-columns kind))
                             ")")
                        query)
                     "otel-exporter/metric-insert-columns"
                     "metric insert columns differ from the pinned collector" {:kind kind})
             (check! (= [0 [] [] [] [] []]
                        [(get row "Flags")
                         (get row "Exemplars.FilteredAttributes")
                         (get row "Exemplars.TimeUnix")
                         (get row "Exemplars.Value")
                         (get row "Exemplars.SpanId")
                         (get row "Exemplars.TraceId")])
                     "otel-exporter/metric-unmodeled-defaults"
                     "unmodeled metric fields did not use canonical empty defaults"
                     {:kind kind})
             (check! (= ["resource-schema" "scope-schema" text "1"
                          {"scope.value" (str value)} 0]
                        [(get row "ResourceSchemaUrl") (get row "ScopeSchemaUrl")
                         (get row "ScopeName") (get row "ScopeVersion")
                         (get row "ScopeAttributes")
                         (get row "ScopeDroppedAttrCount")])
                     "otel-exporter/metric-metadata"
                     "representable metric metadata was lost" {:kind kind})))
         (check! (= 0 (get-in rows ["g" 1 "StartTimeUnix"]))
                 "otel-exporter/gauge-zero-start"
                 "absent gauge start time was fabricated" {})
         (check! (= [1 2]
                    [(get-in rows ["s" 1 "AggregationTemporality"])
                     (get-in rows ["h" 1 "AggregationTemporality"])])
                 "otel-exporter/metric-temporality"
                 "metric temporality codes differ from pdata" {}))))))

(defn run-properties! []
  [{:label "per-signal lifecycle swarm" :result (lifecycle-property)}
   {:label "JSON safety and correlation" :result (wire-json-property)}
   {:label "canonical metric wire rows" :result (metric-wire-property)}])
