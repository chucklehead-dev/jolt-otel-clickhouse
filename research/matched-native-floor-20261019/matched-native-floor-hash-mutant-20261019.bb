;; No evidence/data files are mutated. Prove cross-process hash matching rejects
;; a different captured SQL digest before publishing an aggregate summary.
(require '[clojure.edn :as edn] '[clojure.string :as str] '[clojure.java.io :as io])
(let [[output] *command-line-args* original slurp changed (atom 0) caught (atom false)
      root "/home/chuck/ai-src/evidence/"
      target (str root "matched-native-floor-v2-20261019/0-native.writer.edn")]
  (assert (not (.exists (io/file output))))
  (with-redefs [slurp (fn [path]
                       (let [text (original path)]
                         (if (= path target)
                           (do (swap! changed inc)
                               (pr-str (assoc (edn/read-string text) :statement-sha256 (apply str (repeat 64 "0")))))
                           text)))]
    (binding [*command-line-args* [(str root "matched-native-floor-abba-20261019")
                                 (str root "matched-native-floor-v2-20261019") output]]
      (try (load-file (str root "summarize-matched-native-floor-20261019.bb"))
           (catch java.lang.AssertionError error
             (assert (str/includes? (.getMessage error) "map key arms"))
             (reset! caught true)))))
  (assert (= 1 @changed))
  (assert @caught)
  (assert (not (.exists (io/file output))))
  (prn {:hash-mutant-rejected true :summary-not-written true :persisted-data-unchanged true}))
