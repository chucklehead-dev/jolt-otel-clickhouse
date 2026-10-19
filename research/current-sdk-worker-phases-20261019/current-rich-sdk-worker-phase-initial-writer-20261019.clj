;; Diagnostic wrappers around existing coarse boundaries. No JSONWriter,
;; attribute-policy, classifier, authority or persistence behavior is replaced.
(require '[clojure.data.json.jolt-native :as json-native]
         '[jdbc.chdb :as driver] '[jdbc.chdb.native :as native]
         '[jdbc.chdb.durable :as durable]
         '[jdbc.chdb.durable.control :as control]
         '[jdbc.chdb.durable.digest :as digest]
         '[otel.sdk.export :as export]
         '[otel.exporter.chdb-benchmark :as bench])
(assert (= "json" (System/getenv "RICH_FORMAT_ARM")))
(let [context (atom nil) calls (atom 0) events (atom [])
      sample #'bench/counter-sample delta #'bench/counter-delta
      record! (fn [event]
                (assert (< (count @events) 1024))
                (swap! events conj event))
      around (fn [phase f]
               (fn [& args]
                 (if-let [stamp @context]
                   (let [thread (.getName (Thread/currentThread))
                         before (sample) start (System/nanoTime)
                         result (apply f args)
                         end (System/nanoTime) after (sample)]
                     (record! (assoc stamp :phase phase :status :complete
                                     :nanos (- end start) :thread thread
                                     :counters (delta before after)))
                     result)
                   (apply f args))))
      writer-dbspec durable/writer-dbspec
      export-spans export/export-spans!
      observed-export-spans (around :callback export-spans)
      observer (fn [event]
                 (when-let [stamp @context]
                   ;; Only the production hook's closed scalar vocabulary.
                   (assert (contains? #{:wal-prepare :wal-append :wal-join
                                        :wal-immutable-put :wal-immutable-verify :wal-head-cas}
                                      (:phase event)))
                   (record! (merge stamp (select-keys event [:phase :status :calls :nanos :bytes])
                                   {:thread (.getName (Thread/currentThread))}))))]
  (with-redefs [durable/writer-dbspec
                (fn [options]
                  ;; JDBC normalization invokes writer-dbspec a second time.
                  ;; Accept our own already-installed observer, never replace
                  ;; a different caller's hook or append a duplicate wrapper.
                  (assert (or (nil? (get-in options [:operations :writer-phase!]))
                              (identical? observer (get-in options [:operations :writer-phase!]))))
                  (writer-dbspec (assoc-in options [:operations :writer-phase!] observer)))
                export/export-spans!
                (fn [owner spans]
                  (let [call (swap! calls inc)]
                    (assert (<= call 35))
                    (reset! context (when (> call 3) {:ordinal (- call 4)}))
                    (try (observed-export-spans owner spans)
                         (finally (reset! context nil)))))
                json-native/write-prefixed-batch-bytes!
                (around :projection-and-encoding json-native/write-prefixed-batch-bytes!)
                durable/execute-owned-and-flush!
                (around :native-and-confirm durable/execute-owned-and-flush!)
                native/classify-query-buffer!
                (around :native-classify native/classify-query-buffer!)
                driver/execute-owned-compact-json-stream-with-query-buffer
                (around :native-stream driver/execute-owned-compact-json-stream-with-query-buffer)
                control/publish-wal-file!
                (around :wal-publish control/publish-wal-file!)
                control/commit-reference!
                (around :head-commit control/commit-reference!)
                digest/sha256-file
                (around :file-digest digest/sha256-file)]
    (load-file "/home/chuck/ai-src/evidence/exporter-singleton-sdk-durable-writer-20261019.clj"))
  (assert (= 35 @calls))
  (doseq [phase [:callback :projection-and-encoding :native-and-confirm
                 :native-classify :native-stream :wal-publish :head-commit
                 :wal-prepare :wal-append :wal-join]]
    (let [observed (filter #(= phase (:phase %)) @events)]
      (assert (= 32 (count observed)))
      (assert (= (vec (range 32)) (mapv :ordinal observed)))
      (assert (every? #(= :complete (:status %)) observed))))
  (spit (str (first *command-line-args*) "/worker-profile.edn")
        (pr-str {:scope :current-real-sdk-worker-correlated-phase-attribution
                 :runtime "7098a8d6" :exporter "2c54b98" :json "2172061" :sdk "7878a2e"
                 :full-callback-and-outer-boundaries true
                 :public-json-writer-roots-unchanged true
                 :default-native-executor-preserved true
                 :samples 32 :events @events :nested-phase-labels #{:file-digest}
                 :tail-qualified false})))
