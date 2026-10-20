(require '[clojure.string :as str])
(defn replace-once [source needle replacement]
  (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
  (str/replace source needle replacement))
(let [source (slurp "/home/chuck/ai-src/evidence/summarize-private-prepared-wal-worker-profile-20261019.bb")
      changes [[":scope :single-lane-private-wal-phase-diagnostic-summary"
                ":scope :cumulative-flat-map-phase-diagnostic-summary"]
               [":p95-ms (/ (pct (map :nanos xs) 0.95) 1e6)"
                ":p90-ms (/ (pct (map :nanos xs) 0.90) 1e6) :p95-ms (/ (pct (map :nanos xs) 0.95) 1e6) :p99-ms (/ (pct (map :nanos xs) 0.99) 1e6)"]]]
  (load-string (reduce (fn [text [needle replacement]] (replace-once text needle replacement)) source changes)))
