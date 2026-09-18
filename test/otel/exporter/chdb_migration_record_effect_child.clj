(ns otel.exporter.chdb-migration-record-effect-child
  "Minimal independent native-process reader for the migration-record effect
  witness. It intentionally does not call schema/migrate!: v99 is test-only
  direct application, not part of the maintained consecutive migration plan."
  (:require [clojure.string :as str]
            [db.jdbc]
            [jdbc.chdb]
            [jdbc.core :as jdbc]))

(def migration
  {:version 99
   :name "causal-native-record-effect-v99"
   :checksum "e6b9f09bb03a458d7667124d456a82d7cad2b32e0e4d2bf8c407543909ecd567"
   :statements []})

(defn -main [& _]
  (let [path (System/getenv "JOLT_CAUSAL_RECORD_EFFECT_PATH")]
    (when-not (and (string? path) (not (str/blank? path)))
      (throw (ex-info "Causal record-effect child path missing" {})))
    (let [expected (select-keys migration [:version :name :checksum])
          actual (with-open [conn (jdbc/connection (str "chdb:" path))]
                   (jdbc/fetch-one
                    conn
                    "select Version, Name, Checksum
                       from otel_schema_migrations where Version=99"))
          qualified? (= expected actual)]
      (println :causal-record-effect-readback-child-result
               :qualified qualified? :actual actual)
      (when qualified?
        (println :causal-record-effect-readback-child-confirmed))
      (System/exit (if qualified? 0 1)))))
