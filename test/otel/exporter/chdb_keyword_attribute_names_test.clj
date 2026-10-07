(ns otel.exporter.chdb-keyword-attribute-names-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [otel.exporter.chdb :as exporter]))

(defn frozen-key-string [key]
  (cond (string? key) key (keyword? key) (subs (str key) 1) :else (str key)))

(deftest keyword-names-preserve-the-original-print-and-strip-wire
  (doseq [key [:service.name :domain/key :domain/é😀
               (keyword "") (keyword "" "name") (keyword "ns" "")
               (keyword "a/b" "c/d") (keyword nil "a/b")
               nil "plain" 'domain/key 42 false]]
    (is (= (frozen-key-string key) (#'exporter/key-string key)))))

(deftest normalized-collisions-and-complete-json-remain-identical
  (doseq [input [(array-map :domain/key "first" "domain/key" "last"
                            :service.name "example")
                 (array-map :é😀 "\n" :nested [true nil] :nil nil)
                 (array-map (keyword "" "name") "empty-ns"
                            (keyword "ns" "") "empty-name")]]
    (let [expected (into {} (map (fn [[key value]]
                                  [(frozen-key-string key) (#'exporter/value-string value)])) input)
          actual (#'exporter/attrs input)]
      (is (= expected actual))
      (is (= (json/write-str expected) (json/write-str actual)))))
  (is (= {"domain/key" "last"}
         (#'exporter/attrs (array-map :domain/key "first" "domain/key" "last")))))

(deftest attribute-converters-retain-order-and-stop-at-errors
  (let [effects (atom []) original-key @#'exporter/key-string
        original-value @#'exporter/value-string]
    (with-redefs [exporter/key-string (fn [key]
                                      (swap! effects conj [:key key])
                                      (original-key key))
                  exporter/value-string (fn [value]
                                          (swap! effects conj [:value value])
                                          (when (= "fail" value)
                                            (throw (ex-info "controlled failure" {:kind :controlled})))
                                          (original-value value))]
      (is (= {:kind :controlled}
             (try (#'exporter/attrs (array-map :domain/first "ok" :second "fail" :third "later"))
                  (catch clojure.lang.ExceptionInfo error (ex-data error)))))
      (is (= [[:key :domain/first] [:value "ok"] [:key :second] [:value "fail"]]
             @effects)))))
