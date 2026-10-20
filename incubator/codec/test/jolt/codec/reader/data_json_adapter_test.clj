(ns jolt.codec.reader.data-json-adapter-test
  "Adapter qualification; intentionally separate from dependency-free tests."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests]]
            [jolt.codec.reader.experimental :as reader]))

(defn configured-read [source options]
  (apply json/read-str source (mapcat identity options)))
(def capability
  (reader/open-reader {:number-reader configured-read :fallback-reader configured-read}))
(defn outcome [run]
  (try (let [value (run)] {:value value :class (class value)})
       (catch Exception error {:class (class error) :message (ex-message error)})))
(defn compare-paths [source options]
  (is (= (outcome #(configured-read source options))
         (outcome #(reader/read-string capability source options))) source))

(deftest numeric-values-classes-and-malformed-parity
  (doseq [source ["false" "null" "true" "{}" "[]" "0" "-0"
                  "9223372036854775807" "-9223372036854775808"
                  "9223372036854775808" "-9223372036854775809"
                  "1.25" "1e300" "1e-300" "1e309"
                  "{\"a\":[false,null,{},[1,-2,3.25]],\"b\":\"λ🚀\"}"
                  "" " " "[" "{" "[1,]" "{\"a\":}" "01" "-01"
                  "1e" "1e+" "1.2.3" "true false" "{} trailing"
                  "{\"a\":1,\"a\":2}" "{\"a\":1,\"\\u0061\":2}"
                  "\"\\uZZZZ\"" "\"\\uD800\"" "\"x\\x\""
                  (str "\"raw" (char 1) "control\"")
                  (str "\"escaped\\nraw" (char 1) "control\"")]
          options [{} {:bigdec true} {:eof-error? false :eof-value :eof}]]
    (compare-paths source options)))

(deftest generated-values-retain-types-and-telemetry-content
  (doseq [n (range 256)
          :let [value {"name" (str "λ🚀-" n) "active" (even? n) "empty" nil
                       "nested" [{"integer" n "negative" (- n)
                                  "text" (str (char n) "\"/\\suffix")}
                                 false [] {}]}
                source (json/write-str value)]]
    (is (= value (reader/read-string capability source)))
    (compare-paths source {})))

(deftest callback-counts-unread-suffix-and-options-are-preserved
  (doseq [source ["{}" "{} " "{}\n" "{}tail" "false " "42 "]]
    (let [run (fn [selected?]
                (let [calls (atom [])
                      options {:extra-data-fn
                               (fn [value input]
                                 (swap! calls conj [value (slurp input)]) :callback)}
                      value (if selected? (reader/read-string capability source options)
                                (configured-read source options))]
                  [value @calls]))]
      (is (= (run false) (run true)))))
  (doseq [options [{:key-fn keyword} {:key-fn nil}
                   {:value-fn (fn [_ value] (if (number? value) (inc value) value))}]]
    (compare-paths "{\"a\":7,\"b\":false}" options))
  (let [source (str (apply str (repeat 65 "[")) "0" (apply str (repeat 65 "]")))]
    (compare-paths source {})))

(defn -main [& _]
  (let [result (run-tests 'jolt.codec.reader.data-json-adapter-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
