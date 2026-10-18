;; Research only: partitioned ended spans, not a new supported SDK processor.
;; One serial SDK worker per DISTINCT exporter; one borrowed connection owner.
(require '[db.jdbc] '[jdbc.core :as jdbc] '[clojure.java.io :as io]
         '[jdbc.chdb.durable :as durable] '[jdbc.chdb.native :as native]
         '[clojure.data.json.jolt-native :as json-native]
         '[otel.sdk.export :as export] '[otel.trace :as trace]
         '[otel.sdk.lifecycle :as lifecycle]
         '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb-benchmark :as bench]
         '[jdbc.chdb-typed-storage-support :as storage])

(defn ensure! [condition label]
  (when-not condition (throw (ex-info "Partitioned SDK screen failed" {:label label}))))

(let [[root lanes-text] *command-line-args*
      lanes (parse-long lanes-text) n 5000 rounds 32 total (* n rounds)
      _ (ensure! (contains? #{1 2} lanes) :bounded-lanes)
      _ (ensure! (= :local (storage/arm)) :local-only)
      queue-capacity (quot (* 2 n) lanes)
      spans (mapv (fn [span]
                    (update (assoc-in span [:span-context :trace-flags] 1) :attributes into
                            (map (fn [i] [(str "extra-" i)
                                          (case (mod i 3) 0 "value" 1 false i)]))
                            (range 12)))
                  (with-redefs [fixture/batch-size n] (fixture/spans "typed-compact-prefix")))
      stream-calls (atom 0) actual-stream native/chdb-stream-insert-n
      batches (atom [])]
  (ensure! (every? #(trace/sampled? (:span-context %)) spans) :sampled-fixture)
  (native/ensure-loaded!)
  (ensure! (= "26.9.0" (native/chdb-version)) :native-version)
  (with-redefs [native/chdb-stream-insert-n
                (fn [& args] (swap! stream-calls inc) (apply actual-stream args))]
    (let [conn (jdbc/connection
                      (durable/writer-dbspec
                        (merge (storage/options root "writer")
                               {:owner "typed-compact-prefix" :instance "writer-1"
                                :database "otel" :owned-compact-stream? true
                                :lease-ttl-ms 900000 :heartbeat-interval-ms 300000
                               :operations {:json-reader (json-native/load-reader!)}})))]
      (let [{:keys [capability]} (fixture/setup conn)
            _ (ensure! (= :committed (:status (durable/checkpoint! conn))) :schema-checkpoint)
            owners (atom []) processors (atom [])]
        (try
          ;; Record each acquired owner immediately so partial setup cleans up.
          (dotimes [lane lanes]
            (let [owner (e/exporter {:connection conn :durable? true :create-schema? false
                                     :datetime64-wire :raw-ticks :max-insert-bytes 67108864
                                     :signals #{:spans} :typed-span-descriptors capability
                                     :insert-format :json-compact-each-row
                                     :json-backend :native-guarded-byte-batch
                                     :owned-statement-output? true})]
              (swap! owners conj owner)))
          (dotimes [_ 3]
            (ensure! (true? (export/export-spans! (first @owners) spans)) :warmup))
          (dotimes [lane lanes]
            (let [owner (nth @owners lane)
                  borrowed (reify export/SpanExporter
                             (export-spans! [_ values]
                               (ensure! (< (count @batches) 256) :bounded-scalar-events)
                               (let [start (System/nanoTime)
                                     ok (export/export-spans! owner values)
                                     end (System/nanoTime)]
                                 (swap! batches conj {:lane lane :rows (count values)
                                                      :ok (true? ok)
                                                      :start-nanos start :end-nanos end
                                                      :nanos (- end start)})
                                 ok))
                             (flush-exporter! [_] (export/flush-exporter! owner))
                             (shutdown-exporter! [_] true))]
              (swap! processors conj
                     (export/batch-processor borrowed
                       {:max-queue-size queue-capacity :max-export-batch-size n
                        :schedule-delay-ms 100}))))
          (System/gc)
          (let [before (#'bench/counter-sample) start (System/nanoTime)
                deadline (+ start 120000000000) throttled (atom 0)]
            (dotimes [round rounds]
              (let [processor (nth @processors (mod round lanes))]
                (doseq [span spans]
                  (loop []
                    (ensure! (< (System/nanoTime) deadline) :producer-deadline)
                    (when (>= (export/queue-size processor) queue-capacity)
                      (let [begin (System/nanoTime)]
                        (Thread/sleep 1)
                        (swap! throttled + (- (System/nanoTime) begin)))
                      (recur)))
                  (export/on-end processor span))))
            (let [producer-done (System/nanoTime)]
              (loop []
                (let [stats (mapv export/batch-processor-stats @processors)]
                  (ensure! (every? #(zero? (:failed-span-count %)) stats) :no-failed-export)
                  (ensure! (every? #(zero? (:dropped-count %)) stats) :no-overflow)
                  (when (< (reduce + (map :exported-span-count stats)) total)
                    (ensure! (< (System/nanoTime) deadline) :completion-deadline)
                    (Thread/sleep 1)
                    (recur))))
              (let [end (System/nanoTime) after (#'bench/counter-sample)
                    stats (mapv export/batch-processor-stats @processors)]
                (ensure! (= total (reduce + (map :attempted-span-count stats))
                            (reduce + (map :exported-span-count stats))
                            (reduce + (map :rows @batches))) :row-accounting)
                (ensure! (every? #(zero? (:queue-size %)) stats) :empty-queues)
                (ensure! (every? :ok @batches) :confirmed-callbacks)
                (doseq [[_ events] (group-by :lane @batches)]
                  (let [ordered (sort-by :start-nanos events)]
                    (ensure! (every? (fn [[a b]] (<= (:end-nanos a) (:start-nanos b)))
                                     (partition 2 1 ordered)) :serial-per-exporter)))
                (ensure! (= (+ 3 (count @batches)) @stream-calls) :actual-native-streams)
                (ensure! (= (* n (+ 3 rounds))
                            (:n (jdbc/fetch-one conn "SELECT count() AS n FROM otel_traces"))) :writer-count)
                (spit (str root "/writer.edn")
                      (pr-str {:scope :partitioned-sdk-consumer-research-not-public-mode
                               :lanes lanes :distinct-exporter-count (count @owners)
                               :serial-worker-per-exporter true :shared-connection-owner-count 1
                               :batch-size n :rounds rounds :rows total :warmups 3
                               :queue-capacity-per-lane queue-capacity
                               :total-queue-capacity (* lanes queue-capacity)
                               :schedule-delay-ms 100 :stats stats :batches @batches
                               :stream-calls @stream-calls
                               :sdk-source (str (io/resource "otel/sdk/export.clj"))
                               :load-generator-capacity-wait-nanos @throttled
                               :producer-nanos (- producer-done start)
                               :confirmed-window-nanos (- end start)
                               :confirmed-rows-per-second (/ (double (* total 1000000000)) (- end start))
                               :counter-delta (#'bench/counter-delta before after)
                               :force-flush-before-measurement false :tail-qualified false})))))
          (finally
            ;; Quarantine rather than close a connection whose SDK users lack
            ;; stable retirement proof. The outer bounded process will fail;
            ;; its native store is retained rather than counted/cleaned as pass.
            (let [errors (atom [])]
              (doseq [processor @processors]
                (try (ensure! (true? (export/shutdown! processor)) :processor-shutdown)
                     (catch Throwable error (swap! errors conj error))))
              (ensure! (every? #(= :confirmed (:sdk-quiescence
                                                (lifecycle/component-settlement %)))
                               @processors) :all-sdk-users-settled-before-release)
              (let [released (atom 0)]
                (doseq [owner @owners]
                  (try (ensure! (true? (export/shutdown-exporter! owner)) :exporter-shutdown)
                       (swap! released inc)
                       (catch Throwable error (swap! errors conj error))))
                (ensure! (= (count @owners) @released) :all-exporters-released-before-close)
                (.close conn))
              (when (seq @errors) (throw (first @errors))))))))))
