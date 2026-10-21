(ns perf.compact-phase-costs
  "Research-only coarse inclusive phase counters on the existing confirmed app.
  No per-row/field probes, input retention, SDK policy or publication override."
  (:require [perf.public-pipeline :as app]
            [otel.exporter.chdb :as exporter]
            [jolt.codec.experimental :as codec]
            [jdbc.chdb.owned-statement :as owned]
            [jolt.host :as host]
            [clojure.edn :as edn]))

(def ^:dynamic *sample* nil)

(defn snapshot []
  ;; Separate coarse snapshots, not an atomic tuple or allocation-site profile.
  {:heap (host/bytes-allocated) :gc-bytes (host/gc-bytes)
   :gc-count (host/gc-count) :gc-nanos (host/gc-real-nanos)
   :cpu-nanos (host/cpu-nanos)})

(defn measure [sink phase operation]
  (if (nil? *sample*)
    (operation)
    (let [before (snapshot) started (System/nanoTime)
          value (operation)
          elapsed (- (System/nanoTime) started) after (snapshot)
          sample *sample*
          receipt {:nanos elapsed
                   :estimated-allocated-bytes
                   (- (+ (:heap after) (:gc-bytes after))
                      (+ (:heap before) (:gc-bytes before)))
                   :cpu-nanos (- (:cpu-nanos after) (:cpu-nanos before))
                   :gc-nanos (- (:gc-nanos after) (:gc-nanos before))
                   :gc-count (- (:gc-count after) (:gc-count before))}]
      (assert (nil? (get-in @sink [sample phase])) [sample phase :duplicate-phase])
      (swap! sink assoc-in [sample phase] receipt)
      value)))

(defn -main [& [root]]
  (let [samples (atom {}) ordinal (atom -1) publications (atom 0)
        prepare @#'exporter/prepare-closed-compact-rows
        publish @#'exporter/publish-prepared-compact-insert!
        encode codec/encode-prefixed-records
        snapshot-statement owned/try-snapshot
        wal owned/precompute-wal]
    ;; Private boundary probes make this a diagnostic, NOT a release throughput
    ;; comparison. Every original operation executes exactly once and serially.
    (with-redefs [exporter/prepare-closed-compact-rows
                  (fn [& args]
                    (let [sample (swap! ordinal inc)]
                      (assert (< sample 35))
                      (binding [*sample* sample]
                        (measure samples :prepare-total #(apply prepare args)))))
                  exporter/publish-prepared-compact-insert!
                  (fn [prepared]
                    (let [sample @ordinal]
                      (assert (= sample @publications))
                      (binding [*sample* sample]
                        (let [result (measure samples :publish-confirmed #(publish prepared))]
                          (swap! publications inc)
                          result))))
                  codec/encode-prefixed-records
                  (fn [& args]
                    (measure samples :normalize-project-encode #(apply encode args)))
                  owned/try-snapshot
                  (fn [bytes]
                    (measure samples :owned-snapshot #(snapshot-statement bytes)))
                  owned/precompute-wal
                  (fn [statement]
                    (measure samples :wal-preparation #(wal statement)))]
      (app/-main root "release" "view"))
    (assert (= 34 @ordinal))
    (assert (= 35 @publications))
    (assert (= (set (range 35)) (set (keys @samples))))
    (let [phases #{:prepare-total :publish-confirmed :normalize-project-encode
                   :owned-snapshot :wal-preparation}
          writer (edn/read-string (slurp (str root "/writer.edn")))]
      (assert (= [175000 5000 32 3]
                 (mapv writer [:stored-rows :batch-size :rounds :warmups])))
      (doseq [[_ observation] @samples]
        (assert (= phases (set (keys observation))))
        (assert (every? #(pos? (:nanos %)) (vals observation))))
      (spit (str root "/phase-costs.edn")
        (pr-str {:scope :research-inclusive-compact-phase-costs
                 :base "f0aa75dd595dff82290fb2c19389d2a6ad02c1e6"
                 :measured-samples (mapv #(assoc (get @samples %) :round (- % 3)) (range 3 35))
                 :warmup-count 3 :completed-preparations 35 :confirmed-publications 35
                 :counter-snapshots-non-atomic true :private-boundary-probes true
                 :per-row-probes false :causal-throughput-qualified false
                 :sdk-queue-used false :production-default-changed false})))
    (println :compact-phase-costs-complete :preparations 35 :confirmed-publications 35)))
