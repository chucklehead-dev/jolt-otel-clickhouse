(require '[clojure.edn :as edn])

(let [[root arm] *command-line-args*
      read-source slurp
      source (read-source "research/fused-typed-field-slots-20261021/validate-native.bb")
      writer-path (str root "/writer.edn")
      reader-path (str root "/reader.edn")
      w (edn/read-string (read-source writer-path))
      r (edn/read-string (read-source reader-path))
      run (fn [writer reader]
            (with-out-str
              (binding [*command-line-args* [root arm]]
                (with-redefs [slurp (fn [path & args]
                                     (cond (= path writer-path) (pr-str writer)
                                           (= path reader-path) (pr-str reader)
                                           :else (apply read-source path args)))]
                  (load-string source)))))
      controls [[:empty-samples (assoc w :samples []) r]
                [:false-ack (assoc-in w [:samples 0 :ok] false) r]
                [:wrong-round (assoc-in w [:samples 0 :round] 1) r]
                [:wrong-selected (update w :factories-selected inc) r]
                [:wrong-quantile (update-in w [:p50-p90-p95-p99-ms 0] inc) r]
                [:wrong-rate (update w :confirmed-rows-per-second inc) r]
                [:wrong-allocation (update w :estimated-bytes-per-row inc) r]
                [:missing-physical-proof w (assoc r :full-physical-rows-equal false)]
                [:missing-rows w (update r :rows dec)]]]
  (assert (:verified (edn/read-string (run w r))))
  (doseq [[label writer reader] controls]
    (assert (try (run writer reader) false
                 (catch clojure.lang.ExceptionInfo ex
                   ;; A validator rejection, not an unrelated evaluation error.
                   (= "Invalid field-slot native receipt" (ex-message ex))))
            (str "Mutation escaped validator: " label)))
  (prn {:positive-control true :rejected-controls (mapv first controls)
        :persisted-receipts-modified false}))
