(ns otel.exporter.chdb-payload-chunks-test
  (:require [clojure.test :as test :refer [deftest is]]
            #?(:bb [cheshire.core :as json]
               :clj [clojure.data.json :as json])
            [otel.exporter.chdb.payload-chunks :as chunks]))

(defn- write-str [value & {:as options}]
  #?(:bb (json/generate-string value
                              (cond-> {}
                                (contains? options :escape-unicode)
                                (assoc :escape-non-ascii (:escape-unicode options))))
     :clj (json/write-str value options)))

(defn- options [limit total]
  {:encode-row write-str :max-chunk-bytes limit
   :max-logical-bytes total :max-rows 1000})
(defn- utf8-size [text] (alength (.getBytes text "UTF-8")))
(defn- error-type [f]
  (try (f) :returned (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))

(deftest exact-utf8-boundaries-and-order
  (doseq [rows [[nil false 0 "é😀"]
                [{"line" "a\nb"} {"quote" "\"/\\"} {}]
                (mapv #(hash-map "index" % "value" "β😀") (range 40))]]
    (let [reference (mapv #(str (write-str %) "\n") rows)
          minimum (apply max (map utf8-size reference))]
      (doseq [limit [minimum (inc minimum) (* 2 minimum) 4096]]
        (let [result (chunks/prepare-payloads! rows (options limit 16384))
              prepared (:chunks result)]
          (is (= (apply str reference) (apply str (map :payload prepared))))
          (is (= (count rows) (:row-count result)
                 (reduce + (map :row-count prepared))))
          (is (= (reduce + (map utf8-size reference)) (:byte-count result)
                 (reduce + (map :byte-count prepared))))
          (doseq [chunk prepared]
            (is (= (utf8-size (:payload chunk)) (:byte-count chunk)))
            (is (<= 1 (:byte-count chunk) limit))))))))

(deftest exact-full-chunk-has-no-empty-successor
  (let [result (chunks/prepare-payloads! [1 2 3 4] (options 4 8))]
    (is (= ["1\n2\n" "3\n4\n"] (mapv :payload (:chunks result))))
    (is (= [2 2] (mapv :row-count (:chunks result)))))
  (is (= {:chunks [] :byte-count 0 :row-count 0}
         (chunks/prepare-payloads! [] (options 4 8)))))

(deftest splitting-never-reencodes-effects
  (let [calls (atom [])
        result (chunks/prepare-payloads!
                [1 2 3 4]
                (assoc (options 2 8) :encode-row
                       (fn [row] (swap! calls conj row) (write-str row))))]
    (is (= [1 2 3 4] @calls))
    (is (= ["1\n" "2\n" "3\n" "4\n"] (mapv :payload (:chunks result))))))

(deftest rejected-row-does-not-request-later-input
  (doseq [[row opts expected]
          [["😀" (options 8 8) ::chunks/logical-limit]
           ["😀" (options 6 8) ::chunks/oversized-row]]]
    (let [events (atom [])
          ;; unchunked source: a false implementation which requests next after
          ;; rejecting the second row would execute the final throwing thunk.
          rows (lazy-seq
                (cons 1 (lazy-seq
                         (cons row (lazy-seq
                                    (swap! events conj :late-input)
                                    (throw (ex-info "must not request later row" {})))))))
          encode (fn [value] (swap! events conj [:encode value])
                   (write-str value :escape-unicode false))]
      (is (= expected (error-type #(chunks/prepare-payloads! rows (assoc opts :encode-row encode)))))
      (is (= [[:encode 1] [:encode row]] @events)))))

(deftest option-row-count-and-callback-failures
  (doseq [opts [(assoc (options 2 8) :max-chunk-bytes 0)
                (assoc (options 2 8) :max-logical-bytes 67108865)
                (assoc (options 2 8) :max-rows 65537)
                (assoc (options 2 8) :unknown true)]]
    (is (= ::chunks/invalid-options (error-type #(chunks/prepare-payloads! [1] opts)))))
  (let [calls (atom [])]
    (is (= ::chunks/row-limit
           (error-type #(chunks/prepare-payloads!
                          [1 2] (assoc (options 2 8) :max-rows 1 :encode-row
                                       (fn [row] (swap! calls conj row) (str row)))))))
    (is (= [1] @calls)))
  (is (= ::chunks/invalid-encoded-row
         (error-type #(chunks/prepare-payloads! [1] (assoc (options 2 8) :encode-row (constantly nil))))))
  (let [calls (atom 0)]
    (is (= :callback-failed
           (error-type #(chunks/prepare-payloads!
                          [1 2] (assoc (options 2 8) :encode-row
                                       (fn [_] (swap! calls inc)
                                         (throw (ex-info "expected" {:type :callback-failed}))))))))
    (is (= 1 @calls))))

(defn -main [& _]
  (let [result (test/run-tests 'otel.exporter.chdb-payload-chunks-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (throw (ex-info "Payload chunk tests failed" result)))))
