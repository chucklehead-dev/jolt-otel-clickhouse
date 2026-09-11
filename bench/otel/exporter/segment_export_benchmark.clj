(ns otel.exporter.segment-export-benchmark
  "Measures the cost of exporting ClickStack-compatible telemetry out of
  embedded chDB as columnar segments, against the cost of the Durable V1
  statement WAL that currently carries the same rows to object storage.

  This benchmark answers three questions that decide whether a `segments/`
  object class is affordable:

  1. What does the Durable WAL path actually cost? It is bounded by payload
     bytes, not rows, so the same engine sustains very different row rates for
     narrow and wide telemetry. The two-width run demonstrates that directly.
  2. What does a Parquet export of the same rows cost, in CPU and in bytes?
  3. How does that cost vary with segment size? Small segments lose twice:
     lower export throughput and worse compression.

  Rows are generated inside chDB with `INSERT ... SELECT FROM numbers(...)` so
  that row construction is not attributed to the measured work, and so the
  attribute cardinality is explicit and reproducible rather than drawn from a
  captured trace. The generated shape approximates one instrumented HTTP
  service: twelve spans per trace, eight services, twenty routes, eight
  resource attributes and ten span attributes.

  Numbers produced here are evidence, not CI thresholds. Compression in
  particular is optimistic relative to production telemetry, whose attribute
  values are less repetitive than a generator's; see
  `docs/benchmarks/segment-export.md` for the recorded baseline and its
  limitations."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [db.jdbc]
            [jdbc.chdb]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.core :as jdbc]
            [jolt.host :as host]))

(def traces-ddl
  "CREATE TABLE IF NOT EXISTS otel_traces (
     Timestamp DateTime64(9), TraceId String, SpanId String, ParentSpanId String,
     TraceState String, SpanName String, SpanKind String, ServiceName String,
     ResourceAttributes Map(String, String), ScopeName String, ScopeVersion String,
     SpanAttributes Map(String, String), Duration UInt64, StatusCode String,
     StatusMessage String, EventsJSON String, LinksJSON String
   ) ENGINE=MergeTree ORDER BY (ServiceName, SpanName, Timestamp)")

(def logs-ddl
  "CREATE TABLE IF NOT EXISTS otel_logs (
     Timestamp DateTime64(9), TraceId String, SpanId String, Body String,
     ServiceName LowCardinality(String),
     LogAttributes Map(LowCardinality(String), String)
   ) ENGINE=MergeTree ORDER BY (ServiceName, Timestamp)")

(def ^:private span-names
  (str "['GET /api/v1/cart','POST /api/v1/cart/checkout','GET /api/v1/products',"
       "'GET /health','POST /api/v1/orders','GET /api/v1/orders/{id}',"
       "'SELECT orders','SELECT products','INSERT orders','redis.GET',"
       "'redis.SETEX','kafka.produce','GET /api/v1/users/{id}',"
       "'POST /api/v1/payments','grpc.InventoryService/Check','GET /metrics',"
       "'POST /api/v1/shipping/quote','SELECT inventory','GET /api/v1/search',"
       "'UPDATE cart'][(number %% 20) + 1]"))

(def ^:private resource-attributes
  (str "map('k8s.pod.name', concat('checkout-', toString(number %% 8), "
       "'-7d9f8b6c5-', lower(substring(hex(cityHash64(intDiv(number, 4096))), 1, 5))),"
       "'k8s.namespace.name','production',"
       "'k8s.node.name', concat('ip-10-0-', toString(number %% 200), '-', toString(number %% 250), '.ec2.internal'),"
       "'k8s.deployment.name', concat('checkout-', toString(number %% 8)),"
       "'host.name', concat('ip-10-0-', toString(number %% 200), '-', toString(number %% 250)),"
       "'service.version','2.14.3','service.namespace','shop','cloud.region','us-east-1')"))

(def ^:private span-attributes
  (str "map('http.request.method',['GET','POST','PUT','DELETE'][(number %% 4) + 1],"
       "'http.route','/api/v1/cart/checkout',"
       "'http.response.status_code', toString([200,200,200,200,201,400,404,500][(number %% 8) + 1]),"
       "'url.path', concat('/api/v1/cart/', toString(number %% 100000)),"
       "'url.scheme','https','server.address','checkout.shop.svc.cluster.local',"
       "'server.port','8443','network.protocol.version','1.1',"
       "'user_agent.original','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36',"
       "'client.address', concat('10.0.', toString(number %% 200), '.', toString(number %% 250)))"))

