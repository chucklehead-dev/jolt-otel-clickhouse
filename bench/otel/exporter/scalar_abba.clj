(ns otel.exporter.scalar-abba
  (:require [clojure.data.json :as json] [clojure.java.io :as io]
            [jdbc.core :as jdbc] [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.local-posix :as local]
            [jdbc.chdb.native :as native] [jolt.host :as host]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.exporter.scalar-abba-support :as f]
            [otel.sdk.export :as export]))

(defn check! [ok label]
  (when-not ok (throw (ex-info label {}))))

(defn qualified-chez? [version]
  ;; jolt.host/scheme-version returns Chez's full identification string.
  ;; Keep exact matching: do not admit suffixes or nearby releases.
  (= "Chez Scheme Version 10.4.1" version))

(defn validate-sample! [index before after publications]
  (check! (= index (:wal-count before)) "starting WAL history")
  (check! (= (inc index) (:wal-count after)) "one added WAL reference")
  (check! (and (= 1 (count publications))
               (contains? f/confirmed-statuses (first publications)))
          "one confirmed publication"))

(defn measured-call [operation clock counters]
  (let [before (counters) start (clock)
        result (operation) end (clock) after (counters)]
    (check! result "public export")
    {:nanos (- end start) :counters (f/counter-delta before after)}))

