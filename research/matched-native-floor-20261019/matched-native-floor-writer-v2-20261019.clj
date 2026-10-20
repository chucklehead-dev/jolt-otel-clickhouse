;; Correct the two review-found path asymmetries without changing the frozen
;; original dataset or measurements. Both paths now use the same observer and
;; real exporter confirmed helper (including instrumentation suppression).
(require '[clojure.string :as str] '[clojure.edn :as edn])
(defn replace-once [source needle replacement]
  (assert (= 2 (count (str/split source (re-pattern (java.util.regex.Pattern/quote needle)) -1))))
  (str/replace source needle replacement))
(defn observed-executor [statement calls execute]
  (fn [connection sql]
    (swap! calls inc)
    (when (nil? @statement) (reset! statement sql))
    (execute connection sql)))
(let [[root arm] *command-line-args*
      source (slurp "/home/chuck/ai-src/evidence/matched-native-floor-writer-20261019.clj")
      changes [["(fn [connection sql]\n                      (swap! calls inc)\n                      (when (nil? @statement) (reset! statement sql))\n                      (execute connection sql))"
                "(observed-executor statement calls execute)"]
               ["captured (atom nil) captures (atom 0) execute @#'e/execute-durable-sql!"
                "captured (atom nil) captures (atom 0) observed-calls (atom 0) execute @#'e/execute-durable-sql! observed (observed-executor captured observed-calls execute)"]
               ["(swap! captures inc) (reset! captured sql) (execute c sql)"
                "(swap! captures inc) (observed c sql)"]
               ["(durable/execute-owned-and-flush! connection statement)"
                "(observed connection statement)"]
               ["(ensure! (= 35 @streams) :all-physical-native-streams)"
                "(ensure! (= 35 @streams) :all-physical-native-streams) (ensure! (= 35 @observed-calls) :observed-confirmed-requests)"]
               [":capture-calls @captures :stream-calls @streams :batches batches"
                ":capture-calls @captures :observed-execute-calls @observed-calls :stream-calls @streams :batches batches"]]
      staged (reduce (fn [text [needle replacement]] (replace-once text needle replacement)) source changes)]
  (spit (str root "/matched-native-v2-staged.clj") staged)
  (load-string staged)
  (let [path (str root "/writer.edn") w (edn/read-string (slurp path))]
    (spit path
          (pr-str (assoc w :native-floor-harness-version 2
                           :observed-execute-calls (if (= arm "sdk") (:capture-calls w) (:observed-execute-calls w))
                           :instrumentation-suppression-included true :statement-observer-per-call true
                           :warmup-breakdown (if (= arm "sdk") {:exporter 3 :direct 0} {:exporter 1 :direct 2}))))))
