(ns otel.exporter.chdb-attribute-kv-fold-test
  (:require [clojure.test :refer [deftest is]]
            [otel.exporter.chdb :as exporter]))

(defn legacy-attrs [m]
  (persistent!
    (reduce (fn [out [k v]]
              (assoc! out (#'exporter/key-string k) (#'exporter/value-string v)))
            (transient {}) (or m {}))))

(defn observe [operation input]
  (let [effects (atom [])
        key-string @#'exporter/key-string value-string @#'exporter/value-string]
    (with-redefs [exporter/key-string (fn [key]
                                      (swap! effects conj [:key key])
                                      (key-string key))
                  exporter/value-string (fn [value]
                                          (swap! effects conj [:value value])
                                          (value-string value))]
      [(operation input) @effects])))

(deftest exact-values-order-and-collision-precedence
  (doseq [input [nil {} (array-map :a 1 "a" 2 :domain/key true)
                 (array-map :empty nil :nested [true "é😀"] :negative -1)
                 (into {} (map (fn [n] [(str "key" n) n]) (range 40)))
                 (sorted-map :a 1 :b false)
                 [[:a 1] ["a" 2] [:domain/key "value"]]
                 (list [:a 1] [:b 2])]]
    (is (= (observe legacy-attrs input) (observe #'exporter/attrs input)))))

(deftest array-map-uses-kv-reduction-and-other-inputs-do-not
  (let [calls (atom []) original @#'exporter/array-map-attrs]
    (with-redefs [exporter/array-map-attrs (fn [input]
                                          (swap! calls conj input)
                                          (original input))]
      (let [small (array-map :a 1 "a" 2)]
        (is (= {"a" "2"} (#'exporter/attrs small)))
        (is (= [small] @calls)))
      (reset! calls [])
      (doseq [input [nil [[:a 1]] (sorted-map :a 1)
                     (into {} (map (fn [n] [n n]) (range 40)))]]
        (is (= (legacy-attrs input) (#'exporter/attrs input))))
      (is (empty? @calls)))))

(deftest conversion-error-stops-before-later-entry
  (let [input (array-map :first "ok" :second "fail" :third "later")
        run (fn [operation]
              (let [effects (atom []) original @#'exporter/value-string]
                (with-redefs [exporter/value-string
                              (fn [value]
                                (swap! effects conj value)
                                (when (= "fail" value)
                                  (throw (ex-info "controlled failure" {:kind :controlled})))
                                (original value))]
                  [(try (operation input) (catch clojure.lang.ExceptionInfo e (ex-data e)))
                   @effects])))]
    (is (= [{:kind :controlled} ["ok" "fail"]] (run legacy-attrs)))
    (is (= (run legacy-attrs) (run #'exporter/attrs)))))
