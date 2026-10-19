(require '[clojure.java.io :as io] '[jolt.scheme :as scheme])
(let [arm (System/getenv "SDK_JSON_ARM")
      source (str (io/resource "clojure/data/json/jolt_byte_writer.ss"))
      expected (if (= arm "candidate") "data-json-escaped-ascii-cursor-20261019"
                   "data-json-default-omission-identity-20261018")]
  (assert (contains? #{"baseline" "candidate"} arm))
  (assert (.contains source expected))
  (assert (scheme/eval-string
            "(and (top-level-bound? 'stock-sorted-map?)
                  (top-level-bound? 'sorted-map-fold-seq-order))"))
  (println :escape-cursor-durable :arm arm :json-resource source)
  (flush))
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [original fixture/spans]
  (with-redefs [fixture/spans (fn [region] (nested-sorted-sdk-shape (original region)))]
    (load-file "/home/chuck/ai-src/evidence/sdk-trace-affinity-screen-20261018.clj")))
