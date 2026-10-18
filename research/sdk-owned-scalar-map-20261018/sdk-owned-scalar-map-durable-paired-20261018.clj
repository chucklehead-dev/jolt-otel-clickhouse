;; Diagnostic: alternate optional SDK scalar conversion inside the same serial exporter/connection.
;; Source rows, SQL/WAL/ACK, SDK and writer lifetime remain the existing driver.
(require '[jolt.scheme :as scheme] '[otel.exporter.chdb :as e]
         '[otel.exporter.chdb.attribute-projection :as p]
         '[otel.exporter.chdb.native-projection :as projection-native]
         '[clojure.data.json.jolt-native :as json-native]
         '[jdbc.chdb.durable :as durable]
         '[otel.exporter.chdb-benchmark :as bench])
(def ^:dynamic *scalar-map-probe-context* nil)
(let [sdk-var (ns-resolve 'otel.any-value 'try-scalar-string-map)
      _ (assert sdk-var)
      choices {:baseline (delay nil) :candidate (delay sdk-var)}
      actual @#'e/insert-direct-typed-span-batch!
      sample @#'bench/counter-sample delta @#'bench/counter-delta
      calls (atom 0) observations (atom []) phases (atom [])
      observe (fn [phase actual]
                (fn [& args]
                  (if-not *scalar-map-probe-context* (apply actual args)
                    (let [before (sample) start (System/nanoTime) result (apply actual args)
                          end (System/nanoTime) after (sample)]
                      (swap! phases conj
                             (assoc *scalar-map-probe-context* :phase phase
                                    :start-nanos start :end-nanos end :nanos (- end start)
                                    :thread-name (.getName (Thread/currentThread))
                                    :counters (delta before after)))
                      result))))]
  (with-redefs [json-native/write-prefixed-batch-bytes!
                (observe :projection-and-encoding json-native/write-prefixed-batch-bytes!)
                durable/execute-owned-and-flush!
                (observe :native-and-confirm durable/execute-owned-and-flush!)
                e/insert-direct-typed-span-batch!
                (fn [connection state spans]
                  (let [call (swap! calls inc) measured? (> call 3)
                        ordinal (- call 4)
                        arm (if measured? (nth [:baseline :candidate :candidate :baseline] (mod ordinal 4))
                                (if (= call 2) :candidate :baseline))
                        before (when measured? (sample)) start (System/nanoTime)
                        result (binding [*scalar-map-probe-context* (when measured? {:ordinal ordinal :arm arm})]
                                 (with-redefs [e/sdk-scalar-map-converter (get choices arm)]
                                   (actual connection state spans)))
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
                 :observations @observations :phases @phases})))
