(require '[clojure.java.io :as io] '[jolt.scheme :as scheme])
(assert (contains? #{"1" "2"} (second *command-line-args*)))
(assert (.contains (str (io/resource "otel/exporter/chdb.clj")) "exporter-singleton-event-columns-20261019"))
(assert (.contains (str (io/resource "otel/any_value.clj")) "7878a2eb4340519d6843d13490a5a7c99ebade8a"))
(assert (.contains (str (io/resource "clojure/data/json/jolt_byte_writer.ss")) "2172061060a102ccab779f5adc5d77f8b83a81f6"))
(println :rich-sdk-lanes :lanes (second *command-line-args*) :selected-stack true)
(flush)
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [original fixture/spans]
  (with-redefs [fixture/spans (fn [region] (nested-sorted-sdk-shape (original region)))]
    (load-file "/home/chuck/ai-src/evidence/sdk-trace-affinity-screen-20261018.clj")))
