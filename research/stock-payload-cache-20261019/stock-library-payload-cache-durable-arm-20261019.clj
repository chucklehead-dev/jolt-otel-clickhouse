;; Same SDK/Durable workload and private WAL precompute in both arms; only the
;; explicit public payload-cache option differs. No production Var replacement.
(require '[clojure.string :as str] '[clojure.edn :as edn])
(let [[root arm] *command-line-args*
      _ (assert (contains? #{"off" "on"} arm))
      source (slurp "/home/chuck/ai-src/evidence/stock-library-codec-durable-writer-20261019.clj")
      needle ":experimental-span-codec? true"
      _ (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
      staged (str/replace source needle
                          (str needle " :experimental-span-payload-cache? " (= arm "on")))]
  (spit (str root "/cache-selector.clj") staged)
  (load-string staged)
  (let [path (str root "/writer.edn") data (edn/read-string (slurp path))]
    (spit path (pr-str (assoc data :payload-cache-arm arm
                            :private-prepared-wal-in-both-arms true
                            :stock-runtime "0.8.23")))))
