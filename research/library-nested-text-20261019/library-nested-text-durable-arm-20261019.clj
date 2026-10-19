;; Research selector: control uses prior public String operation in the exact
;; same candidate. Byte arm leaves the production method unchanged. No encoder,
;; projection, native, WAL or persistence operations are replaced.
(require '[jolt.codec.experimental :as codec] '[clojure.edn :as edn])
(let [[root arm] *command-line-args* original codec/prepare-nested-text]
  (assert (contains? #{"string" "bytes"} arm))
  (with-redefs [codec/prepare-nested-text (if (= arm "string") codec/write-nested original)]
    (with-bindings {#'*command-line-args* [root]}
      (load-file "/home/chuck/ai-src/evidence/stock-library-codec-durable-writer-20261019.clj")))
  (let [path (str root "/writer.edn") writer (edn/read-string (slurp path))]
    (spit path (pr-str (assoc writer :nested-text-arm arm
                              :research-selector :nested-method-only
                              :candidate-production-method (= arm "bytes"))))))
