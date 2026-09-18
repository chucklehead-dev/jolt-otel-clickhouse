(ns otel.exporter.chdb-migration-record-effect-writer
  "Minimal native-process writer for the migration-record effect witness."
  (:require [clojure.string :as str]
            [db.jdbc]
            [jdbc.chdb]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb-migration-record-effect-child :as reader]
            [otel.exporter.chdb.schema :as schema]))

(defn- cause-chain [error]
  (loop [current error causes []]
    (if current
      (recur (.getCause current) (conj causes current))
      causes)))

(defn -main [& _]
  (let [path (System/getenv "JOLT_CAUSAL_RECORD_EFFECT_PATH")
        armed? (atom false)
        injected? (atom false)
        sentinel (ex-info "injected post-native registry-result decode failure"
                          {:type ::injected-post-native-record-decode})]
    (when-not (and (string? path) (not (str/blank? path)))
      (throw (ex-info "Causal record-effect writer path missing" {})))
    (let [failure
          (with-open [conn (jdbc/connection (str "chdb:" path))]
            ;; Build the ordinary registry before arming the decoder seam. The
            ;; only later query is apply-migration!'s native registry INSERT.
            (schema/migrate! conn)
            (let [apply-migration (ns-resolve 'otel.exporter.chdb.schema
                                              'apply-migration!)
                  decode-result (ns-resolve 'jdbc.chdb 'decode-compact-json)
                  original-decode @decode-result]
              (when-not (and apply-migration decode-result)
                (throw (ex-info "Required causal migration test seam missing" {})))
              (with-redefs-fn
                {decode-result
                 (fn [data]
                   ;; The native query has returned before this decoder runs.
                   ;; Decode first, then inject, to make this result-consumption
                   ;; failure rather than a pre-driver stand-in.
                   (let [decoded (original-decode data)]
                     (if (compare-and-set! armed? true false)
                       (do (reset! injected? true) (throw sentinel))
                       decoded)))}
                (fn []
                  (reset! armed? true)
                  (try
                    (@apply-migration conn reader/migration)
                    nil
                    (catch Throwable error error))))))
          expected (select-keys reader/migration [:version :name :checksum])
          actual (select-keys (ex-data failure) [:version :name :checksum])
          qualified? (and @injected?
                          (= :record (:phase (ex-data failure)))
                          (= expected actual)
                          (boolean (some #(identical? sentinel %)
                                         (cause-chain failure))))]
      (println :causal-record-effect-writer-child-result :qualified qualified?)
      (when qualified?
        (println :causal-record-effect-writer-child-confirmed))
      (System/exit (if qualified? 0 1)))))
