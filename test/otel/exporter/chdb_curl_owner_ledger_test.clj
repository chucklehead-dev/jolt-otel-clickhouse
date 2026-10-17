(ns otel.exporter.chdb-curl-owner-ledger-test
  (:require [clojure.test :refer [deftest is]]
            [otel.exporter.chdb-curl-owner-ledger :as ledger]))

(deftest retired-native-address-can-be-reused-by-a-new-lifetime
  (let [h1 (Object.) h2 (Object.) state (atom (ledger/initial))
        close! (fn [handle] (ledger/step! state :cleaned (ledger/step! state :retire handle)))]
    (is (= 0 (ledger/step! state :init h1)))
    (is (= 1 (ledger/step! state :init h2)))
    (ledger/step! state :performed (ledger/step! state :perform h1))
    (close! h1) (close! h2)
    (is (= 2 (ledger/step! state :init h1)))
    (is (thrown? clojure.lang.ExceptionInfo (ledger/step! state :cleaned 0)))
    (ledger/step! state :performed (ledger/step! state :perform h1))
    (close! h1)
    (is (ledger/settled? @state))
    (is (= [1 0 1] (mapv :requests (:owners @state))))
    ;; The old raw-pointer oracle rejects this valid exact history.
    (is (not (every? #(= 1 (count (filter (fn [h] (identical? h %)) [h1 h2 h1]))) [h1 h2 h1])))))

(deftest invalid-lifetime-events-and-incomplete-cleanup-are-rejected
  (let [h (Object.) state (atom (ledger/initial))]
    (ledger/step! state :init h)
    (is (thrown? clojure.lang.ExceptionInfo (ledger/step! state :init h)))
    (is (not (ledger/settled? @state)))
    (let [token (ledger/step! state :perform h)]
      (is (thrown? clojure.lang.ExceptionInfo (ledger/step! state :perform h)))
      (is (thrown? clojure.lang.ExceptionInfo (ledger/step! state :retire h)))
      (ledger/step! state :performed token)
      (is (thrown? clojure.lang.ExceptionInfo (ledger/step! state :performed token))))
    (let [token (ledger/step! state :retire h)]
      (is (not (ledger/settled? @state)))
      (is (thrown? clojure.lang.ExceptionInfo (ledger/step! state :perform h)))
      (is (thrown? clojure.lang.ExceptionInfo (ledger/step! state :retire h)))
      (ledger/step! state :cleaned token)
      (is (thrown? clojure.lang.ExceptionInfo (ledger/step! state :cleaned token))))
    (is (ledger/settled? @state))))
