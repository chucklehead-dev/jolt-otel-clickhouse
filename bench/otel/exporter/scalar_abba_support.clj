(ns otel.exporter.scalar-abba-support
  "Local-only compiler/source factorial for the public Durable exporter path."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.local-posix :as local]
            [jdbc.chdb.native :as native]
            [jolt.host :as host]
            [otel.context :as context]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]
            [otel.sdk.export :as export]))

(def batch-size 512)
(def warmups 5)
(def samples 100)
(def regions [:public :preencoded])
(def confirmed-statuses #{:committed :reconciled})

(defn- require! [value label]
  (when-not value (throw (ex-info (str "Factorial control failed: " label) {}))))

(defn sha256 [text]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (str text) "UTF-8"))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn percentile [ordered fraction]
  (nth ordered (dec (long (Math/ceil (* fraction (count ordered)))))))

(defn ordered-regions [order]
  (case order
    "public-first" [:public :preencoded]
    "preencoded-first" [:preencoded :public]
    (throw (ex-info "Unknown region order" {}))))

(defn counters []
  {:cpu-nanos (host/cpu-nanos)
   :real-nanos (host/real-nanos)
   :gc-count (host/gc-count)
   :gc-real-nanos (host/gc-real-nanos)
   :gc-bytes (host/gc-bytes)
   :live-heap-bytes (host/bytes-allocated)})

(defn counter-delta [before after]
  {:cpu-nanos (- (:cpu-nanos after) (:cpu-nanos before))
   :real-nanos (- (:real-nanos after) (:real-nanos before))
   :gc-count (- (:gc-count after) (:gc-count before))
   :gc-real-nanos (- (:gc-real-nanos after) (:gc-real-nanos before))
   :gc-bytes (- (:gc-bytes after) (:gc-bytes before))
   :scheme-heap-bytes-allocated
   (- (+ (:live-heap-bytes after) (:gc-bytes after))
      (+ (:live-heap-bytes before) (:gc-bytes before)))})

(defn measure! [operation]
  (dotimes [_ warmups] (operation))
  (System/gc)
  (let [before (counters)
        latencies
        (mapv (fn [_]
                (let [start (System/nanoTime)]
                  (operation)
                  (- (System/nanoTime) start)))
              (range samples))
        after (counters)
        ordered (vec (sort latencies))
        total (reduce + 0 latencies)]
    {:samples samples
     :batch-size batch-size
     :latency-nanos latencies
     :p50-nanos (percentile ordered 0.50)
     :p99-nanos (percentile ordered 0.99)
     :aggregate-rows-per-second (/ (* (double samples) batch-size 1000000000.0)
                                   total)
     :counters (counter-delta before after)}))

(defn fixture []
  (let [value (edn/read-string (slurp (System/getenv "BENCH_FROZEN_FIXTURE")))]
    (require! (and (vector? value) (= batch-size (count value))) "frozen fixture")
    value))

(defn input-row [span]
  (let [ctx (:span-context span)]
    [(:trace-id ctx) (:span-id ctx) (:name span)
     (max 0 (- (:end-time-unix-nano span) (:start-time-unix-nano span)))]))

(defn ordered-digest [rows] (sha256 (pr-str (sort rows))))

(defn head-evidence [store]
  (let [head (:head (control/read-head-read-only! store))]
    {:base-present? (boolean (get-in head ["manifest" "base"]))
     :wal-count (count (get-in head ["manifest" "wal"]))}))

(defn readback-digest [connection]
  (ordered-digest
   (map (fn [row] [(:traceid row) (:spanid row) (:spanname row) (:duration row)])
        (jdbc/fetch connection
                    "SELECT TraceId, SpanId, SpanName, Duration FROM otel_traces ORDER BY TraceId"))))

(def expanded-selection
  "SELECT TraceId, SpanId, EventsJSON, LinksJSON, arrayMap(x -> toString(toUnixTimestamp64Nano(x)), `Events.Timestamp`) AS event_ticks, `Events.Name`, `Events.Attributes`, `Links.TraceId`, `Links.SpanId`, `Links.TraceState`, `Links.Attributes` FROM otel_traces ORDER BY TraceId, SpanId")

(defn expanded-rows-digest [rows]
  (sha256 (pr-str (sort-by pr-str
                   (walk/postwalk #(if (map? %) (into (sorted-map) %) %) rows)))))

(defn expanded-digest [connection]
  (expanded-rows-digest (jdbc/fetch connection expanded-selection)))

(defn run-reader! [root]
  (let [writer-text (slurp (str root "/writer-report.edn"))
        writer (edn/read-string writer-text)
        namespace (local/local-backend (str root "/objects"))
        options {:namespace-backend namespace :object-id "telemetry"
                 :scratch-parent (str root "/scratch-reader")}]
    (with-open [connection (jdbc/connection (durable/snapshot-dbspec options))]
      (let [actual (:n (jdbc/fetch-one connection "SELECT count() AS n FROM otel_traces"))
            digest (readback-digest connection)
            expected (get-in writer [:writer :expected-digest])
            expanded (expanded-digest connection)
            report {:schema-version 1 :count actual
                    :writer-report-sha256 (sha256 writer-text)
                    :expected-digest expected :actual-digest digest
                    :equal? (= expected digest)
                    :expanded-digest expanded
                    :expanded-equal? (= (get-in writer [:writer :expanded-digest]) expanded)}]
        (require! (= (get-in writer [:writer :expected-rows]) actual) "reader count")
        (require! (:equal? report) "reader digest")
        (require! (:expanded-equal? report) "reader expanded event/link digest")
        (spit (str root "/reader-report.edn") (str (pr-str report) "\n"))
        (spit (str root "/reader.expanded-digest") (str expanded "\n"))
        (println :factorial-reader-green :rows actual :digest-equal true)))))
