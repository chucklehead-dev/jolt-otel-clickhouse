(ns otel.exporter.chdb-typed-encoder-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests]]
            [otel.exporter.chdb :as exporter]))

(def shape {:name "shape" :start-time-unix-nano 0 :end-time-unix-nano 0
            :events [] :links [] :attributes {} :resource {:attributes {}}})

(defn- projected [span]
  ;; Same fixed physical key set and value/status pair shape as a confirmed
  ;; descriptor projector. The native gate uses installer-issued descriptors.
  (let [value (get-in span [:resource :attributes "service.tier"])]
    (sorted-map "ResourceAttribute.service_tier" (if (string? value) value "")
                "ResourceAttribute.service_tier__status"
                (cond (nil? value) 1 (= value "") 2 (string? value) 3 :else 4)
                "SpanAttribute.answer" (if (integer? (get-in span [:attributes "answer"]))
                                         (get-in span [:attributes "answer"]) 0)
                "SpanAttribute.answer__status"
                (if (integer? (get-in span [:attributes "answer"])) 3 1))))

(defn- canonical [span]
  (json/write-str (#'exporter/span-row span projected)))

(deftest typed-encoder-exact-byte-parity
  (let [encoder (#'exporter/compile-untyped-span-encoder projected)
        cases [shape
               (assoc shape :resource {:attributes {"service.tier" ""}})
               (assoc shape :resource {:attributes {"service.tier" "gold/λ\"\n"}}
                      :attributes {"answer" 9223372036854775807}
                      :name "quote\" slash/ backslash\\ newline\n emoji😀")
               (assoc shape :resource {:attributes {"service.tier" true}}
                      :attributes {"answer" "invalid"})
               (assoc shape :start-time-unix-nano 1700000000000000001
                      :end-time-unix-nano 1700000000000000002
                      :events [{:timestamp-unix-nano 1700000000000000001
                                :name "event/λ" :attributes {"slash" "a/b"}}])
               (assoc shape :links [{:span-context {:trace-id "abc" :span-id "def"}
                                     :attributes {"x" "y"}}])]]
    (is (ifn? encoder))
    (doseq [span cases]
      (is (= (canonical span) (encoder span))))
    (let [expected (canonical (nth cases 2))]
      (with-redefs [exporter/span-row (fn [& _] (throw (ex-info "outer row map built" {})))]
        (is (= expected (encoder (nth cases 2))))))
    (is (= (apply str (map #(str (canonical %) "\n") cases))
           (#'exporter/untyped-span-payload encoder cases)))))

(deftest unsafe-column-shape-keeps-row-map-fallback
  (is (nil? (#'exporter/compile-untyped-span-encoder
             (fn [_] {"SpanName" "shadow"}))))
  (is (= "shadow" (get (#'exporter/span-row shape
                         (fn [_] {"SpanName" "shadow"})) "SpanName"))))

(defn -main [& _]
  (let [result (run-tests 'otel.exporter.chdb-typed-encoder-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
