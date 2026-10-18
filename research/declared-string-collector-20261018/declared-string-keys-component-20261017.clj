(require '[clojure.java.io :as io] '[clojure.data.json :as json]
         '[jolt.scheme :as scheme]
         '[otel.exporter.chdb.attribute-projection :as p]
         '[otel.exporter.chdb-benchmark :as bench])

(let [parent (scheme/eval-string
               (slurp "/home/chuck/ai-src/worktrees/exporter-literal-span-enums-20261017/src/otel/exporter/chdb/native_declared_attributes.ss"))
      candidate (scheme/eval-string
                  (slurp (io/resource "otel/exporter/chdb/native_declared_attributes.ss")))
      original @#'p/original-key-string
      ;; Match the maintained loader: it already captures stable Var identities.
      ;; Quoted Vars inside the timed control would measure repeated Var lookup
      ;; rather than the real parent's four-argument native invocation.
      key-var #'p/key-string contains-var #'clojure.core/contains?
      bound-candidate (candidate key-var contains-var original)
      bound-generic (candidate key-var contains-var false)
      input (mapv (fn [i]
                    (into {"typed" i "ok" false "name" "synthetic" "other" "value"}
                          (map (fn [j] [(str "extra-" j) (case (mod j 3) 0 "value" 1 false j)]))
                          (range 12))) (range 1000))
      wanted #{"typed" "ok"}
      before (fn [m] (parent m wanted key-var contains-var))
      after (fn [m] (bound-candidate m wanted))
      bound-only (fn [m] (bound-generic m wanted))
      run (fn [f]
            (reduce (fn [sum m] (+ sum (count (f m)))) 0 input))]
  ;; Exact serialization also checks traversal/order, not just map equality.
  (assert (= (mapv #(json/write-str (before %)) input)
             (mapv #(json/write-str (bound-only %)) input)
             (mapv #(json/write-str (after %)) input)))
  (dotimes [_ 3] (assert (= 2000 (run before))) (assert (= 2000 (run after))))
  (let [samples
        (mapv (fn [arm]
                (let [a (#'bench/counter-sample) start (System/nanoTime)
                      checksum (run (case arm :control before :bound-only bound-only :candidate after))
                      nanos (- (System/nanoTime) start)
                      b (#'bench/counter-sample)]
                  (assert (= 2000 checksum))
                  {:arm arm :nanos nanos :counter-delta (#'bench/counter-delta a b)}))
              (take 36 (cycle [:control :bound-only :candidate :candidate :bound-only :control])))]
    (spit (first *command-line-args*)
          (pr-str {:scope :prepared-typed-collector-not-ingestion
                   :rows-per-sample 1000 :exact-stock-wire-equal true :samples samples}))
    (doseq [[arm xs] (group-by :arm samples)
            :let [times (vec (sort (map :nanos xs)))
                  pct (fn [q] (/ (double (nth times (dec (long (Math/ceil (* q (count times))))))) 1e6))]]
      (prn {:arm arm :p50-ms (pct 0.5) :p90-ms (pct 0.9) :p95-ms (pct 0.95) :p99-ms (pct 0.99)
            :allocated-B-per-row
            (/ (double (reduce + (map #(get-in % [:counter-delta :scheme-heap-bytes-allocated]) xs)))
               (* 1000 (count xs)))}))))