(defn run-writer! [root]
  (native/ensure-loaded!)
  (check! (= "26.7.3" (native/chdb-version)) "native release")
  (check! (qualified-chez? (host/scheme-version)) "Chez pin")
  (require 'jdbc.chdb.durable.wal)
  (check! ((ns-resolve 'jdbc.chdb.durable.wal 'native-line-enabled?)) "native WAL")
  (let [fixture-text (slurp (System/getenv "BENCH_FROZEN_FIXTURE"))
        _ (check! (= (f/sha256 fixture-text) (System/getenv "BENCH_FROZEN_FIXTURE_SHA256")) "fixture hash")
        source (str (io/resource "otel/any_value.clj"))
        _ (check! (= (f/sha256 (slurp (io/resource "otel/any_value.clj")))
                     (System/getenv "BENCH_OTEL_SOURCE_SHA256")) "loaded OTel hash")
        exporter-source (str (io/resource "otel/exporter/chdb.clj"))
        _ (check! (= (f/sha256 (slurp (io/resource "otel/exporter/chdb.clj")))
                     (System/getenv "BENCH_EXPORTER_SOURCE_SHA256")) "loaded exporter hash")
        spans (f/fixture)
        arm (System/getenv "BENCH_ARM")
        _ (check! (contains? #{"A" "B"} arm) "known arm")
        rows (mapv #(#'exporter/span-row % nil) spans)
        payload (#'exporter/json-each-row-payload rows)
        _ (check! (= payload (apply str (map #(str (json/write-str %) "\n") rows))) "JSON parity")
        _ (check! (= "7848af32f3ae2abdd1bffb3dfaeede978bd3fa74892df367fbfa0b252f556820" (f/sha256 payload)) "payload hash")
        ns-backend (local/local-backend (str root "/objects"))
        store (backend/object-backend ns-backend "telemetry")
        _ (check! (nil? (backend/get-bytes store "head.json")) "fresh store")
        options {:namespace-backend ns-backend :object-id "telemetry"
                 :scratch-parent (str root "/scratch-writer")
                 :owner "scalar-abba" :instance "writer-1" :database "otel"
                 :lease-ttl-ms 900000 :heartbeat-interval-ms 300000}]
    (with-open [connection (jdbc/connection (durable/writer-dbspec options))]
      (schema/ensure-schema! connection)
      (check! (contains? f/confirmed-statuses (:status (durable/checkpoint! connection))) "schema checkpoint")
      (let [writer (exporter/exporter {:connection connection :durable? true :create-schema? false :signals #{:spans}})
            op #(export/export-spans! writer spans)
            publish @#'exporter/persistence-barrier!
            pubs (atom [])
            observed (atom [])]
        (try
          ;; Public SQL preflight uses a non-mutating admission spy and a
          ;; confirmed test barrier. Real publications begin with warmup zero.
          (with-redefs [jdbc/execute! (fn [_ sql] (swap! observed conj sql) nil)
                        exporter/persistence-barrier! (fn [& _] {:status :committed})]
            (check! (op) "public SQL preflight"))
          (check! (= [(str "insert into otel_traces FORMAT JSONEachRow\n" payload)] @observed) "exact public SQL")
          (when (= "B" arm)
            ;; Compile/cache before replacing span-row. Any fallback would fail:
            ;; witness the actual production public call, outside timing.
            (let [calls (atom 0)]
              (with-redefs [exporter/span-row (fn [& _] (throw (ex-info "candidate fell back" {})))
                            jdbc/execute! (fn [_ sql] (check! (= (first @observed) sql) "activation SQL") (swap! calls inc))
                            exporter/persistence-barrier! (fn [& _] {:status :committed})]
                (check! (op) "production encoder activation")
                (check! (= 1 @calls) "activation admission"))))
          (with-redefs [exporter/persistence-barrier!
                        (fn [& args]
                          (let [r (apply publish args)]
                            (swap! pubs conj (:status r)) r))]
            (let [all
                  (mapv (fn [index]
                          (when (= index f/warmups) (System/gc))
                          (let [before (f/head-evidence store)
                                _ (reset! pubs [])
                                sample (measured-call op #(System/nanoTime) f/counters)
                                after (f/head-evidence store)
                                _ (validate-sample! index before after @pubs)
                                receipt (assoc sample :index index :warmup? (< index f/warmups)
                                               :wal-before before :wal-after after :publications @pubs)]
                            (spit (str root "/samples.edn") (str (pr-str receipt) "\n") :append true)
                            receipt))
                        (range (+ f/warmups f/samples)))
                  measured (vec (drop f/warmups all))
                  latencies (vec (sort (map :nanos measured)))
                  barrier (durable/flush! connection)
                  n (:n (jdbc/fetch-one connection "SELECT count() AS n FROM otel_traces"))
                  expected-n (* (+ f/warmups f/samples) f/batch-size)
                  digest (f/ordered-digest (mapcat identity (repeat (+ f/warmups f/samples) (mapv f/input-row spans))))
                  actual-digest (f/readback-digest connection)
                  expanded-digest (f/expanded-digest connection)]
              (check! (= :empty (:status barrier)) "empty final flush")
              (check! (= expected-n n) "writer count")
              (check! (= digest actual-digest) "writer digest")
              (spit (str root "/writer-report.edn")
                    (str (pr-str {:arm (System/getenv "BENCH_ARM") :otel-source source
                                  :production-encoder-witnessed? (= "B" arm)
                                  :exporter-source exporter-source
                                  :runtime {:jolt (host/jolt-version) :chez (host/scheme-version)}
                                  :payload-sha256 (f/sha256 payload)
                                  :measurements measured
                                  :p50-nanos (f/percentile latencies 0.50)
                                  :p99-nanos (f/percentile latencies 0.99)
                                  :aggregate-rows-per-second (/ (* 1.0e9 f/samples f/batch-size) (reduce + latencies))
                                  :counters (reduce #(merge-with + %1 %2) {} (map :counters measured))
                                  :writer {:rows n :expected-rows expected-n :expected-digest digest
                                           :expanded-digest expanded-digest
                                           :actual-digest actual-digest :empty-barrier? true
                                           :head (f/head-evidence store)}}) "\n"))
              (println :writer-green n)))
          (finally (export/shutdown-exporter! writer)))))))

(defn -main [phase root]
  (case phase "writer" (run-writer! root) "reader" (f/run-reader! root)
        (throw (ex-info "Expected writer or reader" {}))))
