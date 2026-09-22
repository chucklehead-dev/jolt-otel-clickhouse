(ns otel.exporter.chdb-untyped-log-encoder-test
  "Focused parity/route checks for the Jolt-only schema-bound log writer."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests]]
            [jdbc.chdb :as chdb]
            [jdbc.chdb.durable :as durable]
            [otel.exporter.chdb :as exporter]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(defn- utf8 [text] (vec (.getBytes text "UTF-8")))
(defn- baseline [record] (json/write-str (#'exporter/log-row record nil)))

(def base-log
  {:timestamp-unix-nano 1700000000000000000
   :observed-time-unix-nano 1700000000000000001
   :trace-id "0123456789abcdef0123456789abcdef"
   :span-id "0123456789abcdef"
   :trace-flags 1 :severity-text "INFO" :severity-number 9
   :body "routine body" :event-name "event"
   :attributes {"enabled" true "answer" 42}
   :resource {:schema-url "resource-v1"
              :attributes {"service.name" "log-renderer" "region" "us-east-2"}}
   :scope {:schema-url "scope-v1" :name "scope" :version "1"
           :attributes {"library" "test"}}})

(deftest exact-utf8-pathological-corpus
  (let [encoder (#'exporter/compile-untyped-log-encoder)
        corpus [base-log
                (assoc base-log :timestamp-unix-nano 0 :body nil
                       :severity-text "quote-\" slash/ backslash\\ newline\nnull\u0000")
                (assoc base-log :body "unicode-λ-é-😀-\u2028"
                       :event-name "event/escaped")
                (assoc base-log :body {"nested" [false nil "λ"]}
                       :attributes {"control" "\u0000\t\r\n" "large" 9223372036854775807})
                (assoc base-log :resource {:attributes {"service.name" ""}}
                       :scope nil :attributes {})]]
    (is (true? (#'exporter/jolt-runtime?)))
    (is (ifn? encoder))
    (doseq [record corpus]
      (is (#'exporter/untyped-log-eligible? record))
      (is (= (utf8 (baseline record)) (utf8 (encoder record)))))
    ;; The admitted route must not construct the legacy full physical row.
    (let [expected (baseline base-log)]
      (with-redefs [exporter/log-row (fn [& _] (throw (ex-info "admitted row materialized" {})))]
        (is (= expected (encoder base-log)))))))

(deftest fallback-and-host-capability-contract
  (let [encoder (#'exporter/compile-untyped-log-encoder)
        fallback (assoc base-log :attributes ["not-an-attribute-map"])]
    (is (not (#'exporter/untyped-log-eligible? fallback)))
    (is (= (utf8 (baseline fallback)) (utf8 (encoder fallback))))
    (with-redefs [exporter/jolt-runtime? (constantly false)]
      (is (nil? (#'exporter/compile-untyped-log-encoder))))))

(deftest attribute-wire-cache-is-batch-local-and-order-safe
  (let [encoder (#'exporter/compile-untyped-log-encoder)
        same-resource {"service.name" "cache-test" "region" "us-east-2"}
        same-scope {"library" "cache-test"}
        same-log {"enabled" true "answer" 42}
        ordered-a (array-map "first" "a" "second" "b")
        ordered-b (array-map "second" "b" "first" "a")
        encode #(#'exporter/untyped-log-payload encoder %)
        baseline-payload #(apply str (map (fn [record] (str (baseline record) "\n")) %))]
    (is (ifn? encoder))
    ;; All three source maps are reused in the second record, so data.json
    ;; should render each only once.  The expected bytes are produced before
    ;; instrumentation, from the maintained generic physical-row path.
    (let [records [(assoc base-log :resource {:attributes same-resource}
                                 :scope {:attributes same-scope}
                                 :attributes same-log)
                   (assoc base-log :body "same maps, different row"
                                 :resource {:attributes same-resource}
                                 :scope {:attributes same-scope}
                                 :attributes same-log)]
          expected (baseline-payload records)
          calls (atom 0)
          original @#'exporter/attrs]
      (with-redefs [exporter/attrs (fn [source]
                                     (swap! calls inc)
                                     (original source))]
        (is (= expected (encode records)))
        (is (= 3 @calls))
        ;; The binding is freshly allocated for every payload; no values or
        ;; cache entries survive an export call.
        (is (= expected (encode records)))
        (is (= 6 @calls))))
    ;; Equal associative maps may have different input iteration order.  They
    ;; are distinct JSON wires and therefore intentionally consume distinct
    ;; cache entries; shared resource/scope wires are still reused.
    (let [records [(assoc base-log :attributes ordered-a)
                   (assoc base-log :body "ordered-b" :attributes ordered-b)]
          expected (baseline-payload records)
          calls (atom 0)
          original @#'exporter/attrs]
      (is (not= (json/write-str (original ordered-a))
                (json/write-str (original ordered-b))))
      (with-redefs [exporter/attrs (fn [source]
                                     (swap! calls inc)
                                     (original source))]
        (is (= expected (encode records)))
        ;; resource + scope once, then each ordered LogAttributes source once.
        (is (= 4 @calls))))))

(deftest durable-route-retains-exact-public-payload
  (let [encoder (#'exporter/compile-untyped-log-encoder)
        target (exporter/->ChdbExporter
                :writer false #{:logs}
                (atom {:closed-signals #{} :durable? true
                       :typed-log-projector nil
                       :log-insert-columns
                       ["Timestamp" "TraceId" "SpanId" "TraceFlags" "SeverityText"
                        "SeverityNumber" "ServiceName" "Body" "ResourceSchemaUrl"
                        "ResourceAttributes" "ScopeSchemaUrl" "ScopeName" "ScopeVersion"
                        "ScopeAttributes" "LogAttributes" "EventName"]}))
        calls (atom [])
        records [base-log (assoc base-log :body "unicode-λ😀")]
        expected (str "insert into otel_logs (Timestamp, TraceId, SpanId, TraceFlags, SeverityText, SeverityNumber, ServiceName, Body, ResourceSchemaUrl, ResourceAttributes, ScopeSchemaUrl, ScopeName, ScopeVersion, ScopeAttributes, LogAttributes, EventName) FORMAT JSONEachRow\n"
                      (apply str (map #(str (baseline %) "\n") records)))]
    (is (ifn? encoder))
    (with-redefs [durable/execute-and-flush! (fn [_ sql]
                                               (swap! calls conj sql) {:status :committed})]
      (is (true? (logs/export-logs! target records))))
    (is (= [(utf8 expected)] (mapv utf8 @calls)))))

(deftest falsey-records-retain-generic-rows-and-durable-route
  ;; A raw caller can pass nil or false even though normal SDK records are
  ;; maps. The generic path materializes their default log rows; the fast path
  ;; must neither terminate early nor acknowledge a partial Durable batch.
  (let [encoder (#'exporter/compile-untyped-log-encoder)
        target (exporter/->ChdbExporter
                :writer false #{:logs}
                (atom {:closed-signals #{} :durable? true
                       :typed-log-projector nil
                       :log-insert-columns
                       ["Timestamp" "TraceId" "SpanId" "TraceFlags" "SeverityText"
                        "SeverityNumber" "ServiceName" "Body" "ResourceSchemaUrl"
                        "ResourceAttributes" "ScopeSchemaUrl" "ScopeName" "ScopeVersion"
                        "ScopeAttributes" "LogAttributes" "EventName"]}))
        query "insert into otel_logs (Timestamp, TraceId, SpanId, TraceFlags, SeverityText, SeverityNumber, ServiceName, Body, ResourceSchemaUrl, ResourceAttributes, ScopeSchemaUrl, ScopeName, ScopeVersion, ScopeAttributes, LogAttributes, EventName) FORMAT JSONEachRow\n"
        calls (atom [])]
    (is (ifn? encoder))
    (doseq [records [[nil base-log] [false base-log]]]
      (let [generic (apply str (map #(str (baseline %) "\n") records))
            direct (#'exporter/untyped-log-payload encoder records)]
        (is (= (var-get #'exporter/generic-untyped-log-payload) direct))
        (reset! calls [])
        (with-redefs [durable/execute-and-flush! (fn [_ sql]
                                                   (swap! calls conj sql) {:status :committed})]
          (is (true? (logs/export-logs! target records))))
        (is (= [(utf8 (str query generic))] (mapv utf8 @calls)))))))

(deftest noneligible-record-keeps-legacy-durable-json
  ;; This shape is not eligible for the fixed writer. Durable historically
  ;; lets data.json render the keyword through log-row; unlike ordinary
  ;; transport, it must not gain an early physical-row validation failure.
  (let [target (exporter/->ChdbExporter
                :writer false #{:logs}
                (atom {:closed-signals #{} :durable? true
                       :typed-log-projector nil
                       :log-insert-columns
                       ["Timestamp" "TraceId" "SpanId" "TraceFlags" "SeverityText"
                        "SeverityNumber" "ServiceName" "Body" "ResourceSchemaUrl"
                        "ResourceAttributes" "ScopeSchemaUrl" "ScopeName" "ScopeVersion"
                        "ScopeAttributes" "LogAttributes" "EventName"]}))
        record (assoc base-log :resource {:schema-url :legacy-keyword
                                          :attributes {"service.name" "fallback"}})
        query "insert into otel_logs (Timestamp, TraceId, SpanId, TraceFlags, SeverityText, SeverityNumber, ServiceName, Body, ResourceSchemaUrl, ResourceAttributes, ScopeSchemaUrl, ScopeName, ScopeVersion, ScopeAttributes, LogAttributes, EventName) FORMAT JSONEachRow\n"
        expected (str query (baseline record) "\n")
        calls (atom [])]
    (is (not (#'exporter/untyped-log-eligible? record)))
    (with-redefs [durable/execute-and-flush! (fn [_ sql]
                                               (swap! calls conj sql) {:status :committed})]
      (is (true? (logs/export-logs! target [record]))))
    (is (= [(utf8 expected)] (mapv utf8 @calls)))))

(deftest noneligible-record-keeps-ordinary-validation
  ;; The same non-eligible value previously reaches ordinary-payload, where
  ;; the transport boundary rejects it before calling the driver.
  (let [target (exporter/->ChdbExporter
                :writer false #{:logs}
                (atom {:closed-signals #{} :durable? false
                       :typed-log-projector nil
                       :log-insert-columns
                       ["Timestamp" "TraceId" "SpanId" "TraceFlags" "SeverityText"
                        "SeverityNumber" "ServiceName" "Body" "ResourceSchemaUrl"
                        "ResourceAttributes" "ScopeSchemaUrl" "ScopeName" "ScopeVersion"
                        "ScopeAttributes" "LogAttributes" "EventName"]}))
        record (assoc base-log :resource {:schema-url :legacy-keyword
                                          :attributes {"service.name" "fallback"}})
        calls (atom [])]
    (with-redefs [chdb/insert-json-rows! (fn [& arguments] (swap! calls conj arguments))]
      (is (false? (logs/export-logs! target [record]))))
    (is (empty? @calls))
    (is (= ::exporter/invalid-ordinary-row
           (:type (ex-data (exporter/last-error target)))))))

(deftest noncanonical-log-columns-retain-ordinary-validation
  ;; The record is eligible for the fixed encoder, but this constructed state
  ;; is not its schema. The legacy ordinary path must reject before the driver
  ;; rather than submitting fixed JSON under the unexpected column list.
  (let [target (exporter/->ChdbExporter
                :writer false #{:logs}
                (atom {:closed-signals #{} :durable? false
                       :typed-log-projector nil
                       :log-insert-columns ["Unexpected"]}))
        calls (atom [])]
    (with-redefs [chdb/insert-json-rows! (fn [& arguments] (swap! calls conj arguments))]
      (is (false? (logs/export-logs! target [base-log]))))
    (is (empty? @calls))
    (is (= ::exporter/invalid-ordinary-row
           (:type (ex-data (exporter/last-error target)))))))

(defn -main []
  (let [result (run-tests 'otel.exporter.chdb-untyped-log-encoder-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
