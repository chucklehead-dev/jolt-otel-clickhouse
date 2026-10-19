(require '[clojure.java.io :as io] '[clojure.edn :as edn]
         '[otel.any-value :as any] '[otel.exporter.chdb :as e])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [[root arm] *command-line-args*]
  (assert (contains? #{"off" "on"} arm))
  (assert (.contains (str (io/resource "otel/exporter/chdb.clj")) "exporter-sdk-payload-scalar-cache-consumer-20261019"))
  (assert (.contains (str (io/resource "otel/any_value.clj")) "otel-payload-scalar-map-native-budget-20261019"))
  (assert (some? (any/scalar-string-map-payload-converter)))
  (let [original fixture/spans
        selected-factory @#'e/sdk-scalar-map-payload-factory]
    (with-redefs-fn {#'fixture/spans (fn [region] (nested-sorted-sdk-shape (original region)))
                    #'e/sdk-scalar-map-payload-factory
                    (if (= arm "on") selected-factory (delay nil))}
      (fn []
        (with-bindings {#'*command-line-args* [root "1" "saturated"]}
          (load-file "/home/chuck/ai-src/evidence/rich-sdk-scaling-screen-20261019.clj")))))
  ;; Written only after the inherited writer has closed every owner/connection.
  (let [path (str root "/writer.edn") data (edn/read-string (slurp path))]
    (spit path (pr-str (assoc data :payload-scalar-cache-arm arm
                            :sdk-cache-source "c474e02" :exporter-cache-local-wiring true
                            :runtime "7098a8d6" :native-library "26.9.0"))))
  (println :sdk-payload-cache-writer-terminal :arm arm))
