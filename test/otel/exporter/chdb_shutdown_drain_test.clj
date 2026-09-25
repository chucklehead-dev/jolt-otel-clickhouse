(ns otel.exporter.chdb-shutdown-drain-test
  (:require [clojure.test :refer [deftest is testing]]
            [jdbc.chdb.durable :as durable]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb-test-support :as support]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(def ^:private sample-span
  {:name "shutdown-race" :kind :internal
   :start-time-unix-nano 1 :end-time-unix-nano 2
   :span-context {:trace-id "11111111111111111111111111111111"
                  :span-id "2222222222222222"}
   :resource {:attributes {}} :scope {:name "shutdown-test"}
   :attributes {} :events [] :links [] :status {:code :unset}})

(defn- await! [value]
  (let [result (deref value 2000 ::timeout)]
    (is (not= ::timeout result) "a causal test worker must terminate")
    result))

(defn- fixture [close! owned? signals]
  (let [state (atom (support/exporter-state
                     {:closed-signals #{} :in-flight 0
                      :connection-close-claimed? false
                      :connection-close-status :open
                      :connection-closed? false
                      :durable? true :last-error nil}))]
    {:state state
     :exporter (chdb-export/->ChdbExporter {:close (fn [_] (close!))}
                                           owned? signals state)}))

(deftest admitted-durable-export-drains-before-owned-close
  (let [entered (promise)
        release (promise)
        fenced (promise)
        native-active? (atom false)
        closes (atom 0)
        {:keys [state exporter]}
        (fixture (fn []
                   (is (false? @native-active?)
                       "owned connection cannot close during admitted native work")
                   (swap! closes inc)) true #{:spans})]
    (add-watch state ::fence
               (fn [_ _ _ new]
                 (when (contains? (:closed-signals new) :spans)
                   (deliver fenced true))))
    (with-redefs [durable/execute-and-flush!
                  (fn [_ _]
                    (reset! native-active? true)
                    (deliver entered true)
                    @release
                    (reset! native-active? false)
                    {:status :committed})]
      (let [writer (future (export/export-spans! exporter [sample-span]))]
        (try
          (is (true? (await! entered)))
          (is (= 1 (:in-flight @state)))
          (let [shutdown (future (export/shutdown-exporter! exporter))]
            (try
              (is (true? (await! fenced)))
              (is (= 0 @closes))
              (is (= ::pending (deref shutdown 10 ::pending)))
              (is (false? (export/export-spans! exporter [sample-span]))
                  "new exports must reject after the fence")
              (is (false? (export/flush-exporter! exporter))
                  "span flushes share the admission fence")
              (is (= 1 (:in-flight @state)))
              (deliver release true)
              (is (true? (await! writer)) "admitted call keeps its confirmed ACK")
              (is (true? (await! shutdown)))
              (is (= 1 @closes))
              (is (= 0 (:in-flight @state)))
              (is (= :closed (:connection-close-status @state)))
              (is (true? (export/shutdown-exporter! exporter)))
              (is (= 1 @closes) "repeated shutdown never closes again")
              (finally (deliver release true))))
          (finally (deliver release true)))))))

(deftest failed-close-is-terminal-after-admitted-export
  (let [entered (promise) release (promise) fenced (promise)
        failure (ex-info "synthetic close failure" {:type ::close-failure})
        closes (atom 0)
        {:keys [state exporter]}
        (fixture (fn [] (swap! closes inc) (throw failure)) true #{:spans})]
    (add-watch state ::fence
               (fn [_ _ _ new]
                 (when (contains? (:closed-signals new) :spans)
                   (deliver fenced true))))
    (with-redefs [durable/execute-and-flush!
                  (fn [_ _] (deliver entered true) @release {:status :committed})]
      (let [writer (future (export/export-spans! exporter [sample-span]))]
        (try
          (is (true? (await! entered)))
          (let [shutdown (future (export/shutdown-exporter! exporter))]
            (is (true? (await! fenced)))
            (is (= 0 @closes))
            (deliver release true)
            (is (true? (await! writer)))
            (is (false? (await! shutdown)))
            (is (false? (export/shutdown-exporter! exporter)))
            (is (= 1 @closes))
            (is (= :failed (:connection-close-status @state)))
            (is (identical? failure (chdb-export/last-error exporter))))
          (finally (deliver release true)))))))

(deftest interrupted-shutdown-waiter-does-not-abandon-close
  (let [entered (promise) release (promise) fenced (promise)
        closes (atom 0)
        {:keys [state exporter]}
        (fixture #(swap! closes inc) true #{:spans})]
    (add-watch state ::fence
               (fn [_ _ _ new]
                 (when (contains? (:closed-signals new) :spans)
                   (deliver fenced true))))
    (with-redefs [durable/execute-and-flush!
                  (fn [_ _] (deliver entered true) @release {:status :committed})]
      (let [writer (future (export/export-spans! exporter [sample-span]))]
        (try
          (is (true? (await! entered)))
          (let [shutdown (future
                           (.interrupt (Thread/currentThread))
                           (try
                             (let [result (export/shutdown-exporter! exporter)]
                               [result (Thread/interrupted)])
                             (finally (Thread/interrupted))))]
            (is (true? (await! fenced)))
            (is (= [false true] (await! shutdown))
                "interrupted waiter reports failure and retains its interrupt flag")
            (is (= 0 @closes))
            (deliver release true)
            (is (true? (await! writer)))
            (is (true? (export/shutdown-exporter! exporter)))
            (is (= 1 @closes))
            (is (= :closed (:connection-close-status @state))))
          (finally (deliver release true)))))))

(deftest interrupted-waiter-on-terminal-close-result-preserves-flag
  ;; The first signal's drain is already complete. This waiter reaches the
  ;; *second* promise (the owned connection's close result), not only the
  ;; per-signal drain promise tested above.
  (let [close-entered (promise) release-close (promise)
        closes (atom 0)
        {:keys [state exporter]}
        (fixture (fn []
                   (swap! closes inc)
                   (deliver close-entered true)
                   @release-close) true #{:spans :logs})]
    (is (true? (export/shutdown-exporter! exporter)))
    (let [owner (future (logs/shutdown-log-exporter! exporter))]
      (try
        (is (true? (await! close-entered)))
        (let [waiter (future
                       (.interrupt (Thread/currentThread))
                       (try
                         (let [result (logs/shutdown-log-exporter! exporter)]
                           [result (Thread/interrupted)])
                         (finally (Thread/interrupted))))]
          (is (= [false true] (await! waiter)))
          (is (= 1 @closes))
          (is (= :closing (:connection-close-status @state)))
          (deliver release-close true)
          (is (true? (await! owner)))
          (is (true? (logs/shutdown-log-exporter! exporter)))
          (is (= 1 @closes)))
        (finally (deliver release-close true))))))

(deftest racing-shutdown-observes-terminal-close-failure-without-retry
  (let [close-entered (promise) release-close (promise)
        racer-crossed-state (promise)
        failure (ex-info "synthetic close failure" {:type ::racing-close-failure})
        closes (atom 0)
        {:keys [state exporter]}
        (fixture (fn []
                   (swap! closes inc)
                   (deliver close-entered true)
                   @release-close
                   (throw failure)) true #{:spans :logs})]
    (is (true? (export/shutdown-exporter! exporter)))
    (let [owner (future (logs/shutdown-log-exporter! exporter))]
      (try
        (is (true? (await! close-entered)))
        ;; Jolt atom watches fire even for the repeated close-signal! swap
        ;; that leaves state unchanged. The owner is blocked in .close, so
        ;; this next state transition is the racing shutdown itself.
        (add-watch state ::racer-crossed-state
                   (fn [_ _ _ _] (deliver racer-crossed-state true)))
        (let [racer (future (logs/shutdown-log-exporter! exporter))]
          (is (true? (await! racer-crossed-state)))
          (is (= ::pending (deref racer 10 ::pending)))
          (is (= 1 @closes))
          (deliver release-close true)
          (is (false? (await! owner)))
          (is (false? (await! racer)))
          (is (false? (logs/shutdown-log-exporter! exporter)))
          (is (= 1 @closes))
          (is (= :failed (:connection-close-status @state)))
          (is (identical? failure (chdb-export/last-error exporter))))
        (finally
          (remove-watch state ::racer-crossed-state)
          (deliver release-close true))))))

(deftest nonterminal-and-shared-signal-shutdown-drain-admitted-calls
  (doseq [owned? [true false]]
    (let [entered (promise) release (promise) fenced (promise)
          closes (atom 0)
          {:keys [state exporter]}
          (fixture #(swap! closes inc) owned? #{:spans :logs})]
      (add-watch state ::fence
                 (fn [_ _ _ new]
                   (when (contains? (:closed-signals new) :spans)
                     (deliver fenced true))))
      (with-redefs [durable/execute-and-flush!
                    (fn [_ _] (deliver entered true) @release {:status :committed})]
        (let [writer (future (export/export-spans! exporter [sample-span]))]
          (try
            (is (true? (await! entered)))
            (let [shutdown (future (export/shutdown-exporter! exporter))]
              (is (true? (await! fenced)))
              (is (= ::pending (deref shutdown 10 ::pending))
                  "signal shutdown waits even without a final owned close")
              (is (= 0 @closes))
              (deliver release true)
              (is (true? (await! writer)))
              (is (true? (await! shutdown)))
              (is (= 0 @closes))
              (is (true? (logs/shutdown-log-exporter! exporter)))
              (is (= (if owned? 1 0) @closes)))
            (finally (deliver release true))))))))

(deftest shared-connection-is-never-closed
  (let [closes (atom 0)
        {:keys [exporter]}
        (fixture #(swap! closes inc) false #{:spans :logs})]
    (is (true? (export/shutdown-exporter! exporter)))
    (is (true? (logs/shutdown-log-exporter! exporter)))
    (is (false? (export/export-spans! exporter [sample-span])))
    (is (= 0 @closes))))
