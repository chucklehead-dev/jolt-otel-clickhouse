(ns bench-untyped-log-encoder
  "Small, deterministic encoder-only probe; it never opens chDB or a WAL."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [otel.exporter.chdb :as exporter]))

(defn- logs [count]
  ;; Deliberately share every attribute map while varying ordinary scalar
  ;; fields. This is a high-reuse, but still byte-realistic, log batch: it
  ;; isolates the batch-local attribute-wire cache rather than accidentally
  ;; measuring an unrelated per-row formatter.
  (let [resource {"service.name" "encoder-probe" "region" "us-east-2"}
        scope {"library" "bench"}
        attributes {"component" "encoder-probe" "enabled" true}]
    (mapv (fn [n]
            {:timestamp-unix-nano (+ 1700000000000000000 n)
             :trace-id "0123456789abcdef0123456789abcdef" :span-id (str "span-" n)
             :trace-flags 1 :severity-text "INFO" :severity-number 9
             :body (str "steady-log-" n) :event-name "benchmark"
             :attributes attributes
             :resource {:attributes resource}
             :scope {:name "probe" :version "1" :attributes scope}})
          (range count))))

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

(defn- sha256 [text]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (java.math.BigInteger. 1 (.digest digest (.getBytes text "UTF-8"))))))

(defn- sample [count thunk]
  (mapv :nanos (repeatedly count #(nanos thunk))))

(defn- write-immutable-receipt! [path receipt]
  ;; A probe never silently replaces evidence. The caller must choose a fresh
  ;; file under an existing, explicitly owned evidence directory.
  (let [file (io/file path)]
    (when-not (.getParentFile file)
      (throw (ex-info "receipt needs an explicit parent directory" {:receipt path})))
    (when-not (.exists (.getParentFile file))
      (throw (ex-info "receipt parent does not exist" {:receipt path})))
    (when-not (.createNewFile file)
      (throw (ex-info "refusing to replace encoder probe receipt" {:receipt path})))
    (spit file (str (pr-str receipt) "\n"))))

(defn -main [& [row-count sample-count receipt-path]]
  (let [row-count (Long/parseLong (or row-count "512"))
        sample-count (Long/parseLong (or sample-count "20"))
        records (logs row-count)
        encoder (#'exporter/compile-untyped-log-encoder)]
    (when-not (ifn? encoder)
      (throw (ex-info "Jolt schema-bound log encoder unavailable" {})))
    ;; Exact wire equivalence is part of the probe precondition, not a timing
    ;; result.  Warm-up output is discarded and never included in timings.
    (let [without-cache #(with-redefs [exporter/untyped-log-attribute-cache (constantly nil)]
                            (#'exporter/untyped-log-payload encoder records))
          with-cache #(#'exporter/untyped-log-payload encoder records)
          batch-encoder (#'exporter/compile-untyped-log-batch-encoder)
          direct-batch #(binding [exporter/*untyped-log-attribute-wire-cache*
                                  (#'exporter/untyped-log-attribute-cache)]
                          (batch-encoder records))
          generic (generic-payload records)
          direct-without-cache (without-cache)
          direct-with-cache (with-cache)]
      (when-not (ifn? batch-encoder)
        (throw (ex-info "Jolt direct batch log encoder unavailable" {})))
      (let [direct-batch-wire (direct-batch)]
        (when-not (and (= (vec (.getBytes generic "UTF-8"))
                        (vec (.getBytes direct-without-cache "UTF-8")))
                     (= (vec (.getBytes generic "UTF-8"))
                        (vec (.getBytes direct-with-cache "UTF-8")))
                     (= (vec (.getBytes generic "UTF-8"))
                        (vec (.getBytes direct-batch-wire "UTF-8"))))
          (throw (ex-info "encoder byte parity failed" {})))
        (dotimes [_ 3] (generic-payload records) (without-cache) (with-cache) (direct-batch))
      ;; ABBA avoids attributing one monotonic warm-up/drift direction to the
      ;; direct batch renderer. A retains the reviewed per-row exact encoder;
      ;; B appends admitted rows to one request-local StringBuilder and counts
      ;; exact UTF-8 bytes before observing the next record. This is encoder
      ;; evidence only, never a Durable throughput claim.
        (let [arms [[:a-row-cache with-cache] [:b-direct-batch direct-batch]
                  [:b-direct-batch direct-batch] [:a-row-cache with-cache]]
            measured (mapv (fn [[label thunk]]
                             {:arm label :summary (timing-summary (sample sample-count thunk))})
                           arms)
            receipt {:kind :jolt-direct-batch-log-renderer-encoder-abba
                     :rows row-count :samples sample-count
                     :jolt-version (System/getProperty "jolt.version")
                     :exporter-source-sha256
                     (sha256 (slurp "src/otel/exporter/chdb.clj"))
                     :probe-source-sha256
                     (sha256 (slurp "scripts/bench_untyped_log_encoder.clj"))
                     :fixture-sha256 (sha256 (pr-str records))
                     :payload-sha256 (sha256 direct-batch-wire)
                     :bytes (alength (.getBytes direct-batch-wire "UTF-8"))
                     :exact? true
                     :generic (timing-summary (sample sample-count #(generic-payload records)))
                     :abba measured
                     :scope :encoder-only-no-chdb-or-wal}]
          (when receipt-path (write-immutable-receipt! receipt-path receipt))
          (println (pr-str receipt)))))))
