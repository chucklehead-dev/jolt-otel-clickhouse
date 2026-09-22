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
(def ^:private insert-byte-limit (* 8 1024 1024))

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

(defn- log-target [durable?]
  (exporter/->ChdbExporter
   :writer false #{:logs}
   (atom {:closed-signals #{} :durable? durable?
          :typed-log-projector nil
          :log-insert-columns
          ["Timestamp" "TraceId" "SpanId" "TraceFlags" "SeverityText"
           "SeverityNumber" "ServiceName" "Body" "ResourceSchemaUrl"
           "ResourceAttributes" "ScopeSchemaUrl" "ScopeName" "ScopeVersion"
           "ScopeAttributes" "LogAttributes" "EventName"]})))

(defn- generated-parity-records []
  ;; Deterministic rather than randomized: failures name an exact source shape
  ;; and this corpus runs the same bytes on every host. The values cover the
  ;; conversion boundary (`value-string`/`attrs`) as well as JSON escaping.
  (vec
   (mapcat
    (fn [n]
      (let [edge (nth [0 1 -1 9223372036854775807 -9223372036854775808] (mod n 5))
            timestamp-edge (nth [0 1 1700000000000000000 9223372036854775807] (mod n 4))
            control (str "record-" n "-quote-\"-slash/-backslash\\-tab\t-newline\n-nul"
                         (char 0) "-unicode-λ-😀-" (char 0x2028))
            attrs (into (array-map)
                        [["false" false] ["nil" nil] ["zero" 0]
                         ["edge" edge] ["nested" {"n" [n false nil "λ"]}]
                         [:keyword-key (str "keyword-" n)]])
            resource (if (zero? (mod n 3))
                       nil
                       {:schema-url (if (even? n) "resource/β" "")
                        :attributes (into (array-map)
                                         [["service.name" (str "generated-" n)]
                                          ["resource.flag" (odd? n)]
                                          ["resource.nested" [nil false edge]]])})
            scope (if (zero? (mod n 4))
                    nil
                    {:schema-url (if (odd? n) "scope/λ" "")
                     :name (str "scope-" n) :version (str "v" n)
                     :attributes {"scope.zero" 0 "scope.nested" {"n" n}}})]
        [(assoc base-log
                :timestamp-unix-nano (if (zero? (mod n 6)) 0 timestamp-edge)
                :observed-time-unix-nano (if (zero? (mod n 6)) 0 1700000000000000001)
                :trace-flags edge :severity-number edge
                :severity-text (if (even? n) control nil)
                :body (case (mod n 5)
                        0 nil
                        1 false
                        2 0
                        3 {"body" [false nil edge control]}
                        control)
                :event-name (if (zero? (mod n 3)) "" control)
                :attributes attrs :resource resource :scope scope)
         ;; Same logical fields, deliberately inserted in a different map order
         ;; to keep the public data.json ordering path as the byte oracle.
         (into (array-map)
               (reverse (seq (assoc base-log :attributes attrs :resource resource :scope scope
                                    :body control :event-name (str "event-" n)))))]))
    (range 12))))

