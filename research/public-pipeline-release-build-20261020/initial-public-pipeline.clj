(ns perf.public-pipeline
  "Public synchronous exporter/Durable app; no private Vars, redefs, eval or
  benchmark namespace dependencies that could inhibit release optimization."
  (:require [db.jdbc] [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable] [jdbc.chdb.native :as native]
            [jdbc.chdb.durable.local-posix :as local]
            [jdbc.chdb.durable.backend :as backend]
            [otel.sdk.export :as export]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            ;; Explicit optional libraries must be available in the app image.
            [jolt.codec.experimental] [jolt.codec.scalar-text.experimental]
            [otel.any-value-native] [jolt.host :as host]))

(defn ensure! [ok label]
  (when-not ok (throw (ex-info "Public pipeline check failed" {:label label}))))

(defn fixture [n]
  (let [base 1700000000123456789
        resource (into (sorted-map "service.name" "producer-shape" "flag" false "count" 42)
                   (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value-λ" 1 false i)]) (range 14)))
        extra (fn [] (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value" 1 false i)]) (range 12)))]
    (mapv (fn [i]
            {:name "typed-compact-prefix" :kind :internal
             :start-time-unix-nano (+ base i) :end-time-unix-nano (+ base i 1000)
             :span-context {:trace-id (format "%032x" (inc (* 8 (quot i 8))))
                            :span-id (format "%016x" (inc i)) :trace-flags 1}
             :resource {:attributes (into (sorted-map) resource)}
             :scope {:name "transport-benchmark" :version "1"
                     :attributes (into (sorted-map) resource)}
             :attributes (into {"flag" false "count" i
                                "url" "https://synthetic.invalid/path?q=one?two"
                                "text" "quote\"slash\\newline\n漢字😀"} (extra))
             :events [{:name "scenario-event" :timestamp-unix-nano (+ base i)
                       :attributes (into (sorted-map) resource)}]
             :links [] :status {:code :unset}})
          (range n))))

(defn approved []
  (manifest/compile-manifest
    {:dataset-id "ordinary-benchmark" :application-id "trace-transport"
     :lineage "benchmark-v1" :version 1
     :fragments [{:schema manifest/reviewed-fragment-schema :authority :advice
                  :source "advice/ordinary-benchmark.edn"
                  :entries [{:signal :spans :table "otel_traces" :location :span-attributes
                             :key "flag" :type :boolean}
                            {:signal :spans :table "otel_traces" :location :span-attributes
                             :key "count" :type :int64}]}]}))

(defn setup! [connection]
  (schema/ensure-schema! connection)
  (let [installation (installer/install-approved!
                       (backend/memory-backend) (approved)
                       {:target connection
                        :observe-columns #(vector {:signal :spans :table "otel_traces"
                                                   :columns (into {} (map (juxt :name :type))
                                                                  (jdbc/fetch connection "DESCRIBE TABLE otel_traces"))})
                        :execute-ddl! #(jdbc/execute! connection %)})]
    (ensure! (= :active (:status installation)) :typed-installation)
    (:descriptor-set installation)))

(defn counters []
  {:nanos (System/nanoTime) :heap (host/bytes-allocated) :gc-bytes (host/gc-bytes)
   :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos) :cpu-nanos (host/cpu-nanos)})

(defn -main [& [root mode]]
  (ensure! (and root (.isAbsolute (java.io.File. root))) :absolute-owned-root)
  (ensure! (contains? #{"source" "release" "dev"} mode) :mode-label)
  (native/ensure-loaded!)
  (ensure! (= "26.9.0" (native/chdb-version)) :native-version)
  (let [n 5000 rounds 32 warmups 3 rows (fixture n)
        scratch (str root "/scratch-writer")
        _ (.mkdirs (java.io.File. scratch))]
    (with-open [connection
                (jdbc/connection
                  (durable/writer-dbspec
                    {:namespace-backend (local/local-backend (str root "/objects"))
                     :object-id "typed-compact-prefix" :scratch-parent scratch
                     :owner "typed-compact-prefix" :instance "writer-1" :database "otel"
                     :owned-compact-stream? true :lease-ttl-ms 900000
                     :heartbeat-interval-ms 300000 :operations {}}))]
      (let [capability (setup! connection)
            _ (ensure! (= :committed (:status (durable/checkpoint! connection))) :schema-checkpoint)
            owner (exporter/exporter
                    {:connection connection :durable? true :create-schema? false
                     :datetime64-wire :raw-ticks :max-insert-bytes 67108864
                     :signals #{:spans} :typed-span-descriptors capability
                     :insert-format :json-compact-each-row :json-backend :native-guarded-byte-batch
                     :owned-statement-output? true :experimental-span-codec? true
                     :experimental-span-payload-cache? true :experimental-span-flat-map-cache? true
                     :experimental-span-nested-fragment-cache? true :experimental-span-shape-plans? true})]
        (try
          (dotimes [_ warmups] (ensure! (true? (export/export-spans! owner rows)) :warmup))
          (System/gc)
          (let [before (counters)
                samples (mapv (fn [round]
                                (let [start (System/nanoTime)
                                      ok (export/export-spans! owner rows)
                                      elapsed (- (System/nanoTime) start)]
                                  (ensure! (true? ok) :confirmed-export)
                                  (println :public-pipeline-batch {:round round :nanos elapsed})
                                  (flush)
                                  {:round round :nanos elapsed :rows n :ok true})) (range rounds))
                after (counters) elapsed (- (:nanos after) (:nanos before))
                stored (:n (jdbc/fetch-one connection "SELECT count() AS n FROM otel_traces"))
                v (vec (sort (map :nanos samples)))
                q (fn [p] (/ (nth v (dec (long (Math/ceil (* p (count v)))))) 1e6))]
            (ensure! (= (* n (+ warmups rounds)) stored) :writer-count)
            (spit (str root "/writer.edn")
              (pr-str {:scope :public-synchronous-exporter-source-release :mode mode
                       :sdk-queue-used false :private-var-probes false :stream-calls-instrumented false
                       :rows (* n rounds) :batch-size n :warmups warmups :rounds rounds
                       :samples samples :before before :after after :confirmed-window-nanos elapsed
                       :confirmed-rows-per-second (/ (* 1e9 n rounds) elapsed)
                       :p50-p90-p95-p99-ms (mapv q [0.5 0.9 0.95 0.99])
                       :estimated-bytes-per-row
                       (/ (- (+ (:heap after) (:gc-bytes after)) (+ (:heap before) (:gc-bytes before)))
                          (double (* n rounds)))
                       :stored-rows stored :native-package "26.9.0" :tail-qualified false
                       :counter-snapshots-non-atomic true :diagnostic-printing-in-window true}))
            (println :public-pipeline-complete {:rows stored :mode mode}))
          (finally (export/shutdown-exporter! owner)))))))
