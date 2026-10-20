(require '[clojure.edn :as edn])
(let [[prefix] *command-line-args*
      w (edn/read-string (slurp (str prefix ".writer.edn")))
      r (edn/read-string (slurp (str prefix ".reader.edn")))
      arm (:native-floor-arm w)]
  (assert (contains? #{"sdk" "native"} arm))
  (assert (:matched-runtime-source w))
  (assert (and (pos? (:statement-bytes w)) (pos? (:prepared-wal-bytes w))
               (re-matches #"[0-9a-f]{64}" (:statement-sha256 w))))
  (assert (and (:passed r) (:full-physical-rows-equal r) (:typed-values-status r)
               (= [175000 5000 625 35] (mapv r [:rows :groups :trace-count :per-record-multiplicity]))))
  (if (= "sdk" arm)
    (let [text (binding [*command-line-args* [prefix "on"]]
                 (with-out-str
                   (load-file "/home/chuck/ai-src/worktrees/exporter-declared-string-evidence-20261018/research/fork-free-codec-20261019/validate-durable.clj")))]
      (assert (:verified (edn/read-string text))))
    (do
      (assert (= [160000 5000 32 3 1 35 true false false]
                 (mapv w [:rows :batch-size :rounds :warmups :capture-calls :stream-calls
                          :preencoded-native-floor? :sdk-queue-measured? :encoding-and-wal-precompute-measured?])))
      (assert (= 32 (count (:batches w))))
      (assert (every? #(and (= 5000 (:rows %)) (:ok %)
                           (contains? #{:committed :reconciled} (:status %)) (pos? (:nanos %))) (:batches w)))
      (assert (every? (fn [[a b]] (<= (:end-nanos a) (:start-nanos b))) (partition 2 1 (:batches w))))))
  (prn {:verified true :scope :matched-sdk-versus-preencoded-native-floor
        :arm arm :recovered-rows (:rows r) :old-stack-independent-full-recovery true
        :confirmed-rows-per-second (:confirmed-rows-per-second w)
        :statement-sha256 (:statement-sha256 w) :statement-bytes (:statement-bytes w)
        :matched-across-processes-not-yet-checked true :target-qualified false :tail-qualified false}))
