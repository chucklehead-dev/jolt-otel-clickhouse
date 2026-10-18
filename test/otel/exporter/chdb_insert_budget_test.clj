(ns otel.exporter.chdb-insert-budget-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [db.jdbc-shim :as shim]
            [jdbc.core :as jdbc]
            [jdbc.chdb :as chdb]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-test-support :as support]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; Explicit native invocation must pass, not skip; the aggregate uses configured JSON.
(def ^:dynamic *backend* :configured)

(def span
  {:name "budget" :kind :internal :start-time-unix-nano 1 :end-time-unix-nano 2
   :span-context {:trace-id "11111111111111111111111111111111" :span-id "2222222222222222"}
   :resource {:attributes {}} :scope {:name "test"} :attributes {}
   :events [] :links [] :status {:code :unset}})

(defn- owner [limit]
  (exporter/->ChdbExporter
   :connection false #{:spans :metrics :logs}
   (atom (support/exporter-state
          {:durable? false :closed-signals #{} :max-insert-bytes limit}))))

(defn- limit [] (@#'exporter/insert-byte-limit))

(deftest public-constructor-retains-valid-bounds
  (let [fixture (support/owned-fixture) connection (:connection fixture)]
    (try
      (with-redefs [shim/driver-context (fn [& _] {})]
        (support/call-with-qualified-native
         (fn []
           (doseq [value [nil 1 (* 8 1024 1024) (* 64 1024 1024)]]
             (let [target (exporter/exporter {:connection connection :create-schema? false
                                              :signals #{:spans} :max-insert-bytes value})]
               (is (= value (:max-insert-bytes @(:state target))))
               (is (identical? connection (:connection target)))
               (is (true? (export/shutdown-exporter! target))))))))
      (finally (.close connection)))
    (is (= 1 @(:close-count fixture)))))

(deftest utf8-newline-boundary-excludes-sql-prefix-and-stops-before-next-row
  (let [row {"x" "é😀"} expected (str (json/write-str row) "\n")
        size (alength (.getBytes expected "UTF-8")) effects (atom [])
        prefix "insert into fixture FORMAT JSONEachRow\n"]
    (with-bindings {#'exporter/*insert-byte-limit* size
                    #'exporter/*json-backend* *backend*}
      (is (= expected (#'exporter/json-each-row-payload [row])))
      (is (= (str prefix expected) (#'exporter/json-each-row-payload [row] prefix))))
    (with-bindings {#'exporter/*insert-byte-limit* (dec size)
                    #'exporter/*json-backend* *backend*}
      (let [rows (letfn [(step [n] (lazy-seq (swap! effects conj n)
                                             (cons row (lazy-seq (step (inc n))))))]
                   (step 0))
            error (try (#'exporter/json-each-row-payload rows)
                       nil (catch Throwable e e))]
        (is (= {:limit (dec size)} (ex-data error)))
        (is (= "chDB telemetry export batch exceeds its configured byte limit"
               (ex-message error)))
        (is (= [0] @effects))))))

(deftest explicit-larger-budget-encodes-more-than-eight-mib
  (let [kib (apply str (repeat 1024 "a"))
        value (apply str (repeat (* 9 1024) kib))
        bytes (+ (count value) 3)] ; opening/closing quotes plus newline
    (with-bindings {#'exporter/*json-backend* *backend*
                    #'exporter/*insert-byte-limit* bytes}
      (let [payload (#'exporter/json-each-row-payload [value])]
        (is (= bytes (count payload)))
        (is (= value (json/read-str payload)))))
    (with-bindings {#'exporter/*json-backend* *backend*
                    #'exporter/*insert-byte-limit* nil}
      (is (= {:limit (* 8 1024 1024)}
             (try (#'exporter/json-each-row-payload [value])
                  nil (catch Throwable e (ex-data e))))))))

(deftest invalid-limits-precede-database-and-backend-acquisition
  (let [calls (atom [])]
    (with-redefs [jdbc/connection (fn [& _] (swap! calls conj :open))]
      (doseq [value [0 -1 1.5 true "16777216" {} (inc (* 64 1024 1024))]]
        (let [error (try (exporter/exporter {:max-insert-bytes value :json-backend :unknown})
                         nil (catch Throwable e e))]
          (is (= :otel.exporter.chdb/invalid-insert-byte-limit (:type (ex-data error))))
          (is (= #{:type :minimum :maximum} (set (keys (ex-data error))))))))
    (is (empty? @calls))))

(deftest every-signal-has-its-own-call-bound-and-default-remains-eight-mib
  (let [seen (atom []) target (owner 4096)]
    (with-redefs [chdb/insert-json-rows! (fn [& _] (swap! seen conj (limit)) {:count 1})]
      (is (export/export-spans! target [span]))
      (is (logs/export-logs! target [{:body "log" :timestamp-unix-nano 1}]))
      (is (export/export-metrics!
           target {} [{:scope {:name "test"}
                       :metrics [{:name "gauge" :type :gauge
                                  :data-points [{:time-unix-nano 1 :value 3 :attributes {}}]}]}]))
      (is (= [4096 4096 4096] @seen))
      (reset! seen [])
      (is (export/export-spans! (owner nil) [span]))
      (is (= [(* 8 1024 1024)] @seen)))
    (is (= (* 8 1024 1024) (limit)))))

(deftest oversized-insert-does-not-mutate-and-other-owner-can-accept-it
  (let [calls (atom 0) small (owner 16) large (owner 4096)]
    (with-redefs [chdb/insert-json-rows! (fn [& _] (swap! calls inc) {:count 1})]
      (is (false? (export/export-spans! small [span])))
      (is (= {:limit 16} (ex-data (exporter/last-error small))))
      (is (zero? @calls))
      (is (true? (export/export-spans! large [span])))
      (is (= 1 @calls)))))

(deftest nested-export-restores-outer-limit-after-inner-overflow
  (let [outer (owner 4096) inner (owner 16) seen (atom []) nested? (atom false)
        original @#'exporter/json-each-row-payload]
    (with-redefs [chdb/insert-json-rows! (fn [& _] {:count 1})]
      (with-redefs-fn
        {#'exporter/json-each-row-payload
         (fn [rows]
           (swap! seen conj (limit))
           (when (compare-and-set! nested? false true)
             (is (false? (export/export-spans! inner [span])))
             (swap! seen conj (limit)))
           (original rows))}
        (fn [] (is (true? (export/export-spans! outer [span]))))))
    (is (= [4096 16 4096] @seen))
    (is (= (* 8 1024 1024) (limit)))))

(deftest concurrent-exporters-do-not-share-limits
  (let [ready (CountDownLatch. 2) go (CountDownLatch. 1)
        seen (atom {}) results (atom []) original @#'exporter/json-each-row-payload
        targets [(owner 2048) (owner 4096)]]
    (with-redefs [chdb/insert-json-rows! (fn [& _] {:count 1})]
      (with-redefs-fn
        {#'exporter/json-each-row-payload
         (fn [rows]
           (let [before (limit)]
             (.countDown ready)
             (when-not (.await go 5 TimeUnit/SECONDS)
               (throw (ex-info "Budget fixture coordination timed out" {})))
             (swap! seen assoc before (limit))
             (original rows)))}
        (fn []
          (let [threads (mapv (fn [target]
                                (Thread. (fn [] (swap! results conj (export/export-spans! target [span])))))
                              targets)]
            (doseq [thread threads] (.start thread))
            (try (is (.await ready 5 TimeUnit/SECONDS))
                 (finally (.countDown go)))
            (doseq [thread threads] (.join thread))))))
    (is (= [true true] @results))
    (is (= {2048 2048 4096 4096} @seen))
    (is (= (* 8 1024 1024) (limit)))))

(defn run-checks! []
  (let [result (run-tests 'otel.exporter.chdb-insert-budget-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (throw (ex-info "Insert byte-budget controls failed" result)))
    result))

(defn -main [& [backend]]
  (binding [*backend* (if backend (keyword backend) :configured)]
    (run-checks!)))
