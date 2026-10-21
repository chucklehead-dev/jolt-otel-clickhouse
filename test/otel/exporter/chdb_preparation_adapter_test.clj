(ns otel.exporter.chdb-preparation-adapter-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.owned-statement :as owned]
            [otel.context :as context]
            [otel.exporter.chdb :as e]
            [otel.exporter.chdb.compact-format :as compact]
            [otel.exporter.chdb.preparation :as preparation]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb-test-support :as support]
            [otel.sdk.export :as sdk]))

(def span {:name "preparation/é😀" :kind :server
           :start-time-unix-nano 1790000000000000001 :end-time-unix-nano 1790000000000000017
           :span-context {:trace-id "11111111111111111111111111111111"
                          :span-id "2222222222222222" :trace-flags 1}
           :resource {:attributes {"service.name" "prepared"}}
           :scope {:name "fixed-scope"}
           :attributes {"boolean" false "large" 9007199254740993 "unicode" "é😀"}
           :events [] :links [] :status {:code :ok}})

(defn fixture [owned?]
  (let [{:keys [connection close-count]} (support/owned-fixture)
        columns (into schema/clickstack-trace-insert-columns ["EventsJSON" "LinksJSON"])
        confirmed (with-redefs [jdbc/fetch (fn [& _] (mapv #(hash-map :name % :type "String") columns))]
                    (compact/confirm! connection "otel_traces" columns (zipmap columns (repeat "String"))))
        state (atom (support/exporter-state
                      {:durable? true :json-backend :native-guarded-byte-batch
                       :experimental-span-codec (#'e/load-experimental-span-codec!)
                       :timestamp-wire :raw-ticks :owned-statement-output? true
                       :max-insert-bytes 1048576 :insert-format :json-compact-each-row
                       :closed-signals #{} :in-flight 0 :in-flight-by-signal {}
                       :compact-plans {"otel_traces" confirmed}
                       :persistence-barrier (fn [_] {:status :empty})}))]
    {:exporter (e/->ChdbExporter connection owned? #{:spans} state)
     :state state :closes close-count}))

(deftest concrete-adapter-preserves-sql-and-late-bindings-in-both-ownership-modes
  (doseq [owned? [false true]]
    (let [{:keys [exporter state closes]} (fixture owned?)
          expected (atom nil) actual (atom [])]
      (with-redefs-fn {#'e/execute-durable-sql! (fn [_ sql] (reset! expected (owned/text sql)) {:status :committed})}
        #(is (true? (sdk/export-spans! exporter [span]))))
      (with-redefs [durable/try-submit-owned-and-flush!
                    (fn [_ sql]
                      (is (context/instrumentation-suppressed?))
                      (is (= :native-guarded-byte-batch @#'e/*json-backend*))
                      (is (= :raw-ticks @#'e/*timestamp-wire*))
                      (swap! actual conj (owned/text sql))
                      {:status :admitted :ticket :ticket})
                    durable/await-confirmed! (fn [_] {:status :committed})]
        (let [config (e/experimental-span-batch-options exporter
                       {:max-export-batch-size 1 :schedule-delay-ms 60000})
              adapter (:experimental-span-preparation config)
              cap ((:reserve! adapter) 1)]
          (with-bindings {#'e/*json-backend* :configured #'e/*timestamp-wire* :iso-utc}
            (is (true? ((:prepare! adapter) cap [span])))
            (is (= 1 (:in-flight @state)))
            (is (empty? @actual))
            (is (true? ((:submit! adapter) cap)))
            (is (true? ((:await! adapter) cap)))
            (is (= 1 (:in-flight @state)) "confirmation does not release exporter admission")
            ((:release! adapter) cap) ((:retire! adapter) cap))
          (is (= [@expected] @actual))
          (is (str/includes? (first @actual) "input_format_read_datetime_number_as_raw_value=1"))
          (is (= 0 (:in-flight @state)))
          (is (true? (sdk/shutdown-exporter! exporter)))
          (is (= (if owned? 1 0) @closes)))))))

(deftest actual-sdk-prepares-b-before-confirming-a-through-the-concrete-owner
  (let [{:keys [exporter state]} (fixture false)
        prepared-b (promise) awaiting-a (promise) release-a (promise)
        prepares (atom 0) submissions (atom 0)
        prepare @#'e/prepare-closed-compact-rows]
    (with-redefs-fn
      {#'e/prepare-closed-compact-rows
       (fn [& args]
         (let [value (apply prepare args)]
           (when (= 2 (swap! prepares inc)) (deliver prepared-b true)) value))
       #'durable/try-submit-owned-and-flush!
       (fn [_ _] {:status :admitted :ticket (swap! submissions inc)})
       #'durable/await-confirmed!
       (fn [ticket]
         (when (= ticket 1) (deliver awaiting-a true) @release-a)
         {:status :committed})}
      (fn []
        (let [owner (sdk/batch-processor exporter
                      (e/experimental-span-batch-options exporter
                        {:max-export-batch-size 1 :max-queue-size 8 :schedule-delay-ms 60000}))]
          (try
            (dotimes [_ 3] (sdk/on-end owner span))
            (is (= true (deref prepared-b 3000 ::timeout)))
            (is (= true (deref awaiting-a 3000 ::timeout)))
            (is (= 2 (:in-flight @state)))
            (is (= 1 @submissions))
            (is (= 0 (:exported-span-count (sdk/batch-processor-stats owner))))
            (deliver release-a true)
            (is (true? (sdk/force-flush! owner)))
            (is (= 3 @submissions))
            (is (= 3 (:exported-span-count (sdk/batch-processor-stats owner))))
            (is (= 0 (:in-flight @state)))
            (finally (deliver release-a true) (sdk/shutdown! owner))))))))

(deftest changed-plan-fails-late-before-writer-admission-and-can-be-retired
  (let [{:keys [exporter state]} (fixture false) submitted (atom 0)]
    (with-redefs [durable/try-submit-owned-and-flush! (fn [& _] (swap! submitted inc))]
      (let [adapter (:experimental-span-preparation (e/experimental-span-batch-options exporter))
            cap ((:reserve! adapter) 1)]
        ((:prepare! adapter) cap [span])
        (swap! state assoc :timestamp-wire :iso-utc)
        (is (thrown? Exception ((:submit! adapter) cap)))
        (is (= 0 @submitted))
        ((:abort! adapter) cap) ((:release! adapter) cap) ((:retire! adapter) cap)
        (is (= 0 (:in-flight @state)))))))

(deftest reservation-bound-covers-capacity-at-real-codec-flush-boundaries
  (doseq [n [0 244 245 65524 65525 65526 131072]]
    (let [statement (owned/precompute-wal (owned/try-snapshot (byte-array n)))]
      (is (<= (owned/precomputed-retained-byte-count statement)
              (preparation/reservation-byte-count n))))))

(defn -main [& _]
  (let [r (run-tests 'otel.exporter.chdb-preparation-adapter-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
