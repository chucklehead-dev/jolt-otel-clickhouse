(ns otel.exporter.chdb-experimental-codec-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [jolt.codec.experimental :as codec]
            [otel.exporter.chdb :as e]
            [otel.exporter.chdb.compact-format :as compact]
            [otel.exporter.chdb.native-projection :as native-projection]
            [otel.exporter.chdb.attribute-projection :as projection]
            [jdbc.chdb.owned-statement :as owned]
            [jdbc.chdb.json-each-row :as row-encoder]
            [jdbc.core :as jdbc]))

(deftest explicit-preflight-does-not-load-legacy-native-json
  (let [opens (atom 0)]
    (with-redefs [row-encoder/open-encoder
                  (fn [& _] (throw (ex-info "Legacy writer loaded" {:type ::legacy-loaded})))
                  jdbc/connection
                  (fn [& _] (swap! opens inc) (throw (ex-info "Reached acquisition" {:type ::acquisition})))]
      (is (= ::acquisition
             (try (e/exporter {:experimental-span-codec? true :signals #{:spans}
                               :durable? true :insert-format :json-compact-each-row
                               :json-backend :native-guarded-byte-batch
                               :owned-statement-output? true})
                  nil (catch Throwable error (:type (ex-data error))))))
      (is (= 1 @opens)))))

(deftest library-traversal-preserves-live-normalization-and-collisions
  (let [context (#'e/load-experimental-span-codec!)
        wanted #{"x" "y"}
        collect (native-projection/load-declared-collector!
                  #'projection/key-string #'clojure.core/contains?
                  @#'projection/original-key-string (:folder context))]
    (doseq [input [{"x" 1 "y" false} {:x 1 "x" 2 :y false}
                  (into {} (map (fn [n] [(str "k" n) n]) (range 40)))]]
      (let [expected (reduce (fn [out [k v]]
                               (let [k (#'projection/key-string k)]
                                 (if (contains? wanted k)
                                   (update out k (fnil conj []) v) out))) {} input)]
        (is (= expected (collect input wanted)))))
    (let [seen (atom []) original @#'projection/key-string
          input {:x 1 "x" 2 :y false}]
      (with-redefs-fn {#'projection/key-string
                       (fn [key] (swap! seen conj key) (original key))}
        (fn []
          (is (= {"x" [1 2] "y" [false]} (collect input wanted)))
          (is (= (mapv first input) @seen)))))
    (with-bindings {#'e/*json-backend* :native-guarded-byte-batch
                    #'e/*experimental-attribute-transform* (:attributes context)}
      (is (= {"x" "1" "y" "false"} (#'e/attrs {:x 1 :y false}))))))

(deftest invalid-opt-in-never-opens-storage
  (let [opens (atom 0)]
    (with-redefs [jdbc/connection (fn [& _] (swap! opens inc) (throw (ex-info "DB called" {})))]
      (doseq [option [true "true" 1 nil]]
        (is (= :otel.exporter.chdb/invalid-experimental-span-codec
               (try (e/exporter {:experimental-span-codec? option}) nil
                    (catch Throwable t (:type (ex-data t))))))))
    (is (zero? @opens))))

(deftest unavailable-runtime-does-not-blame-only-alias-or-leak-cause
  (with-redefs [codec/open-codec (fn [] (throw (ex-info "synthetic-private-detail" {:private "not exported"})))]
    (let [error (try (#'e/load-experimental-span-codec!) nil (catch Throwable t t))]
      (is (= :otel.exporter.chdb/experimental-span-codec-unavailable (:type (ex-data error))))
      (is (str/includes? (ex-message error) "qualified runtime"))
      (is (not (str/includes? (ex-message error) "synthetic-private-detail")))
      (is (nil? (ex-cause error))))))

(deftest row-shape-and-post-encoding-plan-check-before-execute
  (let [codec (#'e/load-experimental-span-codec!)
        stale? (atom false) calls (atom [])
        checked (fn []
                  (when @stale? (throw (ex-info "stale plan" {:type ::stale})))
                  {:compact-plans :test :owned-statement-output? true})]
    (with-redefs-fn {#'compact/require-order! (fn [& _] (swap! calls conj :plan))
                    #'owned/try-snapshot (fn [_] nil)
                    #'e/execute-durable-sql! (fn [& _] (swap! calls conj :execute))}
      (fn []
        (is (= :otel.exporter.chdb/invalid-compact-row
               (try (#'e/execute-closed-compact-rows! :conn "otel_traces" ["x"] [1] checked codec
                                                    (fn [_] []))
                    nil (catch Throwable t (:type (ex-data t))))))
        (is (not-any? #{:execute} @calls))
        (reset! calls [])
        (is (= ::stale
               (try (#'e/execute-closed-compact-rows! :conn "otel_traces" ["x"] [1] checked codec
                                                    (fn [x] (reset! stale? true) [x]))
                    nil (catch Throwable t (:type (ex-data t))))))
        (is (not-any? #{:execute} @calls))
        (reset! stale? false) (reset! calls [])
        (#'e/execute-closed-compact-rows! :conn "otel_traces" ["x"] [1] checked codec vector)
        (is (= [:plan :plan :execute] @calls))))))
