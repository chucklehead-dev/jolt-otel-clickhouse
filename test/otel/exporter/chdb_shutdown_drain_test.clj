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
                             (export/shutdown-exporter! exporter)
                             (finally (Thread/interrupted))))]
            (is (true? (await! fenced)))
            (is (false? (await! shutdown))
                "interrupted waiter reports failure without canceling close")
            (is (= 0 @closes))
            (deliver release true)
            (is (true? (await! writer)))
            (is (true? (export/shutdown-exporter! exporter)))
            (is (= 1 @closes))
            (is (= :closed (:connection-close-status @state))))
          (finally (deliver release true)))))))

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
