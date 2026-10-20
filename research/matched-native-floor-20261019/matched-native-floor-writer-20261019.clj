;; Research: matched rich input, serial confirmed native/persistence cost.
;; Native arm EXCLUDES repeated encoding/preparation and SDK queue/dispatch.
;; It is not actual SDK throughput, pipeline concurrency, or a Rust comparison.
(require '[db.jdbc] '[jdbc.core :as jdbc] '[clojure.edn :as edn]
         '[clojure.string :as str] '[jdbc.chdb.durable :as durable]
         '[jdbc.chdb.native :as native] '[jdbc.chdb.owned-statement :as owned]
         '[jdbc.chdb.byte-record :as record] '[jdbc.chdb.durable.digest :as digest]
         '[otel.sdk.export :as export] '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb-typed-storage-support :as storage])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(load-file "/home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj")

(defn ensure! [ok label]
  (when-not ok (throw (ex-info "Matched native floor failed" {:label label}))))
(defn process-sample []
  (let [stat (slurp "/proc/self/stat")
        fields (str/split (subs stat (+ 2 (.lastIndexOf stat ")"))) #"\s+")
        status (slurp "/proc/self/status")
        kib (fn [name] (parse-long (second (re-find (re-pattern (str "(?m)^" name ":\\s+(\\d+) kB$")) status))))]
    {:user-ticks (parse-long (nth fields 11)) :system-ticks (parse-long (nth fields 12))
     :thread-count (parse-long (nth fields 17)) :rss-kib (kib "VmRSS") :highwater-kib (kib "VmHWM")}))
(defn statement-receipt [statement]
  (ensure! (owned/statement? statement) :owned-statement)
  (ensure! (identical? statement (owned/precompute-wal statement)) :precomputed-before-window)
  (let [wal (owned/prepared-wal-for-write statement)]
    (ensure! (satisfies? record/ByteRecord wal) :private-prepared-record)
    {:statement-bytes (owned/byte-count statement)
     :statement-sha256 (digest/sha256-bytes (.getBytes (owned/text statement) "UTF-8"))
     :prepared-wal-bytes (record/byte-count wal)}))

(let [[root arm] *command-line-args*]
  (ensure! (contains? #{"sdk" "native"} arm) :arm)
  (if (= "sdk" arm)
    ;; Delegate unchanged SDK behavior; observe one actual confirmed SQL value
    ;; to establish matching bytes. No hashing or re-encoding in its timed loop.
    (let [statement (atom nil) calls (atom 0) execute @#'e/execute-durable-sql!]
      (with-redefs [e/execute-durable-sql!
                    (fn [connection sql]
                      (swap! calls inc)
                      (when (nil? @statement) (reset! statement sql))
                      (execute connection sql))]
        (with-bindings {#'*command-line-args* [root "on"]}
          (load-file "/home/chuck/ai-src/evidence/flat-map-wire-cache-durable-arm-20261019.clj")))
      (ensure! (= 35 @calls) :sdk-physical-inserts)
      (let [path (str root "/writer.edn") w (edn/read-string (slurp path))]
        (spit path (pr-str (merge w (statement-receipt @statement)
                                 {:native-floor-arm arm :preencoded-native-floor? false
                                  :capture-calls @calls :matched-runtime-source true})))))
    (let [n 5000 rounds 32
          _ (ensure! (= :local (storage/arm)) :local-storage)
          _ (native/ensure-loaded!)
          _ (ensure! (= "26.9.0" (native/chdb-version)) :native-version)
          spans (otel.research.trace-affinity/group-traces
                  (mapv (fn [span]
                          (update (assoc-in span [:span-context :trace-flags] 1) :attributes into
                                  (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value" 1 false i)]))
                                  (range 12)))
                        (nested-sorted-sdk-shape
                          (with-redefs [fixture/batch-size n] (fixture/spans "typed-compact-prefix")))))
          streams (atom 0) actual-stream native/chdb-stream-insert-n]
      (with-redefs [native/chdb-stream-insert-n
                    (fn [& args] (swap! streams inc) (apply actual-stream args))]
        (with-open [connection (jdbc/connection
                                (durable/writer-dbspec
                                  (merge (storage/options root "writer")
                                         {:owner "typed-compact-prefix" :instance "writer-1"
                                          :database "otel" :owned-compact-stream? true
                                          :lease-ttl-ms 900000 :heartbeat-interval-ms 300000
                                          :operations {}})))]
          (let [{:keys [capability]} (fixture/setup connection)
                _ (ensure! (= :committed (:status (durable/checkpoint! connection))) :schema-checkpoint)
                owner (e/exporter {:connection connection :durable? true :create-schema? false
                                   :datetime64-wire :raw-ticks :max-insert-bytes 67108864
                                   :signals #{:spans} :typed-span-descriptors capability
                                   :insert-format :json-compact-each-row :json-backend :native-guarded-byte-batch
                                   :owned-statement-output? true :experimental-span-codec? true
                                   :experimental-span-payload-cache? true :experimental-span-flat-map-cache? true})
                captured (atom nil) captures (atom 0) execute @#'e/execute-durable-sql!]
            ;; The captured statement really executes and confirms, as warmup1.
            ;; Retire the borrowed exporter before the direct serial window.
            (try
              (with-redefs [e/execute-durable-sql!
                            (fn [c sql]
                              (swap! captures inc) (reset! captured sql) (execute c sql))]
                (ensure! (true? (export/export-spans! owner spans)) :confirmed-preparation-warmup))
              (finally (ensure! (true? (export/shutdown-exporter! owner)) :exporter-retired)))
            (ensure! (= 1 @captures) :one-preparation-insert)
            (let [statement @captured receipt (statement-receipt statement)
                  insert! (fn []
                            (let [result (durable/execute-owned-and-flush! connection statement)]
                              (ensure! (contains? #{:committed :reconciled} (:status result)) :confirmed-publication)
                              (:status result)))]
              (dotimes [_ 2] (insert!))
              (System/gc)
              (let [os-before (process-sample) before (#'bench/counter-sample)
                    start (System/nanoTime)
                    batches (mapv (fn [index]
                                    (let [begin (System/nanoTime) status (insert!) end (System/nanoTime)]
                                      {:ordinal index :rows n :status status :ok true
                                       :start-nanos begin :end-nanos end :nanos (- end begin)}))
                                  (range rounds))
                    end (System/nanoTime) after (#'bench/counter-sample) os-after (process-sample)]
                (ensure! (= 35 @streams) :all-physical-native-streams)
                (ensure! (= 175000 (:n (jdbc/fetch-one connection "SELECT count() AS n FROM otel_traces"))) :writer-count)
                (spit (str root "/writer.edn")
                      (pr-str (merge receipt
                                     {:scope :matched-preencoded-confirmed-native-floor
                                      :native-floor-arm arm :preencoded-native-floor? true
                                      :matched-runtime-source true :sdk-queue-measured? false
                                      :encoding-and-wal-precompute-measured? false
                                      :batch-size n :rounds rounds :rows (* n rounds) :warmups 3
                                      :capture-calls @captures :stream-calls @streams :batches batches
                                      :confirmed-window-nanos (- end start)
                                      :confirmed-rows-per-second (/ (* 1e9 n rounds) (- end start))
                                      :counter-delta (#'bench/counter-delta before after)
                                      :os-process-window
                                      {:ticks-per-second (parse-long (System/getenv "BENCH_CLK_TCK"))
                                       :user-ticks-delta (- (:user-ticks os-after) (:user-ticks os-before))
                                       :system-ticks-delta (- (:system-ticks os-after) (:system-ticks os-before))
                                       :before os-before :after os-after :non-atomic-snapshots true}
                                      :target-qualified false :tail-qualified false :s3-qualified false})))))))))))
