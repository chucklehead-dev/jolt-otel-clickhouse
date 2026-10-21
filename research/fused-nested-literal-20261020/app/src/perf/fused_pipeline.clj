(ns perf.fused-pipeline
  "Research selection shim over the unchanged public exporter/Durable app.
  Not a new exporter option, live SDK queue or uninstrumented release claim."
  (:require [perf.public-pipeline :as app] [jolt.codec.experimental :as codec]
            [clojure.edn :as edn]))

(defn -main [& [root arm]]
  (assert (contains? #{"cached" "fused-cached"} arm))
  (let [selected (if (= arm "fused-cached")
                   codec/with-nested-literal-writer codec/with-nested-text-writer)
        calls (atom 0)]
    ;; Same wrapper/atomic counter in both arms. No conversion/policy roots,
    ;; private exporter functions or mutation/confirmation APIs are replaced.
    (with-redefs [codec/with-nested-text-writer
                  (fn [c use-writer] (swap! calls inc) (selected c use-writer))]
      (app/-main root "release" "view"))
    (assert (= 35 @calls))
    (let [path (str root "/writer.edn") original (edn/read-string (slurp path))]
      (assert (= [175000 32 3] (mapv original [:stored-rows :rounds :warmups])))
      (spit path
        (pr-str (assoc original
                  :scope :research-public-exporter-nested-writer-comparison
                  :nested-writer-arm arm :nested-scope-calls @calls
                  :research-constructor-selection true
                  :shared-candidate-encoder true :causal-comparison-qualified false
                  :unmodified-public-fixture true :live-sdk-queue false))))
    (println :nested-writer-scope-calls @calls :arm arm)))
