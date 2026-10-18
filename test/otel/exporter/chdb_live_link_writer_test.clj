(ns otel.exporter.chdb-live-link-writer-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests]]
            [otel.exporter.chdb :as exporter]))

(def span {:name "synthetic" :start-time-unix-nano 0 :end-time-unix-nano 0
           :events [] :links [] :attributes {} :resource {:attributes {}}})

(defn- with-string-writer [write f]
  (let [original @#'json/write-string]
    (try
      ;; Replace the existing registration, rather than leave a new String
      ;; registration that could shadow later CharSequence extensions.
      (extend java.lang.CharSequence json/JSONWriter {:-write write})
      (f)
      (finally (extend java.lang.CharSequence json/JSONWriter {:-write original})))))

(deftest post-construction-string-writer-reaches-links-json
  (doseq [projector [nil (fn [_] (sorted-map "SpanAttribute.synthetic" "typed"))]]
    (let [encoder (#'exporter/compile-untyped-span-encoder projector)
          generic #(json/write-str (#'exporter/span-row span projector))]
      (is (ifn? encoder))
      (is (= (generic) (encoder span)))
      (with-string-writer
        (fn [_ out _] (.write out "\"replacement-marker\""))
        #(let [expected (generic) actual (encoder span)]
           (is (= "replacement-marker" (get (json/read-str expected) "LinksJSON")))
           (is (= expected actual)))))))

(deftest empty-links-json-invokes-current-string-method-once
  (let [encoder (#'exporter/compile-untyped-span-encoder)
        seen (atom [])
        original @#'json/write-string]
    (with-string-writer
      (fn [value out options] (swap! seen conj value) (original value out options))
      #(do
         (encoder span)
         ;; One outer string each for EventsJSON and LinksJSON; inner empty
         ;; vector JSON writes do not invoke String's protocol method.
         (is (= 2 (get (frequencies @seen) "[]" 0)))))))

(deftest changed-link-string-bytes-count-toward-the-row-budget
  (let [encoder (#'exporter/compile-untyped-span-encoder)]
    (with-string-writer
      (fn [_ out _] (.write out "\"longer-replacement-marker\""))
      #(let [expected (str (json/write-str (#'exporter/span-row span nil)) "\n")
             size (alength (.getBytes expected "UTF-8"))]
         (is (= expected (#'exporter/untyped-span-payload encoder [span] size)))
         (is (= (dec size) (try
                      (#'exporter/untyped-span-payload encoder [span] (dec size))
                      nil
                      (catch clojure.lang.ExceptionInfo error
                        (:limit (ex-data error))))))))))

(deftest post-construction-collection-writer-reaches-empty-links
  (let [encoder (#'exporter/compile-untyped-span-encoder)
        original @#'json/write-array]
    (try
      (extend java.util.Collection json/JSONWriter
              {:-write (fn [_ out _] (.write out "[\"custom-array\"]"))})
      (let [expected (json/write-str (#'exporter/span-row span nil))
            generic (json/read-str expected)
            actual (encoder span) compiled (json/read-str actual)]
        (is (= ["custom-array"] (get generic "Links.TraceId")))
        (doseq [column ["Links.TraceId" "Links.SpanId" "Links.TraceState"
                        "Links.Attributes" "LinksJSON"]]
          (is (= (get generic column) (get compiled column))))
        (is (= expected actual)))
      (finally (extend java.util.Collection json/JSONWriter {:-write original})))))

(defn -main [& _]
  (let [result (run-tests 'otel.exporter.chdb-live-link-writer-test)]
    (when-not (zero? (+ (:fail result) (:error result)))
      (throw (ex-info "Live link writer checks failed" result)))))
