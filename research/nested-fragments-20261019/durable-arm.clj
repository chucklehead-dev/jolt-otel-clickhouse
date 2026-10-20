;; One real SDK queue/exporter/Durable writer; source generator, not a product override.
(require '[clojure.string :as str] '[clojure.edn :as edn]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture])
(load-file "research/library-nested-text-20261019/nested-sorted-sdk-fixture-20261018.clj")
(let [[root arm] *command-line-args*
      _ (assert (contains? #{"off" "on"} arm))
      source (slurp "research/library-nested-text-20261019/rich-sdk-scaling-screen-20261019.clj")
      replacements [["'[clojure.data.json.jolt-native :as json-native]" ""]
                    [":operations {:json-reader (json-native/load-reader!)}" ":operations {}"]
                    ["/home/chuck/ai-src/evidence/trace-affinity-scaling-helpers-20261019.clj"
                     "research/library-nested-text-20261019/trace-affinity-scaling-helpers-20261019.clj"]
                    [":owned-statement-output? true"
                     (str ":owned-statement-output? true :experimental-span-codec? true"
                          " :experimental-span-payload-cache? true :experimental-span-flat-map-cache? true"
                          " :experimental-span-nested-fragment-cache? " (= arm "on"))]]
      staged (reduce (fn [text [needle replacement]]
                       (assert (= 2 (count (str/split text (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
                       (str/replace text needle replacement)) source replacements)
      original fixture/spans]
  (spit (str root "/staged-writer.clj") staged)
  (with-redefs [fixture/spans (fn [region] (nested-sorted-sdk-shape (original region)))]
    (binding [*command-line-args* [root "1" "saturated"]] (load-string staged)))
  (let [path (str root "/writer.edn") data (edn/read-string (slurp path))]
    (spit path (pr-str (assoc data :nested-fragments-arm arm
                             :sdk-payload-cache-in-both-arms true :outer-wire-cache-in-both-arms true
                             :row-view-in-production false :runtime "official-stock-0.8.23"
                             :exporter-source (str (clojure.java.io/resource "otel/exporter/chdb.clj")))))))
