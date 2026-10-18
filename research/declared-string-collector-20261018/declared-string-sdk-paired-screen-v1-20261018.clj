;; Diagnostic: alternate collectors inside the same serial exporter/connection.
;; Source rows, SQL/WAL/ACK, SDK and writer lifetime remain the existing driver.
(require '[jolt.scheme :as scheme] '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as p]
         '[otel.exporter.chdb.native-projection :as projection-native]
         '[otel.exporter.chdb-benchmark :as bench])
(let [key-var #'p/key-string contains-var #'clojure.core/contains?
      old (scheme/eval-string @#'projection-native/declared)
      baseline (fn [attrs wanted] (old attrs wanted key-var contains-var))
      candidate (projection-native/load-declared-collector! key-var contains-var @#'p/original-key-string)
      choices {:baseline (delay baseline) :candidate (delay candidate)}
      actual @#'e/insert-direct-typed-span-batch!
      sample @#'bench/counter-sample delta @#'bench/counter-delta
      calls (atom 0) observations (atom [])]
  (with-redefs [e/insert-direct-typed-span-batch!
                (fn [connection state spans]
                  (let [call (swap! calls inc) measured? (> call 3)
                        ordinal (- call 4)
                        arm (if measured? (nth [:baseline :candidate :candidate :baseline] (mod ordinal 4))
                                (if (= call 2) :candidate :baseline))
                        before (when measured? (sample)) start (System/nanoTime)
                        result (with-redefs [p/small-declared-collector (get choices arm)]
                                 (actual connection state spans))
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
        (pr-str {:scope :same-sdk-connection-abba-insert-body-not-throughput
                 :tail-qualified false :warmups-retained-in-store 3
                 :observations @observations})))
