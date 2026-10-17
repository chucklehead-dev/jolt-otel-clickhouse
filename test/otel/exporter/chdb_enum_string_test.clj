(ns otel.exporter.chdb-enum-string-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [otel.exporter.chdb :as exporter]))

(defn- legacy-enum [value fallback]
  (str/capitalize (name (or value fallback))))

(defn- outcome [f]
  (try [:value (f)]
       (catch Throwable e [:error (.getName (class e))])))

(deftest known-and-extended-enums-preserve-values-and-errors
  (doseq [value [nil false :internal :server :client :producer :consumer
                 :unset :ok :error :vendor/server :vendor/FUTURE
                 'future "FUTURE" "" 42 {}]
          fallback [:internal :unset :future nil]]
    (is (= (outcome #(legacy-enum value fallback))
           (outcome #(#'exporter/otel-enum-string value fallback))))))

(deftest standard-keywords-avoid-string-case-work-but-extensions-retain-it
  (let [calls (atom []) capitalize str/capitalize]
    (with-redefs [str/capitalize (fn [text] (swap! calls conj text) (capitalize text))]
      (doseq [[value expected] [[:internal "Internal"] [:server "Server"]
                                [:client "Client"] [:producer "Producer"]
                                [:consumer "Consumer"] [:unset "Unset"]
                                [:ok "Ok"] [:error "Error"]]]
        (is (= expected (#'exporter/otel-enum-string value :unset))))
      (is (empty? @calls))
      (is (= "Future" (#'exporter/otel-enum-string :vendor/FUTURE :unset)))
      (is (= ["FUTURE"] @calls)))))

(def ^:private span
  {:start-time-unix-nano 0 :end-time-unix-nano 1
   :span-context {:trace-id "trace" :span-id "span"}
   :name "operation" :events [] :links []})

(deftest named-and-compact-rows-retain-identical-wire
  (doseq [kind [nil :server :client :vendor/FUTURE]
          status [nil :ok :error :vendor/FUTURE]
          compact? [false true]]
    (let [input (assoc span :kind kind :status {:code status})
          expected (with-redefs [exporter/otel-enum-string legacy-enum]
                     (#'exporter/span-row input nil compact?))
          actual (#'exporter/span-row input nil compact?)]
      (is (= expected actual))
      (is (= (json/write-str expected) (json/write-str actual))))))

(deftest row-construction-keeps-the-enum-helper-live
  (with-redefs [exporter/otel-enum-string (fn [_ _] "replacement")]
    (let [row (#'exporter/span-row span nil)]
      (is (= "replacement" (get row "SpanKind")))
      (is (= "replacement" (get row "StatusCode"))))))
