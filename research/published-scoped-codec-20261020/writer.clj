;; Functional published-dependency gate, not a paired performance comparison.
(require '[clojure.java.io :as io] '[clojure.edn :as edn]
         '[clojure.string :as str] '[jolt.codec.experimental :as codec])
(let [[root] *command-line-args*
      suffix "https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/0af1008185cf84577298d3a0a0b2db87b97bddd5/incubator/codec/src/"
      resources (into {} (for [name ["jolt/codec/experimental.clj" "jolt/codec/json.ss"
                                     "jolt/traversal/experimental.clj" "jolt/traversal/kernel.ss"]]
                            (let [path (str (io/resource name))]
                              (assert (str/ends-with? path (str suffix name)))
                              [name path])))]
  (assert (ifn? codec/with-nested-text-writer))
  (binding [*command-line-args* [root "on"]]
    (load-file "research/nested-fragments-20261019/durable-arm.clj"))
  (let [file (str root "/writer.edn") value (edn/read-string (slurp file))]
    (spit file (pr-str (assoc value :published-codec-resources resources
                                  :checkout-local-codec-source false
                                  :comparison-qualified false)))))
