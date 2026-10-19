(require '[clojure.edn :as edn])
(let [[prefix arm] *command-line-args*
      writer (edn/read-string (slurp (str prefix ".writer.edn")))]
  (assert (= arm (:experimental-codec-arm writer)))
  (assert (:candidate-product-source writer))
  (let [result (binding [*command-line-args* [prefix "1"]]
                 (with-out-str (load-file "/home/chuck/ai-src/evidence/validate-rich-sdk-scaling-20261019.clj")))
        validated (edn/read-string result)]
    (assert (:verified validated))
    (prn (assoc validated :candidate-product-source true :source-overlay true
                         :experimental-codec-arm arm))))
