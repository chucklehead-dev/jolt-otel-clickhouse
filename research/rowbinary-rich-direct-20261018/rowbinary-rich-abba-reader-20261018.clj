(require '[jdbc.chdb-typed-storage-support :as storage])
(load-file "/home/chuck/ai-src/evidence/rowbinary-rich-abba-budget-20261018.clj")
(let [options storage/options]
  (with-redefs [storage/options (fn [root phase]
                                 (assoc (options root phase) :operations (rb-rich-budget-operations)))]
    ;; Normal older-stack physical-row oracle, not the experimental encoder.
    (load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-durable-reader-20261018.clj")))
