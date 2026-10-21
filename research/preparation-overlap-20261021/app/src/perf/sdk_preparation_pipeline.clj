(ns perf.sdk-preparation-pipeline
  "Saturated real SDK queue control. Same fixture and binary for both arms.
  No private Vars, redefs, direct drain calls or fabricated confirmation."
  (:require [jdbc.core :as jdbc] [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local] [jdbc.chdb.native :as native]
            [otel.sdk.export :as sdk] [otel.sdk.lifecycle :as lifecycle]
            [otel.exporter.chdb :as exporter]
            ;; Deliberate optional selection: keep these providers reachable
            ;; in the standalone image, not just the source CLI loader.
            [otel.exporter.chdb.preparation] [otel.sdk.preparation]
            [perf.public-pipeline :as fixture]))

(defn ensure! [ok label] (fixture/ensure! ok label))

(defn- first-batch-barrier! [index queued]
  ;; Startup-only warmup barrier: populate the bounded raw queue before any
  ;; encoding. No producer starvation/partial batches in this saturated screen.
  (when (zero? index)
    (ensure! (= true (deref queued 30000 ::timeout)) :initial-queue-ready)))

(defn- start-sample! [samples spans n queued]
  (let [index (count @samples)]
    (ensure! (= n (count spans)) :exact-batch-geometry)
    (first-batch-barrier! index queued)
    (swap! samples conj {:index index :rows n :started (System/nanoTime)})
    index))

(defn- finish-sample! [samples index ok warmups total anchors]
  (ensure! (true? ok) :confirmed-callback)
  (swap! samples update index assoc :confirmed (System/nanoTime))
  (when (= index (dec warmups)) (swap! anchors assoc :before (fixture/counters)))
  (when (= index (dec total)) (swap! anchors assoc :after (fixture/counters)))
  ;; Retain completed scalar observations even if a later batch/process fails.
  ;; Both arms pay this same coarse diagnostic cost, never once per row.
  (println :sdk-preparation-batch
           (select-keys (get @samples index) [:index :rows :started :prepared :submitted :confirmed]))
  (flush))

(defrecord ObservedExporter [delegate samples n queued warmups total anchors]
  sdk/SpanExporter
  (export-spans! [_ spans]
    (let [index (start-sample! samples spans n queued)
          ok (sdk/export-spans! delegate spans)]
      (finish-sample! samples index ok warmups total anchors)
      ok))
  (flush-exporter! [_] (sdk/flush-exporter! delegate))
  (shutdown-exporter! [_] (sdk/shutdown-exporter! delegate))
  lifecycle/SettlementWitness
  (settlement-status [_] (lifecycle/settlement-status delegate)))

(defn- observed-preparation [adapter samples active n queued warmups total anchors]
  (assoc adapter
    :prepare! (fn [cap spans]
                (let [index (start-sample! samples spans n queued)]
                  (swap! active assoc cap index)
                  (let [ok ((:prepare! adapter) cap spans)]
                    (ensure! (true? ok) :prepared)
                    (swap! samples update index assoc :prepared (System/nanoTime))
                    ok)))
    :submit! (fn [cap]
               (let [ok ((:submit! adapter) cap)]
                 (ensure! (true? ok) :writer-admitted)
                 (swap! samples update (get @active cap) assoc :submitted (System/nanoTime))
                 ok))
    :await! (fn [cap]
              (let [index (get @active cap)
                    ok ((:await! adapter) cap)]
                (finish-sample! samples index ok warmups total anchors)
                ok))
    :retire! (fn [cap]
               (let [ok ((:retire! adapter) cap)]
                 (ensure! (true? ok) :retired)
                 (swap! active dissoc cap)
                 ok))))

(defn- quantiles-ms [values]
  (let [sorted (vec (sort values)) size (count sorted)]
    (ensure! (pos? size) :nonempty-samples)
    (mapv (fn [p] (/ (nth sorted (dec (long (Math/ceil (* p size))))) 1e6))
          [0.5 0.9 0.95 0.99])))

