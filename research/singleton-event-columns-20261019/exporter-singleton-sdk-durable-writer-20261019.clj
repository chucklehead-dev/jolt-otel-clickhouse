(require '[clojure.java.io :as io] '[jolt.scheme :as scheme])
(let [arm (System/getenv "SDK_EXPORTER_ARM")
      source (str (io/resource "otel/exporter/chdb.clj"))
      json-source (str (io/resource "clojure/data/json/jolt_byte_writer.ss"))
      expected (if (= arm "candidate") "exporter-singleton-event-columns-20261019"
                 "exporter-default-omission-json-20261018")]
  (assert (contains? #{"baseline" "candidate"} arm))
  (assert (.contains source expected))
  (assert (.contains json-source "2172061060a102ccab779f5adc5d77f8b83a81f6"))
  (assert (scheme/eval-string
            "(and (top-level-bound? 'stock-sorted-map?)
                  (top-level-bound? 'sorted-map-fold-seq-order))"))
  (println :singleton-event-durable :arm arm :exporter-resource source
    :json-resource json-source)
  (flush))
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [original fixture/spans]
  (with-redefs [fixture/spans (fn [region] (nested-sorted-sdk-shape (original region)))]
    (load-file "/home/chuck/ai-src/evidence/sdk-trace-affinity-screen-20261018.clj")))
