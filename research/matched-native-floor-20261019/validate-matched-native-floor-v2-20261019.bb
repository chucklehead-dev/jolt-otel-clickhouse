(require '[clojure.edn :as edn])
(let [[prefix] *command-line-args*
      w (edn/read-string (slurp (str prefix ".writer.edn")))
      old (with-out-str (load-file "/home/chuck/ai-src/evidence/validate-matched-native-floor-20261019.bb"))]
  (assert (:verified (edn/read-string old)))
  (assert (= [2 35 true true]
             (mapv w [:native-floor-harness-version :observed-execute-calls
                      :instrumentation-suppression-included :statement-observer-per-call])))
  (assert (= (if (= "sdk" (:native-floor-arm w)) {:exporter 3 :direct 0} {:exporter 1 :direct 2})
             (:warmup-breakdown w)))
  (prn (assoc (edn/read-string old) :harness-version 2 :production-helper-path-included true)))
