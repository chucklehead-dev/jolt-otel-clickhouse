;; Real compiled helper + the declared exporter dependency. No helper or
;; writer-factory source overlay; only the deterministic ended-span fixture.
(require '[jolt.scheme :as scheme])
(assert (scheme/eval-string "(and (procedure? stock-sorted-map?) (procedure? sorted-map-fold-seq-order))"))
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [original fixture/spans]
  (with-redefs [fixture/spans (fn [region] (nested-sorted-sdk-shape (original region)))]
    (load-file "/home/chuck/ai-src/evidence/sdk-trace-affinity-screen-20261018.clj")))
