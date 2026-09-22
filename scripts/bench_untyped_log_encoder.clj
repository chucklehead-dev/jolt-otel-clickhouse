(ns bench-untyped-log-encoder
  "Small, deterministic encoder-only probe; it never opens chDB or a WAL."
  (:require [clojure.data.json :as json]
            [otel.exporter.chdb :as exporter]))

(defn- logs [count]
  (mapv (fn [n]
          {:timestamp-unix-nano (+ 1700000000000000000 n)
           :trace-id "0123456789abcdef0123456789abcdef" :span-id (str "span-" n)
           :trace-flags 1 :severity-text "INFO" :severity-number 9
           :body (str "steady-log-" n) :event-name "benchmark"
           :attributes {"iteration" n "enabled" true}
           :resource {:attributes {"service.name" "encoder-probe" "region" "us-east-2"}}
           :scope {:name "probe" :version "1" :attributes {"library" "bench"}}})
        (range count)))

(defn- nanos [f]
  (let [start (System/nanoTime) value (f)]
    {:nanos (- (System/nanoTime) start) :value value}))

(defn- timing-summary [samples]
  (let [ordered (vec (sort samples))
        at (fn [p] (nth ordered (min (dec (count ordered))
                                     (long (Math/floor (* p (dec (count ordered))))))))]
    {:min-nanos (first ordered)
     :p50-nanos (at 0.50)
     :p99-nanos (at 0.99)
     :mean-nanos (quot (reduce + ordered) (count ordered))}))

(defn- generic-payload [records]
  (#'exporter/json-each-row-payload (map #(#'exporter/log-row % nil) records)))

(defn -main [& [row-count sample-count]]
  (let [row-count (Long/parseLong (or row-count "512"))
        sample-count (Long/parseLong (or sample-count "20"))
        records (logs row-count)
        encoder (#'exporter/compile-untyped-log-encoder)]
    (when-not (ifn? encoder)
      (throw (ex-info "Jolt schema-bound log encoder unavailable" {})))
    ;; Exact wire equivalence is part of the probe precondition, not a timing
    ;; result.  Warm-up output is discarded and never included in timings.
    (let [generic (generic-payload records)
          direct (#'exporter/untyped-log-payload encoder records)]
      (when-not (= (vec (.getBytes generic "UTF-8")) (vec (.getBytes direct "UTF-8")))
        (throw (ex-info "encoder byte parity failed" {})))
      (dotimes [_ 3] (generic-payload records) (#'exporter/untyped-log-payload encoder records))
      (let [generic-samples (mapv :nanos (repeatedly sample-count #(nanos (fn [] (generic-payload records)))))
            direct-samples (mapv :nanos (repeatedly sample-count #(nanos (fn [] (#'exporter/untyped-log-payload encoder records)))))]
        (println (pr-str {:rows row-count :samples sample-count
                          :bytes (alength (.getBytes direct "UTF-8")) :exact? true
                          :generic (timing-summary generic-samples)
                          :schema-bound (timing-summary direct-samples)}))))))
