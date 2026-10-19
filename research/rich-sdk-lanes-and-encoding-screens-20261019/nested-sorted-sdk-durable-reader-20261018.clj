(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(let [original fixture/spans]
  (with-redefs [fixture/spans (fn [region] (nested-sorted-sdk-shape (original region)))]
    (load-file (or (second *command-line-args*)
                   "/home/chuck/ai-src/evidence/nested-sorted-sdk-physical-reader-20261018.clj"))))
