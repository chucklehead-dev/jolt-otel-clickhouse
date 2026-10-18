(require '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture]
         '[otel.exporter.chdb :as e])
(load-file "/home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj")
(defn unique-row-keys? [spans]
  (= (count spans) (count (set (map (fn [span]
                                    [(get-in span [:span-context :trace-id])
                                     (get-in span [:span-context :span-id])]) spans)))))
(let [spans (otel.research.trace-affinity/group-traces
              (with-redefs [fixture/batch-size 5000] (fixture/spans "typed-compact-prefix")))
      mutated (assoc-in spans [1 :span-context :span-id] (get-in spans [0 :span-context :span-id]))]
  (assert (= 5000 (count spans)))
  (assert (unique-row-keys? spans))
  (assert (not (unique-row-keys? mutated)))
  (doseq [span spans]
    (let [row (#'e/span-row span nil)
          sampled (#'e/span-row (assoc-in span [:span-context :trace-flags] 1) nil)]
      (assert (= row sampled))
      (assert (not (contains? row "TraceFlags")))))
  (prn {:actual-fixture-records 5000 :unique-sibling-sort-keys true
        :duplicate-key-negative-control-rejected true
        :sampled-bit-does-not-change-physical-span-row true
        :trace-flags-not-claimed-persisted true :no-native-or-store true}))
