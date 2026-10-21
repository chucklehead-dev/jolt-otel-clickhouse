(ns otel.exporter.chdb-preparation-owner-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [otel.exporter.chdb.preparation :as preparation]))

(defn fixture [overrides]
  (let [admitted (atom 0) released (atom 0)
        adapter (preparation/open-adapter
                  (merge {:max-batch-rows 1 :max-rows 2 :reservation-bytes 10 :max-bytes 20
                          :admit! (fn [] (swap! admitted inc) true)
                          :release! (fn [] (swap! released inc) true)
                          :materialize! (fn [_] {:prepared :encoded :retained-bytes 3})
                          :try-submit! (fn [_] {:status :admitted :ticket :confirmed-ticket})
                          :await-confirmed! (fn [_] {:status :committed})} overrides))]
    {:adapter adapter :admitted admitted :released released}))

(deftest reservations-are-held-through-release-and-retired-once
  (let [{:keys [adapter admitted released]} (fixture {})
        a ((:reserve! adapter) 1)]
    (is (= "#<span preparation reservation>" (pr-str a)))
    (is (nil? ((:reserve! adapter) 1)) "one preparer cannot reserve a hidden next batch")
    (is (true? ((:prepare! adapter) a [{}])))
    (is (true? ((:submit! adapter) a)))
    (let [b ((:reserve! adapter) 1)]
      (is (true? ((:prepare! adapter) b [{}])))
      (is (nil? ((:reserve! adapter) 1)))
      (is (true? ((:await! adapter) a)))
      (is (thrown? Exception ((:abort! adapter) a)))
      (is (true? ((:release! adapter) a)))
      (is (nil? ((:reserve! adapter) 1)) "completion/release does not retire a slot")
      (is (true? ((:retire! adapter) a)))
      (is (thrown? Exception ((:retire! adapter) a)))
      (is (true? ((:submit! adapter) b)))
      (let [c ((:reserve! adapter) 1)]
        (is c)
        (is (true? ((:abort! adapter) c)))
        (is (true? ((:release! adapter) c)))
        (is (true? ((:retire! adapter) c))))
      (is (true? ((:await! adapter) b)))
      (is (true? ((:release! adapter) b)))
      (is (true? ((:retire! adapter) b))))
    (is (= 2 @admitted))
    (is (= 2 @released))))

(deftest cancellation-rejects-new-admission-but-settles-prior-ticket
  (let [{:keys [adapter admitted released]} (fixture {})
        a ((:reserve! adapter) 1)]
    ((:prepare! adapter) a [{}]) ((:submit! adapter) a)
    (let [b ((:reserve! adapter) 1)]
      (is (true? ((:fence! adapter))))
      (is (thrown? Exception ((:prepare! adapter) b [{}])))
      (is (= 1 @admitted))
      ((:abort! adapter) b) ((:release! adapter) b) ((:retire! adapter) b))
    (is (true? ((:await! adapter) a)))
    ((:release! adapter) a) ((:retire! adapter) a)
    (is (= 1 @released))
    (is (nil? ((:reserve! adapter) 1)))))

(deftest rejected-and-overbudget-preparation-return-admission-exactly-once
  (doseq [overrides [{:materialize! (fn [_] {:prepared :encoded :retained-bytes 11})}
                    {:materialize! (fn [_] (throw (ex-info "controlled encoder failure" {})))}]]
    (let [{:keys [adapter admitted released]} (fixture overrides)
          a ((:reserve! adapter) 1)]
      (is (thrown? Exception ((:prepare! adapter) a [{}])))
      (is (= 1 @admitted))
      ((:abort! adapter) a) ((:release! adapter) a) ((:retire! adapter) a)
      (is (= 1 @released))
      (is (thrown? Exception ((:release! adapter) a))))))

(deftest unconfirmed-release-is-not-retried-or-retired
  (let [attempts (atom 0)
        {:keys [adapter]} (fixture {:release! (fn [] (swap! attempts inc) false)})
        a ((:reserve! adapter) 1)]
    ((:prepare! adapter) a [{}]) ((:submit! adapter) a) ((:await! adapter) a)
    (is (thrown? Exception ((:release! adapter) a)))
    (is (thrown? Exception ((:release! adapter) a)))
    (is (thrown? Exception ((:retire! adapter) a)))
    (is (= 1 @attempts))))

(deftest unknown-admission-is-quarantined-rather-than-treated-as-rejection
  (doseq [result [{:status :admitted} {:status :unknown} nil
                 {:status :full :ticket :unexpected}]]
    (let [{:keys [adapter released]} (fixture {:try-submit! (fn [_] result)})
          cap ((:reserve! adapter) 1)]
      ((:prepare! adapter) cap [{}])
      (is (thrown? Exception ((:submit! adapter) cap)))
      (is (thrown? Exception ((:abort! adapter) cap)))
      (is (thrown? Exception ((:release! adapter) cap)))
      (is (thrown? Exception ((:retire! adapter) cap)))
      (is (= 0 @released)))))

(deftest fence-does-not-wait-for-an-active-materializer
  (let [entered (promise) release (promise) prepared (promise) fenced (promise)
        {:keys [adapter admitted released]}
        (fixture {:materialize! (fn [_] (deliver entered true) @release
                                {:prepared :encoded :retained-bytes 3})})
        a ((:reserve! adapter) 1)
        preparer (Thread. #(deliver prepared ((:prepare! adapter) a [{}])))
        fencer (Thread. #(deliver fenced ((:fence! adapter))))]
    (try
      (.start preparer)
      (is (= true (deref entered 3000 ::timeout)))
      (.start fencer)
      (is (= true (deref fenced 3000 ::timeout)))
      (is (thrown? Exception ((:abort! adapter) a)) "cannot free an active materializer")
      (deliver release true)
      (.join preparer 3000) (.join fencer 3000)
      (is (= true (deref prepared 100 ::timeout)))
      (is (thrown? Exception ((:submit! adapter) a)))
      ((:abort! adapter) a) ((:release! adapter) a) ((:retire! adapter) a)
      (is (= 1 @admitted)) (is (= 1 @released))
      (finally (deliver release true)
               (when (.isAlive preparer) (.join preparer 3000))
               (when (.isAlive fencer) (.join fencer 3000))))))

(defn -main [& _]
  (let [r (run-tests 'otel.exporter.chdb-preparation-owner-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
