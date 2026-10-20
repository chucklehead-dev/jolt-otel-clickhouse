(ns otel.exporter.chdb-record-context-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [jolt.codec.experimental :as codec]
            [otel.trace :as trace]
            [otel.otlp.trace-decode :as decode]
            [otel.exporter.chdb :as e]
            [jdbc.core :as jdbc]))

(deftest real-decoder-link-context-records-retain-configured-json-fields-and-order
  (let [wire {"resourceSpans"
              [{"scopeSpans"
                [{"spans" [{"traceId" "11111111111111111111111111111111"
                            "spanId" "2222222222222222" "name" "linked"
                            "startTimeUnixNano" "100" "endTimeUnixNano" "200"
                            "links" [{"traceId" "33333333333333333333333333333333"
                                      "spanId" "4444444444444444" "traceState" "a=b,c=d" "flags" 129
                                      "attributes" [{"key" "valid" "value" {"boolValue" false}}]}]}]}]}]}
        span (first (:spans (decode/decode-request wire)))]
    (is (instance? otel.trace.SpanContext (get-in span [:links 0 :span-context])))
    (doseq [nested? [false true]
            span [span (update-in span [:links 0 :span-context] assoc :extension false)]]
      (let [context (#'e/load-experimental-span-codec! true true nested?)
            expected (#'e/span-row span nil true)
            actual (json/read-str
                            (String. ((:encode context) "" [span]
                                       #(#'e/span-row % nil true (:nested context)) 10000) "UTF-8"))]
        (is (= expected actual))
        ;; LinksJSON is exact TEXT equality, not merely reparsed equivalence.
        (is (= (nth expected 23) (nth actual 23)))))))

(deftest incompatible-record-codec-rejects-before-storage
  (let [opens (atom 0)]
    (with-redefs [codec/encode-prefixed-records (fn [& _] (.getBytes "[]\n" "UTF-8"))
                  jdbc/connection (fn [& _] (swap! opens inc))]
      (let [failure (try
                      (e/exporter {:durable? true :signals #{:spans}
                                   :json-backend :native-guarded-byte-batch
                                   :insert-format :json-compact-each-row
                                   :owned-statement-output? true :experimental-span-codec? true})
                      nil (catch Throwable e e))]
        (is (= ::e/experimental-span-codec-unavailable (:type (ex-data failure))))
        (is (zero? @opens))))))
