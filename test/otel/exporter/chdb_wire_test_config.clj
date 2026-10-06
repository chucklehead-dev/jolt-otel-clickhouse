(ns otel.exporter.chdb-wire-test-config
  "Closed input-format selection for native/socket qualification fixtures.")

(defn parse-format [arguments]
  (case (vec arguments)
    [] :json-each-row
    ["json-each-row"] :json-each-row
    ["json-compact-each-row"] :json-compact-each-row
    (throw (ex-info "Invalid native fixture input format" {:type ::invalid-format}))))
