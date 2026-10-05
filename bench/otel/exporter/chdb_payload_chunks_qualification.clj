(ns otel.exporter.chdb-payload-chunks-qualification
  "Explicit Jolt source-mode preparation check, not a throughput benchmark."
  (:require [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb-benchmark :as benchmark]
            [otel.exporter.chdb.payload-chunks :as chunks]
            [clojure.data.json :as json]
            [clojure.data.json.jolt-native :as native]))

(defn -main [& [output]]
  (let [resource (#'benchmark/resource
                  "oscope-backend-bench-00000000-0000-4000-8000-000000000000")
        rows (vec (for [{:keys [scope metrics]} (#'benchmark/metrics 0 10000)
                        metric metrics :when (= :histogram (:type metric))
                        point (:data-points metric)]
                    (#'exporter/metric-row resource scope metric point nil)))]
    (binding [json/*experimental-native-writer* (native/load-writer!)]
      (let [rejected? (try
                        (binding [exporter/*json-backend* :native-guarded]
                          (#'exporter/json-each-row-payload rows))
                        false
                        (catch clojure.lang.ExceptionInfo error
                          (and (= "chDB telemetry export batch exceeds 8 MiB"
                                  (.getMessage error))
                               (= {:limit 8388608} (ex-data error)))))
            _ (assert rejected?)
            calls (atom 0)
            result (chunks/prepare-payloads!
                    rows {:encode-row (fn [row] (swap! calls inc) (json/write-str row))
                          :max-chunk-bytes 8388608 :max-logical-bytes 33554432
                          :max-rows 10000})
            payloads (:chunks result)
            expected (apply str (map #(str (json/write-str %) "\n") rows))]
        (assert (= 10000 @calls (:row-count result)))
        (assert (> (count payloads) 1))
        (assert (= expected (apply str (map :payload payloads))))
        (assert (every? #(<= (:byte-count %) 8388608) payloads))
        (assert (= (alength (.getBytes expected "UTF-8")) (:byte-count result)))
        (let [report {:scope :preparation-only :rows @calls
                      :existing-whole-payload-rejects? rejected?
                      :exact-json-byte-order-parity true
                      :total-bytes (:byte-count result)
                      :chunks (mapv #(select-keys % [:row-count :byte-count]) payloads)
                      :persistence-qualified? false}]
          (when output (spit output (pr-str report)))
          (prn report))))))
