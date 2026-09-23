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

(deftest typed-payload-enforces-utf8-and-lf-byte-bound
  (let [limit (* 8 1024 1024)
        seen (atom [])
        projector (fn [span]
                    (swap! seen conj (:name span))
                    (projected span))
        encoder (#'exporter/compile-untyped-span-encoder projector)
        empty-row (encoder (assoc shape :name ""))
        overhead (inc (alength (.getBytes empty-row "UTF-8")))
        full (assoc shape :name (apply str (repeat (- limit overhead) "x")))
        next-row (assoc shape :name "next")
        later (assoc shape :name "must-not-start")]
    (is (ifn? encoder))
    (reset! seen [])
    (let [payload (#'exporter/untyped-span-payload encoder [full])]
      (is (= limit (alength (.getBytes payload "UTF-8"))))
      (is (= [(get full :name)] @seen)))
    (reset! seen [])
    (let [error (try
                  (#'exporter/untyped-span-payload encoder [full next-row later])
                  nil
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (some? error))
      (is (= {:limit limit} (ex-data error)))
      (is (= [(:name full) "next"] @seen)))
    ;; A UTF-8 scalar consumes two bytes, even though it is one character.
    (let [ascii (assoc shape :name "x")
          accented (assoc shape :name "é")
          ascii-limit (inc (alength (.getBytes (encoder ascii) "UTF-8")))]
      (is (= ascii-limit
             (alength (.getBytes (#'exporter/untyped-span-payload encoder [ascii]
                               ascii-limit) "UTF-8"))))
      (is (some? (try
                   (#'exporter/untyped-span-payload encoder [accented] ascii-limit)
                   nil
                   (catch clojure.lang.ExceptionInfo error error)))))))

(defn -main [& _]
  (let [result (run-tests 'otel.exporter.chdb-typed-encoder-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
