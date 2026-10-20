;; Run from the older reader worktree, on the same official stock runtime.
(require '[clojure.string :as str]
         '[otel.exporter.chdb-ordinary-transport-benchmark :as fixture])
(load-file "/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019/research/library-nested-text-20261019/nested-sorted-sdk-fixture-20261018.clj")
(let [base "/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019/research/library-nested-text-20261019/"
      source (slurp (str base "nested-sorted-sdk-physical-reader-20261018.clj"))
      needle "/home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj"
      _ (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
      staged (str/replace source needle (str base "trace-affinity-scaling-helpers-20261019.clj"))
      original fixture/spans]
  (with-redefs [fixture/spans (fn [region] (nested-sorted-sdk-shape (original region)))]
    (load-string staged)))
