(ns perf.value-reuse-pipeline
  "Research provider selection over the existing public confirmed exporter app.
  No SDK policy/mutation/acknowledgement replacement. Not a new product flag."
  (:require [perf.public-pipeline :as app]
            [jolt.codec.scalar-text.experimental :as shapes]
            [clojure.edn :as edn]))

(defn -main [& [root arm]]
  (assert (contains? #{"views" "value-reuse"} arm))
  (let [selected (if (= arm "value-reuse")
                   shapes/native-value-reuse-plan-factory shapes/native-view-plan-factory)
        providers (atom 0) constructors (atom 0) last-planner (volatile! nil)]
    ;; Same provider and constructor wrappers in BOTH arms. The SDK supplies
    ;; original bindings unchanged; record selection, never snapshot its roots.
    (with-redefs [shapes/native-view-plan-factory
                  (fn [box]
                    (swap! providers inc)
                    (let [factory (selected box)]
                      (fn [bindings maximum prototype schemas chars]
                        (swap! constructors inc)
                        (let [p (factory bindings maximum prototype schemas chars)]
                          (vreset! last-planner p) p))))]
      (app/-main root "release" "view"))
    (assert (= 1 @providers))
    ;; One preflight constructor, then one for each3warm+32measured payloads.
    (assert (= 36 @constructors))
    (let [path (str root "/writer.edn") report (edn/read-string (slurp path))
          stats (when (= arm "value-reuse") (@last-planner 3 nil))]
      (assert (= [175000 32 3] (mapv report [:stored-rows :rounds :warmups])))
      (when stats (assert (pos? (nth stats 2))))
      (spit path
        (pr-str (assoc report :scope :research-public-exporter-value-reuse-comparison
                             :value-reuse-arm arm :provider-calls @providers
                             :planner-constructors @constructors :last-value-reuse-stats stats
                             :research-provider-selection true :same-wrapper-both-arms true
                             :production-default-changed false :native-recovery-qualified false))))
    (println :value-reuse-selection arm :providers @providers :constructors @constructors)))