(defn- exact-limit-record [encoder]
  ;; Start from ASCII's one-byte rule, then derive the final adjustment from
  ;; actual UTF-8 output. This intentionally does not duplicate any key or
  ;; framing literal, and remains valid if the runtime represents a very large
  ;; flat string in chunks whose allocation bookkeeping differs from `count`.
  (let [empty-row (assoc base-log :body "")
        fixed-bytes (inc (alength (.getBytes (encoder empty-row) "UTF-8")))
        body-bytes (- insert-byte-limit fixed-bytes)
        candidate (assoc base-log :body (apply str (repeat body-bytes "a")))
        wire-bytes (inc (alength (.getBytes (encoder candidate) "UTF-8")))
        adjustment (- wire-bytes insert-byte-limit)]
    (when (neg? adjustment)
      (throw (ex-info "boundary fixture unexpectedly underfilled"
                      {:limit insert-byte-limit :wire-bytes wire-bytes})))
    (update candidate :body #(subs % 0 (- (count %) adjustment)))))

(defn- with-log-encoder [encoder thunk]
  ;; This is an observational seam only: production retains its delayed,
  ;; schema-fenced compiler-selected encoder. It lets the route tests prove
  ;; that a rejected row never requests construction of a later raw record.
  (with-redefs-fn {#'exporter/untyped-log-encoder (delay encoder)} thunk))

(deftest exact-utf8-pathological-corpus
  (let [encoder (#'exporter/compile-untyped-log-encoder)
        corpus (into [base-log
                      (assoc base-log :timestamp-unix-nano 0 :body nil
                             :severity-text "quote-\" slash/ backslash\\ newline\nnull\u0000")
                      (assoc base-log :body "unicode-λ-é-😀-\u2028"
                             :event-name "event/escaped")
                      (assoc base-log :body {"nested" [false nil "λ"]}
                             :attributes {"control" "\u0000\t\r\n" "large" 9223372036854775807})
                      (assoc base-log :resource {:attributes {"service.name" ""}}
                             :scope nil :attributes {})]
                     (generated-parity-records))]
    (is (true? (#'exporter/jolt-runtime?)))
    (is (ifn? encoder))
    (doseq [record corpus]
      (is (#'exporter/untyped-log-eligible? record))
      (is (= (utf8 (baseline record)) (utf8 (encoder record)))))
    ;; The admitted route must not construct the legacy full physical row.
    (let [expected (baseline base-log)]
      (with-redefs [exporter/log-row (fn [& _] (throw (ex-info "admitted row materialized" {})))]
        (is (= expected (encoder base-log)))))))

(deftest direct-log-8mib-boundary-preserves-route-and-construction-order
  (let [direct (#'exporter/compile-untyped-log-encoder)
        exact (exact-limit-record direct)
        overflow (assoc exact :body (str (:body exact) "x"))
        later (assoc base-log :body "must-not-be-constructed")
        payload (#'exporter/untyped-log-payload direct [exact])]
    (is (= insert-byte-limit (alength (.getBytes payload "UTF-8"))))
    ;; Check the low-level direct writer's strict sequencing first. Public
    ;; ordinary/Durable route checks below install this same observation.
    (let [seen (atom [])
          observed (fn [record]
                     (swap! seen conj (:body record))
                     (direct record))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds 8 MiB"
                            (#'exporter/untyped-log-payload observed [exact overflow later])))
      (is (= [(:body exact) (:body overflow)] @seen)))
    (doseq [durable? [false true]]
      (let [target (log-target durable?)
            writes (atom [])
            seen (atom [])
            observed (fn [record]
                       (swap! seen conj (:body record))
                       (direct record))]
        ;; Exact payloads remain on the direct route for both transport modes.
        (if durable?
          (with-redefs [durable/execute-and-flush!
                        (fn [_ sql] (swap! writes conj sql) {:status :committed})]
            (is (true? (logs/export-logs! target [exact]))))
          (with-redefs [chdb/insert-json-rows!
                        (fn [& arguments] (swap! writes conj arguments))]
            (is (true? (logs/export-logs! target [exact])))))
        (is (= 1 (count @writes)))
        (reset! writes [])
        ;; Overflow cannot enter either driver boundary, and the next raw
        ;; record remains unobserved. This is particularly important for
        ;; Durable: it prevents a partial SQL/WAL candidate from being built.
        (with-log-encoder observed
          #(if durable?
             (with-redefs [durable/execute-and-flush!
                           (fn [_ sql] (swap! writes conj sql) {:status :committed})]
               (is (false? (logs/export-logs! target [exact overflow later]))))
             (with-redefs [chdb/insert-json-rows!
                           (fn [& arguments] (swap! writes conj arguments))]
               (is (false? (logs/export-logs! target [exact overflow later]))))))
        (is (empty? @writes))
        (is (= [(:body exact) (:body overflow)] @seen))))))

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
