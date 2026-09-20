(ns otel.exporter.chdb-typed-metric-explorer-runner
  (:require [db.jdbc]))

(defn -main [& _]
  (require 'otel.exporter.chdb-typed-metric-explorer-test)
  ((resolve 'otel.exporter.chdb-typed-metric-explorer-test/-main)))