(defn- report! [root arm mode n rounds warmups samples producer anchors stats]
  (let [all @samples measured (subvec all warmups)
        completed (mapv :confirmed all)
        cadence (mapv (fn [i] (- (nth completed i) (nth completed (dec i))))
                      (range warmups (count all)))
        processing (mapv #(- (:confirmed %) (:started %)) measured)
        queued (mapv (fn [sample] (- (:confirmed sample) (:started (nth @producer (:index sample)))))
                     measured)
        elapsed (- (last completed) (nth completed (dec warmups)))
        before (:before @anchors) after (:after @anchors)]
    (ensure! (= rounds (count measured)) :measured-batch-count)
    (ensure! (every? pos? cadence) :monotonic-confirmation)
    (spit (str root "/writer.edn")
      (pr-str {:scope :saturated-real-sdk-preparation-screen :arm arm :execution-mode mode
               :ownership :consumer-owned :sdk-queue-used true
               :confirmation-observation :confirmed-callback-return
               :startup-only-queue-preload true :producer-in-measured-window false
               :publication-overlap-directly-timestamped false
               :batch-size n :rounds rounds :warmups warmups
               :raw-queue-capacity (* n (+ rounds warmups))
               :max-insert-bytes (* 16 1024 1024) :rows (* n rounds)
               :stored-rows (* n (+ rounds warmups)) :samples all :producer @producer
               :stats stats :before before :after after :confirmed-window-nanos elapsed
               :confirmed-rows-per-second (/ (* 1e9 n rounds) elapsed)
               :cadence-p50-p90-p95-p99-ms (quantiles-ms cadence)
               :processing-p50-p90-p95-p99-ms (quantiles-ms processing)
               :queue-inclusive-p50-p90-p95-p99-ms (quantiles-ms queued)
               :estimated-bytes-per-row
               (/ (- (+ (:heap after) (:gc-bytes after)) (+ (:heap before) (:gc-bytes before)))
                  (double (* n rounds)))
               :counter-snapshots-non-atomic true :population-tail-qualified false
               :diagnostic-printing-in-window true
               :independent-recovery-qualified false :native-package "26.9.0"}))))

(defn -main [& [root arm n-text rounds-text warmups-text mode-text]]
  (let [n (Long/parseLong (or n-text "5000"))
        rounds (Long/parseLong (or rounds-text "32"))
        warmups (Long/parseLong (or warmups-text "3")) total (+ rounds warmups)
        mode (or mode-text "source-functional")]
    (ensure! (and root (.isAbsolute (java.io.File. root))) :absolute-owned-root)
    (ensure! (not (.exists (java.io.File. (str root "/objects")))) :fresh-store)
    (ensure! (contains? #{"synchronous" "overlap"} arm) :arm)
    (ensure! (contains? #{"source-functional" "release"} mode) :execution-mode)
    (ensure! (and (<= 1 n 10000) (<= 2 rounds 64) (<= 1 warmups 5)) :bounded-geometry)
    (native/ensure-loaded!) (ensure! (= "26.9.0" (native/chdb-version)) :native-version)
    (.mkdirs (java.io.File. (str root "/scratch-writer")))
    (with-open [connection
                (jdbc/connection
                  (durable/writer-dbspec
                    {:namespace-backend (local/local-backend (str root "/objects"))
                     :object-id "typed-compact-prefix" :scratch-parent (str root "/scratch-writer")
                     :owner "typed-compact-prefix" :instance "writer-1" :database "otel"
                     :owned-compact-stream? true :lease-ttl-ms 900000
                     :heartbeat-interval-ms 300000 :operations {}}))]
      (let [capability (fixture/setup! connection)
            _ (ensure! (= :committed (:status (durable/checkpoint! connection))) :schema-checkpoint)
            target (exporter/exporter
                     {:connection connection :durable? true :create-schema? false
                      :datetime64-wire :raw-ticks :max-insert-bytes (* 16 1024 1024)
                      :signals #{:spans} :typed-span-descriptors capability
                      :insert-format :json-compact-each-row :json-backend :native-guarded-byte-batch
                      :owned-statement-output? true :experimental-span-codec? true
                      :experimental-span-payload-cache? true :experimental-span-flat-map-cache? true
                      :experimental-span-nested-fragment-cache? true :experimental-span-shape-plans? true
                      :experimental-span-scalar-views? true})
            samples (atom []) producer (atom []) anchors (atom {}) active (atom {}) queued (promise)
            observed (->ObservedExporter target samples n queued warmups total anchors)
            base {:max-export-batch-size n :max-queue-size (* n total) :schedule-delay-ms 60000}
            config (if (= arm "overlap")
                     (let [config (exporter/experimental-span-batch-options target base)]
                       (update config :experimental-span-preparation
                         #(observed-preparation % samples active n queued warmups total anchors)))
                     base)
            rows (fixture/fixture n)
            owner (sdk/batch-processor observed config)]
        (try
          (System/gc)
          (dotimes [index total]
            (let [start (System/nanoTime)]
              (doseq [span rows] (sdk/on-end owner span))
              (swap! producer conj {:index index :started start :queued (System/nanoTime)})))
          (deliver queued true)
          (let [deadline (+ (System/nanoTime) 120000000000)]
            (loop []
              (let [stats (sdk/batch-processor-stats owner)]
                (ensure! (and (zero? (:failed-span-count stats)) (zero? (:dropped-count stats))) :no-sdk-loss)
                (when-not (= (* n total) (:exported-span-count stats))
                  (ensure! (< (System/nanoTime) deadline) :completion-deadline)
                  (Thread/sleep 5) (recur)))))
          (ensure! (true? (sdk/force-flush! owner)) :confirmed-flush)
          (ensure! (true? (sdk/shutdown! owner)) :normal-sdk-shutdown)
          (ensure! (empty? @active) :all-capabilities-retired)
          (let [stored (:n (jdbc/fetch-one connection "SELECT count() AS n FROM otel_traces"))]
            (ensure! (= (* n total) stored) :stored-count))
          (report! root arm mode n rounds warmups samples producer anchors (sdk/batch-processor-stats owner))
          (println :sdk-preparation-screen-writer-complete arm)
          (finally
            (deliver queued true)
            (sdk/shutdown! owner)))))))
