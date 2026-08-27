(ns otel.exporter.chdb-property-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hegel.core :as h]
            [hegel.generator :as g]
            [hegel.stateful :as hs]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb :as chdb-export]
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
           nested [text {"line\nbreak" text}]
           attributes {attr-key nested :plain text}
           span {:span-context {:trace-id trace-id :span-id span-id}
                 :parent-span-id "" :name text :kind :server
                 :start-time-unix-nano 1000000001
                 :end-time-unix-nano 1000000011
                 :status {:code :ok :description text}
                 :scope {:name text :version "1"}
                 :resource {:attributes {:service.name text}}
                 :attributes attributes :events [nested] :links []}
           log {:timestamp-unix-nano 1000000002
                :trace-id trace-id :span-id span-id :trace-flags 1
                :severity-text "INFO" :severity-number 9 :body nested
                :scope {:name text :attributes attributes}
                :resource {:attributes {:service.name text}}
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
         (check! (= ["insert into otel_traces" "insert into otel_logs"]
                    [span-table log-table])
                 "otel-exporter/table-routing" "signal used the wrong table" {})
         (check! (= [trace-id span-id trace-id span-id]
                    [(get span-wire "TraceId") (get span-wire "SpanId")
                     (get log-wire "TraceId") (get log-wire "SpanId")])
                 "otel-exporter/correlation" "wire rows lost correlation IDs" {})
         (check! (= (json/write-str nested) (get log-wire "Body"))
                 "otel-exporter/log-body" "structured log body was not JSON-safe" {})
         (check! (= [nested] (json/read-str (get span-wire "EventsJSON")))
                 "otel-exporter/span-events" "span event JSON did not round-trip" {})
         (check! (= (json/write-str nested)
                    (get-in span-wire ["SpanAttributes" attr-key]))
                 "otel-exporter/attributes" "structured attribute did not round-trip" {}))))))

(defn run-properties! []
  [{:label "per-signal lifecycle swarm" :result (lifecycle-property)}
   {:label "JSON safety and correlation" :result (wire-json-property)}])
