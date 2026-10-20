;; One-lane diagnostic only, using the existing coarse observer unchanged.
;; Do not treat the instrumented window as a speedup or add inclusive phases.
(require '[clojure.string :as str])
(defn replace-once [source needle replacement]
  (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
  (str/replace source needle replacement))
(let [[root arm] *command-line-args*
      _ (assert (= "on" arm))
      source (slurp "/home/chuck/ai-src/evidence/private-prepared-wal-worker-profile-20261019.clj")
      changes [["/home/chuck/ai-src/evidence/stock-library-codec-durable-writer-20261019.clj"
                "/home/chuck/ai-src/evidence/flat-map-wire-cache-durable-arm-20261019.clj"]
               ["{#'*command-line-args* [root]}" "{#'*command-line-args* [root \"on\"]}"]
               [":scope :single-lane-private-wal-phase-diagnostic"
                ":scope :single-lane-cumulative-flat-map-phase-diagnostic :flat-map-wire-cache-arm \"on\""]]
      staged (reduce (fn [text [needle replacement]] (replace-once text needle replacement)) source changes)]
  (with-bindings {#'*command-line-args* [root "cached"]} (load-string staged)))
