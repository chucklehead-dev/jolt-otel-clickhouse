;; Reuse the validated summary and checks; only arm field/scope labels differ.
(require '[clojure.string :as str])
(defn replace-once [source needle replacement]
  (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
  (str/replace source needle replacement))
(let [source (slurp "/home/chuck/ai-src/evidence/summarize-stock-library-payload-cache-20261019.bb")
      source (replace-once source ":payload-cache-arm" ":flat-map-wire-cache-arm")
      source (replace-once source ":scope :stock-library-cache-sdk-local-durable-abba"
                           ":scope :flat-map-wire-cache-sdk-local-durable-abba :sdk-payload-cache-in-both-arms true")]
  (load-string source))
