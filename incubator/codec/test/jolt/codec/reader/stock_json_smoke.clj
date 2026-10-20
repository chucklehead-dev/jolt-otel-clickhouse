(ns jolt.codec.reader.stock-json-smoke
  "Bounded stock-Maven adapter smoke, not a full stock data.json compatibility gate."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.codec.reader.experimental :as reader]))

(defn -main [& _]
  (let [source (str (io/resource "clojure/data/json.clj"))
        parse (fn [text options] (apply json/read-str text (mapcat identity options)))
        capability (reader/open-reader {:number-reader parse :fallback-reader parse})]
    (assert (str/includes? source "data.json-2.5.2.jar"))
    (doseq [[text expected] [["false" false] ["null" nil] ["42" 42] ["1.25" 1.25]
                             ["{\"a\":[false,null,42],\"b\":\"\\ud83d\\ude80\"}"
                              {"a" [false nil 42] "b" "🚀"}]]]
      (assert (= expected (reader/read-string capability text))))
    (assert (= {:a 42} (reader/read-string capability "{\"a\":42}" {:key-fn keyword})))
    (assert (= (json/read-str "9223372036854775808")
               (reader/read-string capability "9223372036854775808")))
    (println :stock-maven-adapter-passed {:json-source source})))
