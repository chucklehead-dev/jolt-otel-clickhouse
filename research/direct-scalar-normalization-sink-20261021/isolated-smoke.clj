(require '[jolt.scheme :as scheme])
;; Resolve only the explicit independent source path from a neutral cwd FIRST.
;; After resolution, change this process's directory solely for existing research
;; load-file paths. This does not change the resolved dependency graph.
(def root (first *command-line-args*))
(def sdk-path (second *command-line-args*))
(scheme/eval-string (str "(current-directory " (pr-str root) ")"))
(load-file "research/direct-scalar-normalization-sink-20261021/inline.clj")
(def policy (Object.))
(def original-policy policy)
(let [sdk (slurp sdk-path) copy (sink-context :copy sdk) inline (inline-context sdk)
      anchors [[#'policy original-policy]]
      results (for [n [9 17 128]]
                (let [rows [(into {} (map (fn [i] [(str "key-" i)
                                                   (case (mod i 3) 0 "λ\"\\😀" 1 false i)]))
                                  (range n))]
                      a (sink-encode copy anchors 100 rows 100000 128)
                      b (sink-encode inline anchors 100 rows 100000 128)]
                  (assert (bytes-equal? a b)) n))]
  (assert (= [9 17 128] (vec results)))
  (doseq [ns '[clojure.data.json otel.any-value otel.any-value-native
              otel.sdk.export otel.exporter.chdb jdbc.chdb.durable]]
    (assert (nil? (find-ns ns))))
  (prn {:isolated-stock-smoke true :sizes [9 17 128]
        :sdk-source-read-as-data true :sdk-json-exporter-driver-namespaces-loaded false
        :performance-qualified false :durable-qualified false}))
