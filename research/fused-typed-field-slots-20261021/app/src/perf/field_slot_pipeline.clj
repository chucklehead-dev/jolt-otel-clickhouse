(ns perf.field-slot-pipeline
  "Same public confirmed app; constructor selection only, same wrapper both arms."
  (:require [perf.public-pipeline :as app]
            [otel.exporter.chdb.native-projection :as native]
            [clojure.edn :as edn]))

(defn -main [& [root arm]]
  (assert (contains? #{"indexed" "fused"} arm))
  (let [factory native/load-trace-field-slots!
        calls (atom 0) selected (atom 0)]
    (with-redefs [native/load-trace-field-slots!
                  (fn [& args]
                    (swap! calls inc)
                    (when (= arm "fused")
                      (let [result (apply factory args)]
                        (when result (swap! selected inc)) result)))]
      (app/-main root "release" "view"))
    (let [path (str root "/writer.edn") w (edn/read-string (slurp path))]
      ;; Preserve report before assertions; a mechanism failure never erases the
      ;; native writer's successful receipt or pretends the experiment passed.
      (spit path
        (pr-str (assoc w :scope :research-confirmed-field-slot-comparison
                         :field-slots-arm arm :factory-calls @calls :factories-selected @selected
                         :research-factory-selection true :same-wrapper-both-arms true
                         :per-row-probes false :production-default-changed false
                         :native-recovery-qualified false)))
      (assert (= [175000 5000 32 3] (mapv w [:stored-rows :batch-size :rounds :warmups])))
      (assert (= 1 @calls))
      (assert (= (if (= arm "fused") 1 0) @selected)))
    (println :field-slot-selection arm :factory-calls @calls :selected @selected)))
