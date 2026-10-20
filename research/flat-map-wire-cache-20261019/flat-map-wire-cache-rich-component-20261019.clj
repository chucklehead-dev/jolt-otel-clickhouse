;; Compare only the wire-map cache. Existing SDK normalization cache is enabled
;; in BOTH arms. Reuse full rich fixture, unshared controls, digests and counters.
(require '[clojure.string :as str])
(defn replace-once [source needle replacement]
  (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
  (str/replace source needle replacement))
(let [source (slurp "/home/chuck/ai-src/evidence/stock-payload-cache-library-component-20261019.clj")
      changes [["c (codec/open-codec)" "codecs {false (codec/open-codec) true (codec/open-codec {:flat-map-cache? true})}"]
               ["false (#'e/load-experimental-span-codec! false)" "false (#'e/load-experimental-span-codec! true)"]
               ["(let [context (contexts enabled?)" "(let [c (codecs enabled?) context (contexts enabled?)"]
               [":scope :stock-library-full-rich-projection-nested-outer-component"
                ":scope :flat-map-wire-cache-rich-component :sdk-normalization-cache-in-both-arms true"]]]
  (load-string (reduce (fn [text [needle replacement]] (replace-once text needle replacement)) source changes)))
