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

(deftest direct-batch-log-renderer-preserves-utf8-and-whole-batch-fallback
  ;; The batch renderer is deliberately narrower than the row renderer. It
  ;; retains data.json for attribute values but does not create a final String
  ;; for each physical row merely to count it. This checks its incremental
  ;; count against the actual final UTF-8 wire, including data.json-owned
  ;; Unicode/control escaping in the three attribute maps.
  (let [batch (#'exporter/compile-untyped-log-batch-encoder)
        records [(assoc base-log
                        :body "ASCII direct body"
                        :attributes {"unicode" "λ😀" "control" "\u0000\t\n"}
                        :resource {:attributes {"service.name" "service" "emoji" "😀"}}
                        :scope {:attributes {"scope" "β/\\\""}})
                 (assoc base-log :body "another ASCII body"
                        :attributes {"ordered" "second"})]
        expected (apply str (map #(str (baseline %) "\n") records))]
    (is (ifn? batch))
    (is (= (utf8 expected) (utf8 (batch records))))
    ;; These scalar values need data.json's escape/structured authority. A
    ;; single one discards the partial request-local builder and restarts the
    ;; *entire* batch through the historical generic row path.
    (doseq [ineligible [(assoc base-log :body "unicode-λ")
                        (assoc base-log :body {"nested" [false nil "λ"]})]]
      (is (= (var-get #'exporter/generic-untyped-log-payload)
             (batch [base-log ineligible]))))))

(deftest direct-batch-log-renderer-stops-before-later-record-after-overflow
  (let [batch (#'exporter/compile-untyped-log-batch-encoder)
        exact (assoc base-log :body "direct-boundary-one")
        overflow (assoc base-log :body "direct-boundary-two")
        limit (alength (.getBytes (batch [exact]) "UTF-8"))
        later (lazy-seq (throw (ex-info "later raw record was observed" {})))]
    (is (ifn? batch))
    (is (= limit (alength (.getBytes (batch [exact] limit) "UTF-8"))))
    ;; `batch` may construct the overflowing record, but it must reject before
    ;; requesting the next lazy record and before any driver/WAL call.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"exceeds 8 MiB"
                          (batch (concat [exact overflow] later) limit)))))

(deftest direct-batch-log-renderer-reaches-the-existing-durable-boundary
  (let [row (#'exporter/compile-untyped-log-encoder)
        batch (#'exporter/compile-untyped-log-batch-encoder)
        target (log-target true)
        records [base-log (assoc base-log :body "second ASCII direct body")]
        calls (atom [])]
    ;; Compile the schema fences before replacing log-row. Thereafter a direct
    ;; success may not materialize a physical row map, but still uses the same
    ;; execute-and-flush ownership boundary and exact SQL bytes.
    (with-redefs [exporter/untyped-log-encoder (delay row)
                  exporter/untyped-log-batch-encoder (delay batch)
                  exporter/log-row (fn [& _] (throw (ex-info "direct batch materialized row" {})))
                  durable/execute-and-flush! (fn [_ sql]
                                               (swap! calls conj sql)
                                               {:status :committed})]
      (is (true? (logs/export-logs! target records))))
    (is (= 1 (count @calls)))
    (is (= (utf8 (str "insert into otel_logs (Timestamp, TraceId, SpanId, TraceFlags, SeverityText, SeverityNumber, ServiceName, Body, ResourceSchemaUrl, ResourceAttributes, ScopeSchemaUrl, ScopeName, ScopeVersion, ScopeAttributes, LogAttributes, EventName) FORMAT JSONEachRow\n"
                       (apply str (map #(str (baseline %) "\n") records))))
           (utf8 (first @calls))))))

(deftest large-log-8mib-boundary-keeps-exact-payload-and-no-driver-overflow
  (let [direct (#'exporter/compile-untyped-log-encoder)
        exact (exact-limit-record direct)
        overflow (assoc exact :body (str (:body exact) "x"))
        payload (#'exporter/untyped-log-payload direct [exact])]
    (is (= insert-byte-limit (alength (.getBytes payload "UTF-8"))))
    ;; A giant SDK body may choose the generic batch route after the OTel
    ;; canonicalizer renders it with quotes. The row writer's old bound still
    ;; has a strict no-later-record rule, while public routes below only claim
    ;; exact payload and no driver effect on overflow.
    (let [seen (atom [])
          observed (fn [record]
                     (swap! seen conj (count (:body record)))
                     (direct record))
          later (assoc base-log :body "must-not-be-constructed")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds 8 MiB"
                            (#'exporter/untyped-log-payload observed [exact overflow later])))
      (is (= [(count (:body exact)) (count (:body overflow))] @seen)))
    (doseq [durable? [false true]]
      (let [target (log-target durable?)
            writes (atom [])]
        ;; Both transports accept the exact boundary, whether the conservative
        ;; batch admission chooses direct or generic encoding.
        (if durable?
          (with-redefs [durable/execute-and-flush!
                        (fn [_ sql] (swap! writes conj sql) {:status :committed})]
            (is (true? (logs/export-logs! target [exact]))))
          (with-redefs [chdb/insert-json-rows!
                        (fn [& arguments] (swap! writes conj arguments))]
            (is (true? (logs/export-logs! target [exact])))))
        (is (= 1 (count @writes)))
        (reset! writes [])
        ;; Overflow cannot enter either driver boundary or WAL.
        (if durable?
          (with-redefs [durable/execute-and-flush!
                        (fn [_ sql] (swap! writes conj sql) {:status :committed})]
            (is (false? (logs/export-logs! target [overflow]))))
          (with-redefs [chdb/insert-json-rows!
                        (fn [& arguments] (swap! writes conj arguments))]
            (is (false? (logs/export-logs! target [overflow])))))
        (is (empty? @writes))
        (is (= "chDB telemetry export batch exceeds 8 MiB"
               (some-> (exporter/last-error target) ex-message)))))))

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

(deftest typed-log-route-keeps-merged-generic-projector
  (let [projector (fn [record] {"promoted_value" (get-in record [:attributes "answer"])})
        target (exporter/->ChdbExporter
                :writer false #{:logs}
                (atom {:closed-signals #{} :durable? true
                       :typed-log-projector projector
                       :log-insert-columns (conj (:log-insert-columns @(:state (log-target true)))
                                                 "promoted_value")}))
        records [base-log (assoc base-log :body "typed second")]
        expected (str "insert into otel_logs FORMAT JSONEachRow\n"
                      (apply str (map #(str (json/write-str (#'exporter/log-row % projector)) "\n")
                                      records)))
        calls (atom [])]
    ;; A typed log must never force the fixed-schema encoder, even when all raw
    ;; records otherwise satisfy its admission predicate.
    (with-redefs [exporter/untyped-log-encoder (delay (throw (ex-info "typed log selected untyped encoder" {})))
                  durable/execute-and-flush! (fn [_ sql]
                                               (swap! calls conj sql)
                                               {:status :committed})]
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
        records [base-log record]
        expected (str query (baseline base-log) "\n" (baseline record) "\n")
        calls (atom [])]
    (is (not (#'exporter/untyped-log-eligible? record)))
    (with-redefs [durable/execute-and-flush! (fn [_ sql]
                                               (swap! calls conj sql) {:status :committed})]
      (is (true? (logs/export-logs! target records))))
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
