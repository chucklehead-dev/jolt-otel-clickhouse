;; Diagnostic wrappers around existing coarse boundaries. No JSONWriter,
;; attribute-policy, classifier, authority or persistence behavior is replaced.
(require '[jdbc.chdb :as driver] '[jdbc.chdb.native :as native]
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
      observer (fn [event]
                 (when-let [stamp @context]
                   ;; Only the production hook's closed scalar vocabulary.
                   (assert (contains? #{:wal-prepare :wal-append :wal-join} (:phase event)))
                   (record! (merge stamp (select-keys event [:phase :status :calls :nanos :bytes])
                                   {:thread (.getName (Thread/currentThread))}))))]
  (with-redefs [durable/writer-dbspec
                (fn [options]
                  (assert (nil? (get-in options [:operations :writer-phase!])))
                  (writer-dbspec (assoc-in options [:operations :writer-phase!] observer)))
                export/export-spans!
                (fn [owner spans]
                  (let [call (swap! calls inc)]
                    (assert (<= call 35))
                    (reset! context (when (> call 3) {:ordinal (- call 4)}))
                    (try (export-spans owner spans)
                         (finally (reset! context nil)))))
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
    (load-file "/home/chuck/ai-src/evidence/rowbinary-rich-direct-writer-20261018.clj"))
  (assert (= 35 @calls))
  (doseq [phase [:native-classify :native-stream :wal-publish :head-commit
                 :wal-prepare :wal-append :wal-join]]
    (let [observed (filter #(= phase (:phase %)) @events)]
      (assert (= 32 (count observed)))
      (assert (= (vec (range 32)) (mapv :ordinal observed)))
      (assert (every? #(= :complete (:status %)) observed))))
  (spit (str (first *command-line-args*) "/worker-profile.edn")
        (pr-str {:scope :diagnostic-worker-phase-attribution
                 :public-json-writer-roots-unchanged true
                 :default-native-executor-preserved true
                 :samples 32 :events @events :nested-phase-labels #{:file-digest}
                 :tail-qualified false})))
