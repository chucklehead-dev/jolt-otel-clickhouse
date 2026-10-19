(ns otel.exporter.chdb-sorted-event-encoding-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]
            [otel.exporter.chdb :as e]
            [otel.sdk.tracer :as sdk] [otel.sdk.export :as export]
            [otel.exporter.memory :as memory] [otel.resource :as resource]
            [otel.trace :as trace]))

(defn ended-spans []
  (let [attrs (into {"service.name" "sorted-event-test" "flag" false "count" 42}
                    (map (fn [i] [(str "extra-" i)
                                  (case (mod i 3) 0 "λ\n\"😀" 1 false i)]) (range 14)))
        sink (memory/exporter)
        provider (sdk/tracer-provider {:resource (resource/resource attrs)
                                       :processors [(export/simple-processor sink)]})
        tracer (sdk/get-tracer provider {:name "sorted-event-test" :attributes attrs})]
    (try
      (dotimes [i 2]
        (trace/with-span [span tracer (str "scenario-" i) {:attributes attrs}]
          (trace/add-event! span "event" attrs)))
      (finally (assert (sdk/shutdown! provider))))
    (memory/spans sink)))

(defn stock-payload [spans compact?]
  (with-bindings {#'e/*json-backend* :configured #'e/*timestamp-wire* :raw-ticks}
    (apply str (map #(str (json/write-str (@#'e/span-row % nil compact?)) "\n") spans))))

(defn native-payload [spans compact? prefix budget]
  (with-bindings {#'e/*json-backend* :native-guarded-byte-batch #'e/*timestamp-wire* :raw-ticks}
    (native/write-prefixed-batch-bytes!
      prefix (map #(@#'e/span-row % nil compact?) spans) budget)))

(deftest actual-sdk-sorted-events-have-identical-named-and-compact-bytes
  (let [spans (ended-spans)]
    (is (= 2 (count spans)))
    (is (every? #(sorted? (get-in % [:resource :attributes])) spans))
    (is (every? #(sorted? (get-in % [:events 0 :attributes])) spans))
    (doseq [compact? [false true]]
      (let [expected (stock-payload spans compact?)
            actual (String. (native-payload spans compact? "" 100000) "UTF-8")]
        (is (= expected actual))
        (is (= 2 (count (str/split-lines actual))))))
    (let [row (json/read-str (first (str/split-lines
                                    (String. (native-payload spans false "" 100000) "UTF-8"))))
          event (first (json/read-str (get row "EventsJSON")))]
      (is (false? (get-in event ["attributes" "flag"])))
      (is (= 42 (get-in event ["attributes" "count"])))
      (is (= "λ\n\"😀" (get-in event ["attributes" "extra-0"]))))))

(deftest event-rich-output-honors-exact-newline-inclusive-byte-limit
  (let [spans (ended-spans) prefix "prefix-not-in-budget "
        expected (stock-payload spans true)
        budget (alength (.getBytes expected "UTF-8"))]
    (is (= (str prefix expected)
           (String. (native-payload spans true prefix budget) "UTF-8")))
    (is (= ::native/output-limit
           (:type (ex-data (try (native-payload spans true prefix (dec budget))
                               nil (catch Throwable error error))))))))

(defn -main [& _]
  (let [r (run-tests 'otel.exporter.chdb-sorted-event-encoding-test)]
    (assert (and (= 2 (:test r)) (>= (:pass r) 12)))
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
