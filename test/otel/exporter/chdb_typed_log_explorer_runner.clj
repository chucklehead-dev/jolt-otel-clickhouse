(ns otel.exporter.chdb-typed-log-explorer-runner
  (:require [db.jdbc]))

(defn -main [& _]
  (require 'otel.exporter.chdb-typed-log-explorer-test)
  ((resolve 'otel.exporter.chdb-typed-log-explorer-test/-main)))
