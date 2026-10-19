;; Actual exporter option, no replacement of the production encoder Var.
(require '[clojure.java.io :as io] '[clojure.string :as str] '[clojure.edn :as edn]
         '[otel.exporter.chdb :as e])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [[root arm] *command-line-args*
      _ (assert (contains? #{"off" "on"} arm))
      _ (assert (str/includes? (str (io/resource "otel/exporter/chdb.clj"))
                              "exporter-explicit-codec-row-adapter-20261019"))
      source (slurp "/home/chuck/ai-src/evidence/rich-sdk-scaling-screen-20261019.clj")
      needle ":owned-statement-output? true"
      _ (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
      staged (str/replace source needle (str needle " :experimental-span-codec? " (= "on" arm)))
      original fixture/spans]
  (with-redefs [fixture/spans (fn [region] (nested-sorted-sdk-shape (original region)))]
    (with-bindings {#'*command-line-args* [root "1" "saturated"]} (load-string staged)))
  (let [path (str root "/writer.edn") data (edn/read-string (slurp path))]
    (spit path (pr-str (assoc data :experimental-codec-arm arm
                            :candidate-product-source true :research-harness-overlay true
                            :exporter-source (str (io/resource "otel/exporter/chdb.clj")))))))
