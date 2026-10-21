(ns perf.sdk-preparation-pipeline-test
  "Source-only observer controls, never linked into the timed app."
  (:require [clojure.test :refer [deftest is run-tests]]
            [otel.sdk.preparation :as preparation]
            [perf.sdk-preparation-pipeline :as pipeline]))

(defn- observe [adapter active]
  (let [queued (promise)]
    (deliver queued true)
    ;; Deliberate source-test seam only; no private probes in timed main.
    (#'pipeline/observed-preparation adapter (atom []) active 1 queued 1 1 (atom {}))))

(deftest observer-retains-index-until-literal-retirement
  (doseq [result [false nil :unconfirmed]]
    (let [active (atom {:cap 0}) adapter (observe {:retire! (fn [_] result)} active)]
      (is (thrown? Exception ((:retire! adapter) :cap)))
      (is (= {:cap 0} @active))))
  (let [active (atom {:cap 0}) adapter (observe {:retire! (fn [_] true)} active)]
    (is (true? ((:retire! adapter) :cap)))
    (is (empty? @active))))

(deftest sdk-already-withholds-proof-on-nonliteral-retirement
  ;; This is the REAL engine, not the wrapper's map-emptiness assertion. The
  ;; old wrapper returned false unchanged, so the SDK still quarantined it.
  (doseq [result [false nil :unconfirmed]]
    (let [;; Deliberately bypass the observer: its new guard must not mask a
          ;; regression from literal-true to truthy checks inside the SDK.
          adapter {:reserve! (fn [_] :cap) :prepare! (fn [_ _] true)
                   :submit! (fn [_] true) :await! (fn [_] true)
                   :release! (fn [_] true) :retire! (fn [_] result)
                   :abort! (fn [_] true) :fence! (fn [] true)}
          state (atom {:queue [{}] :attempted-span-count 0 :exported-span-count 0 :failed-span-count 0})
          take-batch! (fn [state _]
                        (let [batch (:queue @state)] (swap! state assoc :queue []) batch))
          error (try (preparation/drain! adapter state 1 take-batch! (fn [_] nil)) nil
                     (catch Exception error error))]
      (is (= :preparation-unsettled (:otel.sdk/error (ex-data error))))
      (is (true? (:preparation-unsettled? @state)))
      (is (= 1 (:exported-span-count @state)) "delivery and resource proof are separate")
      (is (thrown? Exception (preparation/require-settled! state))))))

(defn -main [& _]
  (let [r (run-tests 'perf.sdk-preparation-pipeline-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
