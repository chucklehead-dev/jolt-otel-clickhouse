(ns otel.exporter.chdb-json-backend-test
  (:require [clojure.test :as test :refer [deftest is]]
            [clojure.data.json :as json]
            [jdbc.core :as jdbc]
            [jdbc.chdb.json-each-row :as encoder]
            [jdbc.chdb :as chdb]
            [otel.sdk.export :as export]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-test-support :as support]))

(defn- payload [backend rows]
  (with-bindings {#'exporter/*json-backend* backend}
    (#'exporter/json-each-row-payload rows)))

(deftest backend-rejection-precedes-database-acquisition
  (let [opens (atom 0)
        unavailable (ex-info "Unavailable fixture backend" {:type :fixture})]
    (with-redefs [jdbc/connection (fn [& _] (swap! opens inc))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (exporter/exporter {:json-backend :unknown})))
      (with-redefs [encoder/open-encoder (fn [_] (throw unavailable))]
        (doseq [backend [:native-guarded :native-guarded-string-cache :native-guarded-byte-batch]]
          (is (identical? unavailable
                          (try (exporter/exporter {:json-backend backend})
                               (catch Throwable e e))))))
      (is (zero? @opens)))))

(deftest incompatible-otel-rejects-before-database-acquisition
  (let [resolve @#'clojure.core/ns-resolve opens (atom 0)]
    (with-redefs [clojure.core/ns-resolve
                  (fn [namespace name]
                    (when-not (and (= namespace 'otel.any-value) (= name 'try-scalar-string))
                      (resolve namespace name)))
                  jdbc/connection (fn [& _] (swap! opens inc))]
      (let [error (try (exporter/exporter {}) nil (catch Throwable error error))]
        (is (= :otel.exporter.chdb/incompatible-otel (:type (ex-data error))))
        (is (= "Exporter requires compatible OTel AnyValue scalar support" (ex-message error))))
      (is (zero? @opens)))))

(deftest payload-context-is-per-call-and-always-released
  (let [opened (atom []) closed (atom [])]
    (with-redefs [encoder/open-encoder
                  (fn [options]
                    (let [context (Object.)]
                      (swap! opened conj [context options]) context))
                  encoder/encode-limited-text!
                  (fn [_ rows limit]
                    (is (= (* 8 1024 1024) limit))
                    (if (= rows [:overflow])
                      (throw (ex-info "bounded" {:type :jdbc.chdb.json-each-row/output-limit}))
                      "fixture\n"))
                  encoder/close! (fn [context] (swap! closed conj context))]
      (is (= "fixture\n" (payload :native-guarded [{"x" 1}])))
      (let [error (try (payload :native-guarded [:overflow]) (catch Throwable e e))]
        (is (= "chDB telemetry export batch exceeds 8 MiB" (ex-message error)))
        (is (= {:limit (* 8 1024 1024)} (ex-data error))))
      (is (= (mapv first @opened) @closed))
      (is (not (identical? (ffirst @opened) (first (second @opened)))))
      (is (every? #(= {:parallelism 1 :json-backend :native-guarded} (second %)) @opened)))
    (is (= "{\"x\":1}\n" (payload :configured [{"x" 1}])))))

;; Explicit native qualification: run only with the compiler-bearing source
;; runtime. No database needed; byte parity and serializer effects are real.
(deftest native-payload-parity-effects-and-limit
  (doseq [backend [:native-guarded :native-guarded-string-cache :native-guarded-byte-batch]]
    (let [rows [{"unicode" "é😀" "v" [1 nil false]} {"v" true}]
          reference (payload :configured rows)
          calls (atom [])
          value (fn [n] (reify json/JSONWriter
                          (-write [_ out _]
                            (swap! calls conj n) (.append out "true"))))]
      (is (= reference (payload backend rows)))
      (is (nil? json/*experimental-native-writer*))
      (with-redefs [exporter/max-insert-bytes 5]
        (let [error (try (payload backend [(value 1) (value 2) (value 3)])
                         (catch Throwable e e))]
          (is (= {:limit 5} (ex-data error)))
          (is (= [1 2] @calls))
          (is (= "true\n" (payload backend [true])))))
      (is (nil? json/*experimental-native-writer*)))))

(deftest sdk-selects-backend-without-changing-default
  (doseq [backend [nil :configured :native-guarded :native-guarded-string-cache :native-guarded-byte-batch]]
    (let [options (cond-> {:closed-signals #{} :durable? false}
                    backend (assoc :json-backend backend))
          target (exporter/->ChdbExporter {} false #{:spans}
                                         (atom (support/exporter-state options)))
          selected (atom [])
          original @#'exporter/json-each-row-payload]
      (with-redefs [chdb/insert-json-rows! (fn [& _] nil)
                    exporter/json-each-row-payload
                    (fn [rows]
                      (swap! selected conj (var-get #'exporter/*json-backend*))
                      (original rows))]
        (is (true? (export/export-spans!
                    target [{:name "selection" :start-time-unix-nano 0
                             :end-time-unix-nano 0 :attributes {"x" "é"}}])))
        (is (= [(or backend :configured)] @selected))
        (is (nil? (exporter/last-error target)))))))

(deftest concurrent-native-payloads-have-independent-contexts
  (doseq [backend [:native-guarded :native-guarded-string-cache :native-guarded-byte-batch]]
    (let [started [(promise) (promise)] release (promise)
          workers
          (mapv (fn [index]
                  (future
                    (payload backend
                             [(reify json/JSONWriter
                                (-write [_ out _]
                                  (deliver (nth started index) true)
                                  (when-not (deref release 5000 false)
                                    (throw (ex-info "Test rendezvous expired" {})))
                                  (.append out (str index))))])))
                [0 1])]
      (try
        (doseq [ready started] (is (= true (deref ready 5000 :timeout))))
        (finally (deliver release true)))
      (is (= ["0\n" "1\n"] (mapv #(deref % 5000 :timeout) workers)))
      (is (nil? json/*experimental-native-writer*)))))

(defn -main [& _]
  (let [result (test/run-tests 'otel.exporter.chdb-json-backend-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
