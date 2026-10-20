;; Semantic gate, not a comparative performance result. Same 175k-row workload
;; as previous qualified reader; ONLY select explicit codec + ordinary metadata
;; reader. No production Vars or compiler/runtime helpers are replaced.
(require '[clojure.string :as str] '[clojure.java.io :as io]
         '[clojure.edn :as edn] '[jolt.scheme :as scheme])
(assert (false? ((scheme/eval-string "(lambda () (top-level-bound? 'pmap-fold-seq-order))"))))
(assert (false? ((scheme/eval-string "(lambda () (top-level-bound? 'na-owned-bv->bytearray))"))))
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [[root] *command-line-args*
      source (slurp "/home/chuck/ai-src/evidence/rich-sdk-scaling-screen-20261019.clj")
      changes [["'[clojure.data.json.jolt-native :as json-native]" ""]
               [":operations {:json-reader (json-native/load-reader!)}" ":operations {}"]
               [":owned-statement-output? true" ":owned-statement-output? true :experimental-span-codec? true"]]
      staged (reduce (fn [text [needle replacement]]
                       (assert (= 2 (count (str/split text (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
                       (str/replace text needle replacement)) source changes)
      original fixture/spans]
  (spit (str root "/staged-writer.clj") staged)
  (with-redefs [fixture/spans (fn [region] (nested-sorted-sdk-shape (original region)))]
    (with-bindings {#'*command-line-args* [root "1" "saturated"]} (load-string staged)))
  (let [path (str root "/writer.edn") data (edn/read-string (slurp path))]
    (spit path (pr-str (assoc data :experimental-codec-arm "on"
                            :candidate-product-source true :research-harness-overlay true
                            :stock-runtime-gate true
                            :exporter-source (str (io/resource "otel/exporter/chdb.clj")))))))
