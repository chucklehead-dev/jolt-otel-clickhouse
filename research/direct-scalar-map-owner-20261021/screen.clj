;; Declared before timing: baseline/direct/direct/baseline,16 observations/arm.
;; Both use the SAME candidate SDK+codec; only the explicit SDK option differs.
(load-file "research/direct-scalar-map-owner-20261021/context.clj")
(let [contexts {:baseline (owner-context false) :direct (owner-context true)}
      encode (rich-encoder contexts) rows (app/fixture 5000)
      expected (encode :baseline rows)
      _ (assert (bytes-equal? expected (encode :direct rows)))
      _ (doseq [changed [(assoc-in rows [0 :attributes "count"] -42)
                         (assoc-in rows [0 :events 0 :name] "changed-event")]]
          (assert (not (bytes-equal? expected (encode :direct changed))))
          (assert (bytes-equal? (encode :baseline changed) (encode :direct changed))))
      order [:baseline :direct :direct :baseline]
      _ (doseq [arm order] (encode arm rows))
      observations
      (mapv (fn [ordinal]
              (let [arm (nth order (mod ordinal 4))
                    before (counters) start (System/nanoTime)
                    output (encode arm rows) elapsed (- (System/nanoTime) start)
                    after (counters)]
                (assert (bytes-equal? expected output))
                {:ordinal ordinal :arm arm :nanos elapsed
                 :estimated-bytes (- (+ (:heap after) (:gc-bytes after))
                                     (+ (:heap before) (:gc-bytes before)))
                 :thread-cpu-nanos (- (:thread-cpu after) (:thread-cpu before))
                 :gc-count (- (:gc-count after) (:gc-count before))
                 :gc-nanos (- (:gc-nanos after) (:gc-nanos before))})) (range 32))
      summary (into {} (for [[arm samples] (group-by :arm observations)]
                         [arm {:p50-p90-p95-p99-ms
                               (mapv #(/ (quantile (map :nanos samples) %) 1e6) [0.5 0.9 0.95 0.99])
                               :median-estimated-bytes-per-row
                               (/ (quantile (map :estimated-bytes samples) 0.5) 5000.0)}]))]
  (spit (first *command-line-args*)
    (pr-str {:scope :rich-sdk-owned-direct-scalar-map-screen :rows 5000 :order order
             :samples-per-arm 16 :sdk-original-eight-anchors true :typed-projection true
             :full-output-bytes-equal true :changed-attribute-and-event-controls true
             :output-bytes (alength expected) :summary summary :observations observations
             :private-exporter-var-component-probe true :sdk-queue false
             :durable-io false :tail-qualified false :fork-free-application false}))
  (prn summary))
