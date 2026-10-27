(ns perf.hamt-pipeline
  "Research-only constructor selection around the unchanged real SDK pipeline."
  (:require [otel.exporter.chdb :as exporter]
            [perf.sdk-preparation-pipeline :as pipeline]))

(defn -main [root selection n rounds warmups mode]
  (when-not (contains? #{"enabled" "disabled"} selection)
    (throw (ex-info "Expected enabled or disabled HAMT views" {})))
  (.mkdirs (java.io.File. root))
  (spit (str root "/hamt-mode.edn")
        (pr-str {:hamt-views (= selection "enabled")
                 :control :same-binary-new-sdk-sorted-only-versus-hamt
                 :research-only-constructor-selection true}))
  ;; Research process owns exactly one storage lifetime. Read only during codec
  ;; construction; no dynamic binding remains active while worker code runs.
  (reset! exporter/research-hamt-selection (= selection "enabled"))
  (pipeline/-main root "overlap" n rounds warmups mode))
