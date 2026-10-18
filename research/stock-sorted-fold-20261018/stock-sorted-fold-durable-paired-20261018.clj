(require '[otel.exporter.chdb :as e]
         '[clojure.data.json.jolt-native :as json-native]
         '[jdbc.chdb.durable :as durable]
         '[otel.exporter.chdb-benchmark :as bench])
(load-file "/home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj")
(def ^:dynamic *fold-probe-context* nil)
(let [original-fixture fixture/spans
      original-factory (force @#'json-native/byte-factory)
      mode (or (System/getenv "SDK_SORTED_FOLD_MODE") "bounded")
      _ (assert (contains? #{"bounded" "runtime"} mode))
      _ (load-file (if (= mode "runtime")
                     "/home/chuck/ai-src/evidence/install-runtime-stock-sorted-fold-20261018.clj"
                     "/home/chuck/ai-src/evidence/install-stock-sorted-fold-probe-20261018.clj"))
      candidate (var-get (resolve 'stock-sorted-fold-diagnostic-factory))
      choices {:baseline (delay original-factory) :candidate (delay candidate)}
      insert @#'e/insert-direct-typed-span-batch!
      sample @#'bench/counter-sample delta @#'bench/counter-delta
      calls (atom 0) observations (atom []) phases (atom [])
      observe (fn [phase f]
                (fn [& args]
                  (if-not *fold-probe-context* (apply f args)
                    (let [before (sample) start (System/nanoTime) result (apply f args)
                          end (System/nanoTime) after (sample)]
                      (swap! phases conj
                             (assoc *fold-probe-context* :phase phase
                                    :start-nanos start :end-nanos end :nanos (- end start)
                                    :thread-name (.getName (Thread/currentThread))
                                    :counters (delta before after)))
                      result))))]
  (with-redefs [fixture/spans (fn [region] (nested-sorted-sdk-shape (original-fixture region)))
                json-native/write-prefixed-batch-bytes!
                (observe :projection-and-encoding json-native/write-prefixed-batch-bytes!)
                durable/execute-owned-and-flush!
                (observe :native-and-confirm durable/execute-owned-and-flush!)
                e/insert-direct-typed-span-batch!
                (fn [connection state spans]
                  (let [call (swap! calls inc) measured? (> call 3) ordinal (- call 4)
                        arm (if measured? (nth [:baseline :candidate :candidate :baseline] (mod ordinal 4))
                                (if (= call 2) :candidate :baseline))
                        before (when measured? (sample)) start (System/nanoTime)
                        result (binding [*fold-probe-context* (when measured? {:ordinal ordinal :arm arm})]
                                 (with-redefs [json-native/byte-factory (get choices arm)]
                                   (insert connection state spans)))
                        end (System/nanoTime) after (when measured? (sample))]
                    (when measured?
                      (assert (< (count @observations) 32))
                      (swap! observations conj
                             {:ordinal ordinal :arm arm :rows (count spans)
                              :start-nanos start :end-nanos end :nanos (- end start)
                              :counters (delta before after)}))
                    result))]
    (load-file "/home/chuck/ai-src/evidence/sdk-trace-affinity-screen-20261018.clj"))
  (assert (= 35 @calls))
  (assert (= (vec (range 32)) (mapv :ordinal @observations)))
  (assert (= {:baseline 16 :candidate 16} (frequencies (map :arm @observations))))
  (spit (str (first *command-line-args*) "/paired.edn")
        (pr-str {:scope :stock-sorted-fold-runtime-prototype-abba-not-product
                 :fold-mode mode :runtime-source-overlay true :rebuilt-compiler false
                 :tail-qualified false :warmups-retained-in-store 3
                 :observations @observations :phases @phases})))
