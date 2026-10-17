(ns otel.exporter.chdb-native-projection-benchmark
  "Bounded same-process pair/direct-result comparison; no database or network."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.native-projection :as native]
            [otel.exporter.chdb-benchmark :as counters]
            [otel.exporter.chdb-typed-compact-vector-test :as plans]
            [otel.exporter.chdb-attribute-projection-test :as fixture]))

(defn- quantile [values p]
  (let [xs (vec (sort values))]
    (/ (double (nth xs (dec (int (Math/ceil (* p (count xs))))))) 1e6)))

(defn -main [& [output row-count cycle-count]]
  (let [output (or output "target/native-projection-benchmark.edn")
        rows (parse-long (or row-count "1000"))
        cycles (parse-long (or cycle-count "24"))
        _ (when-not (and rows cycles (<= 1 rows 10000) (<= 1 cycles 128))
            (throw (ex-info "Expected 1..10000 rows and 1..128 ABBA cycles" {})))
        {:keys [capability target vector-projector]} (plans/plans false)
        indexed (projection/trace-vector-projector capability target true)
        ;; Capture handles ONCE, exactly as the production factories do. A Var
        ;; literal inside the measured loop adds unrelated lookup/allocation.
        project-var #'projection/projected-value
        original @#'projection/original-projected-value
        counter-sample @#'counters/counter-sample
        counter-delta @#'counters/counter-delta
        make-span @#'fixture/span
        _ (assert (identical? @project-var original))
        operations {:pairs (delay (native/load-positional-output! project-var))
                    :direct (delay (native/load-positional-output! project-var original))}
        input (mapv (fn [i]
                      (make-span
                        (into {"checkout.count" i "checkout.complete" false "checkout.name" ""}
                              (map (fn [j] [(str "extra-" j)
                                            (case (mod j 3) 0 "value" 1 false j)]))
                              (range 13)))) (range rows))
        expected (mapv vector-projector input)
        expected-json (json/write-str expected)
        run! #(mapv indexed input)
        parity! (fn []
                  (doseq [[_ operation] operations]
                    (with-redefs [projection/owned-plan-output operation]
                      (assert (= expected (run!)))
                      (assert (= expected-json (json/write-str (run!)))))))]
    (parity!)
    (dotimes [_ 4]
      (doseq [[_ operation] operations]
        (with-redefs [projection/owned-plan-output operation]
          (assert (= rows (count (run!)))))))
    (let [last-result (volatile! nil)
          samples (mapv
                    (fn [arm]
                      (with-redefs [projection/owned-plan-output (get operations arm)]
                        (let [before (counter-sample) start (System/nanoTime)
                              result (run!) elapsed (- (System/nanoTime) start)
                              after (counter-sample)]
                          (assert (= rows (count result)))
                          (vreset! last-result result)
                          {:arm arm :nanos elapsed
                           :counter-delta (counter-delta before after)})))
                    (take (* 4 cycles) (cycle [:pairs :direct :direct :pairs])))
          summaries
          (mapv (fn [[arm values]]
                  {:arm arm :samples (count values)
                   :latency-ms (into {} (map (fn [[k p]] [k (quantile (map :nanos values) p)])
                                            [[:p50 0.5] [:p90 0.9] [:p95 0.95] [:p99 0.99]]))
                   :allocated-B-per-row
                   (/ (double (reduce + (map #(get-in % [:counter-delta :scheme-heap-bytes-allocated]) values)))
                      (* rows (count values)))})
                (sort-by (comp str key) (group-by :arm samples)))
          report {:scope :typed-projection-component-not-throughput
                  :rows-per-sample rows :fields 3 :abba-cycles cycles
                  :jolt-version (System/getProperty "jolt.version")
                  :exact-portable-vector-and-json-equal true
                  :tail-qualified? false :summaries summaries :samples samples}]
      (parity!)
      (assert (= expected @last-result))
      (when-let [parent (.getParentFile (io/file output))] (.mkdirs parent))
      (spit output (pr-str report))
      (doseq [summary summaries] (prn summary)))))