(def traces-generator
  (str "INSERT INTO otel_traces SELECT
  toDateTime64(1756300000 + (number %% 86400) + (number %% 1000)/1000.0, 9),
  lower(hex(MD5(toString(intDiv(number, 12))))),
  lower(hex(reinterpretAsFixedString(cityHash64(number)))),
  if(number %% 12 = 0, '', lower(hex(reinterpretAsFixedString(cityHash64(number - 1))))),
  '', " span-names ",
  ['SPAN_KIND_SERVER','SPAN_KIND_CLIENT','SPAN_KIND_INTERNAL','SPAN_KIND_PRODUCER'][(number %% 4) + 1],
  concat('checkout-', toString(number %% 8)), " resource-attributes ",
  'io.opentelemetry.instrumentation.http', '2.14.3', " span-attributes ",
  (number * 2654435761) %% 500000000,
  if(number %% 8 = 5, 'STATUS_CODE_ERROR', 'STATUS_CODE_UNSET'),
  if(number %% 8 = 5, 'upstream connect error or disconnect/reset before headers', ''),
  '[]', '[]'
FROM numbers(%d, %d)"))

(def logs-generator
  "INSERT INTO otel_logs SELECT
  toDateTime64(1756300000 + (number %% 86400), 9),
  lower(hex(MD5(toString(intDiv(number, 12))))),
  lower(hex(reinterpretAsFixedString(cityHash64(number)))),
  concat('request ', toString(number), ' completed for tenant ', toString(number %% 97)),
  concat('checkout-', toString(number %% 8)),
  map('http.method','POST','http.route','/api/v1/cart/checkout')
FROM numbers(%d, %d)")

(defn- millis-since [start-nanos]
  (/ (double (- (host/mono-nanos) start-nanos)) 1e6))

(defn- timed [thunk]
  (let [start (host/mono-nanos)
        value (thunk)]
    [(millis-since start) value]))

(defn- file-bytes [path]
  (let [file (io/file path)]
    (if (.exists file) (.length file) 0)))

(defn- tree-bytes [path]
  (let [file (io/file path)]
    (cond
      (not (.exists file)) 0
      (.isDirectory file) (reduce + 0 (map (comp tree-bytes str) (.listFiles file)))
      :else (.length file))))

(defn- tree-files [path]
  (let [file (io/file path)]
    (cond
      (not (.exists file)) 0
      (.isDirectory file) (reduce + 0 (map (comp tree-files str) (.listFiles file)))
      :else 1)))

(defn- per-second [n millis]
  (if (pos? millis) (/ n (/ millis 1000.0)) 0.0))

(defn- scratch-path [prefix suffix]
  (str (or (System/getenv "SEGMENT_BENCH_DIR") "/tmp/segment-export-bench")
       "/" prefix "-" (System/currentTimeMillis) suffix))

(defn- ensure-scratch! []
  (.mkdirs (io/file (or (System/getenv "SEGMENT_BENCH_DIR")
                        "/tmp/segment-export-bench"))))

;; ---------------------------------------------------------------------------
;; 1. Export format comparison

(def export-formats
  "Each entry is [label format-clause settings-clause]. JSONEachRow is included
  because it is the payload the Durable statement WAL already carries, so it is
  the honest baseline for any columnar segment to beat on bytes."
  [["Parquet (default)" "Parquet" ""]
   ["Parquet zstd" "Parquet" " SETTINGS output_format_parquet_compression_method='zstd'"]
   ["Parquet lz4" "Parquet" " SETTINGS output_format_parquet_compression_method='lz4'"]
   ["Native" "Native" ""]
   ["ArrowStream" "ArrowStream" ""]
   ["JSONEachRow" "JSONEachRow" ""]])

(defn run-export-formats!
  "Export the whole table once per candidate format and report rate and size."
  [connection rows]
  (println)
  (println (format "%-22s %9s %12s %12s %9s %10s"
                   "format" "ms" "rows/s" "bytes" "B/row" "MB/s"))
  (doseq [[label fmt settings] export-formats]
    (let [path (scratch-path "fmt" (str "-" (str/replace label #"[^A-Za-z0-9]" "") ".out"))
          sql (str "SELECT * FROM otel_traces INTO OUTFILE '" path
                   "' TRUNCATE FORMAT " fmt settings)]
      (try
        (let [[millis _] (timed #(jdbc/execute! connection sql))
              size (file-bytes path)]
          (println (format "%-22s %9.0f %12.0f %12d %9.1f %10.1f"
                           label millis (per-second rows millis) size
                           (/ (double size) rows)
                           (per-second (/ (double size) 1048576.0) millis)))
          (.delete (io/file path)))
        (catch Exception e
          (println (format "%-22s FAILED %s" label (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; 2. Segment size sensitivity

(def slice-predicates
  "Bounded time ranges over the generated 24-hour window. These deliberately
  avoid ORDER BY: `otel_traces` orders by (ServiceName, SpanName, Timestamp),
  so a timestamp range is not a primary-key prefix and adding a sort would
  measure the sort rather than the export."
  [["1 min" "Timestamp >= toDateTime64(1756300000,9) AND Timestamp < toDateTime64(1756300060,9)"]
   ["10 min" "Timestamp >= toDateTime64(1756300000,9) AND Timestamp < toDateTime64(1756300600,9)"]
   ["1 hour" "Timestamp >= toDateTime64(1756300000,9) AND Timestamp < toDateTime64(1756303600,9)"]
   ["6 hour" "Timestamp >= toDateTime64(1756300000,9) AND Timestamp < toDateTime64(1756321600,9)"]
   ["all" "1"]])

(defn run-slice-sizes!
  "Export bounded slices to show how segment size changes rate and bytes/row."
  [connection]
  (println)
  (println (format "%-10s %10s %9s %12s %12s %9s"
                   "slice" "rows" "ms" "rows/s" "bytes" "B/row"))
  (doseq [[label predicate] slice-predicates]
    (let [path (scratch-path "slice" (str "-" (str/replace label #"\s" "") ".parquet"))
          rows (:n (jdbc/fetch-one connection
                                   (str "SELECT count() AS n FROM otel_traces WHERE " predicate)))
          sql (str "SELECT * FROM otel_traces WHERE " predicate
                   " INTO OUTFILE '" path "' TRUNCATE FORMAT Parquet"
                   " SETTINGS output_format_parquet_compression_method='zstd'")
          [millis _] (timed #(jdbc/execute! connection sql))
          size (file-bytes path)]
      (println (format "%-10s %10d %9.0f %12.0f %12d %9.1f"
                       label rows millis (per-second rows millis) size
                       (if (pos? rows) (/ (double size) rows) 0.0)))
      (.delete (io/file path)))))

;; ---------------------------------------------------------------------------
;; 3. Durable WAL cost at two row widths

(defn- read-lines [path]
  (with-open [reader (io/reader path)]
    (vec (line-seq reader))))

(defn- payload-bytes [lines]
  (reduce + 0 (map #(alength (.getBytes ^String % "UTF-8")) lines)))

(defn- export-json-rows!
  "Materialize generated rows as the JSONEachRow payload the exporter sends,
  so the Durable run measures the real statement path rather than rows built
  in Jolt."
  [connection table path]
  (jdbc/execute! connection
                 (str "SELECT * FROM " table " INTO OUTFILE '" path
                      "' TRUNCATE FORMAT JSONEachRow"))
  path)

(defn run-durable-width!
  "Insert the same payload through Durable V1 and report rows/s, MB/s, and the
  bytes actually published to the object store."
  [label table ddl lines batch-size]
  (let [total (count lines)
        payload (payload-bytes lines)
        statements (mapv (fn [chunk]
                           (str "INSERT INTO " table " FORMAT JSONEachRow\n"
                                (str/join "\n" chunk)))
                         (partition-all batch-size lines))
        root (scratch-path "store" "")
        dbspec (durable/writer-dbspec
                {:namespace-backend (local-posix/local-backend root)
                 :object-id "bench" :owner "segment-export-benchmark"
                 :database "otel"})]
    (with-open [connection (jdbc/connection dbspec)]
      (jdbc/execute! connection ddl)
      (let [[insert-ms _] (timed #(doseq [statement statements]
                                    (jdbc/execute! connection statement)))
            [flush-ms _] (timed #(durable/flush! connection))
            wal-bytes (tree-bytes (str root "/objects/bench/wal"))
            wal-objects (tree-files (str root "/objects/bench/wal"))
            [checkpoint-ms _] (timed #(durable/checkpoint! connection))
            checkpoint-bytes (tree-bytes (str root "/objects/bench/checkpoints"))]
        (println (format "%-8s %8d rows %8.1f B/row %8.0f ms %9.0f rows/s %7.2f MB/s"
                         label total (/ (double payload) total) insert-ms
                         (per-second total insert-ms)
                         (per-second (/ (double payload) 1048576.0) insert-ms)))
        (println (format "         flush %6.0f ms -> WAL %11d B (%7.1f B/row, %.2fx payload, %d objects)"
                         flush-ms wal-bytes (/ (double wal-bytes) total)
                         (/ (double wal-bytes) payload) wal-objects))
        (println (format "         ckpt  %6.0f ms -> ckpt %11d B (%7.1f B/row)"
                         checkpoint-ms checkpoint-bytes
                         (/ (double checkpoint-bytes) total)))
        {:rows total :payload payload :wal wal-bytes :checkpoint checkpoint-bytes}))))

;; ---------------------------------------------------------------------------

(defn- generate! [connection ddl generator rows]
  (jdbc/execute! connection ddl)
  (let [[millis _] (timed #(jdbc/execute! connection (format generator 0 rows)))]
    (println (format "generated %d rows in %.0f ms" rows millis))))

(defn- storage-summary [connection table]
  (let [row (jdbc/fetch-one connection
                            (str "SELECT sum(bytes_on_disk) AS disk,"
                                 " sum(data_uncompressed_bytes) AS raw"
                                 " FROM system.parts WHERE table='" table "' AND active"))]
    row))

(defn- prepare-payloads!
  "Generate both payload widths and materialize them as JSONEachRow files.

  chDB owns one storage path per process, so this closes its connection before
  any Durable writer opens."
  [rows durable-rows]
  (let [wide-path (scratch-path "wide" ".jsonl")
        narrow-path (scratch-path "narrow" ".jsonl")]
    (with-open [connection (jdbc/connection {:subprotocol "chdb" :subname ":memory:"})]
      (generate! connection traces-ddl traces-generator rows)
      (let [{:keys [disk raw]} (storage-summary connection "otel_traces")]
        (println (format "  MergeTree on disk %d B (%.1f B/row); uncompressed %d B (%.1f B/row)"
                         disk (/ (double disk) rows) raw (/ (double raw) rows))))
      (println "\n== 1. export format comparison (whole table) ==")
      (run-export-formats! connection rows)
      (println "\n== 2. segment size sensitivity (Parquet zstd) ==")
      (run-slice-sizes! connection)
      (export-json-rows! connection "otel_traces" wide-path)
      (jdbc/execute! connection "DROP TABLE otel_traces")
      (generate! connection logs-ddl logs-generator durable-rows)
      (export-json-rows! connection "otel_logs" narrow-path))
    [wide-path narrow-path]))

(defn -main
  "Usage: jolt -M:segment-benchmark [rows] [durable-rows] [batch-size]

  `rows` sizes the export measurements; `durable-rows` sizes the much slower
  Durable statement path, and defaults to a twentieth of `rows`."
  [& args]
  (ensure-scratch!)
  (let [rows (Long/parseLong (or (first args) "1000000"))
        durable-rows (Long/parseLong (or (second args) (str (max 10000 (quot rows 20)))))
        batch-size (Long/parseLong (or (nth args 2 nil) "512"))
        _ (println (format "segment export benchmark: rows=%d durable-rows=%d batch=%d"
                           rows durable-rows batch-size))
        [wide-path narrow-path] (prepare-payloads! rows durable-rows)
        wide (vec (take durable-rows (read-lines wide-path)))
        narrow (read-lines narrow-path)]
    (println "\n== 3. Durable V1 statement WAL, two payload widths ==")
    (println "   (the WAL path is bounded by payload bytes, not rows)")
    (run-durable-width! "narrow" "otel_logs" logs-ddl narrow batch-size)
    (run-durable-width! "wide" "otel_traces" traces-ddl wide batch-size)
    (.delete (io/file wide-path))
    (.delete (io/file narrow-path))
    (println "\nSee docs/benchmarks/segment-export.md for the recorded baseline.")))
