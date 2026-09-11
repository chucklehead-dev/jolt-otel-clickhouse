#!/usr/bin/env bash
# Centre-side half of the segment-export benchmark.
#
# `bench/otel/exporter/segment_export_benchmark.clj` measures what it costs an
# edge writer to produce columnar segments. This script measures what it costs
# a real ClickHouse server to consume them, and compares that against ingesting
# the same rows as the JSONEachRow payload the Durable statement WAL already
# carries.
#
# It exists because an embedded chDB is not a valid stand-in for the centre:
# chDB is single-threaded here and its Parquet writer emits far larger row
# groups than ClickHouse's, and both distortions push the comparison in
# opposite directions. Every centre-side number in
# docs/benchmarks/segment-export.md comes from this script.
#
# Usage:
#   bench/segment-export/clickhouse-ingest.sh [segments] [rows-per-segment]
#
# Requires a `clickhouse` binary on PATH or in CLICKHOUSE_BIN. Nothing here
# touches the repository; all state lives under WORK (default /tmp/ch-segment-bench).
set -euo pipefail

SEGMENTS="${1:-60}"
ROWS_PER_SEGMENT="${2:-50000}"
TOTAL=$((SEGMENTS * ROWS_PER_SEGMENT))
WORK="${WORK:-/tmp/ch-segment-bench}"
CH="${CLICKHOUSE_BIN:-$(command -v clickhouse || true)}"

if [ -z "$CH" ]; then
  echo "No clickhouse binary. Set CLICKHOUSE_BIN, or fetch one with:" >&2
  echo "  curl https://clickhouse.com/ | sh" >&2
  exit 2
fi

COLS="Timestamp DateTime64(9), TraceId String, SpanId String, ParentSpanId String,
TraceState String, SpanName String, SpanKind String, ServiceName String,
ResourceAttributes Map(String, String), ScopeName String, ScopeVersion String,
SpanAttributes Map(String, String), Duration UInt64, StatusCode String,
StatusMessage String, EventsJSON String, LinksJSON String"

rm -rf "$WORK"
mkdir -p "$WORK"/{data,tmp,user_files/seg,fmt}

cat > "$WORK/config.xml" <<XML
<clickhouse>
  <logger><level>warning</level><console>1</console></logger>
  <tcp_port>9000</tcp_port>
  <listen_host>127.0.0.1</listen_host>
  <path>$WORK/data/</path>
  <tmp_path>$WORK/tmp/</tmp_path>
  <user_files_path>$WORK/user_files/</user_files_path>
  <format_schema_path>$WORK/fmt/</format_schema_path>
  <users><default><password></password><profile>default</profile><quota>default</quota>
    <networks><ip>::/0</ip></networks></default></users>
  <profiles><default/></profiles><quotas><default/></quotas>
</clickhouse>
XML

"$CH" server --config-file="$WORK/config.xml" > "$WORK/server.log" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  "$CH" client -q "SELECT 1" >/dev/null 2>&1 && break
  sleep 1
done
"$CH" client -q "SELECT 1" >/dev/null 2>&1 || { echo "server did not start; see $WORK/server.log" >&2; exit 1; }
echo "clickhouse $("$CH" client -q 'SELECT version()')  cores=$(nproc)"

# The generated span shape matches the Jolt-side harness exactly, so the two
# halves measure the same bytes.
cat > "$WORK/select.sql" <<'SQL'
SELECT
  toDateTime64(1756300000 + (number % 86400) + (number % 1000)/1000.0, 9) AS Timestamp,
  lower(hex(MD5(toString(intDiv(number, 12))))) AS TraceId,
  lower(hex(reinterpretAsFixedString(cityHash64(number)))) AS SpanId,
  if(number % 12 = 0, '', lower(hex(reinterpretAsFixedString(cityHash64(number - 1))))) AS ParentSpanId,
  '' AS TraceState,
  ['GET /api/v1/cart','POST /api/v1/cart/checkout','GET /api/v1/products','GET /health','POST /api/v1/orders','GET /api/v1/orders/{id}','SELECT orders','SELECT products','INSERT orders','redis.GET','redis.SETEX','kafka.produce','GET /api/v1/users/{id}','POST /api/v1/payments','grpc.InventoryService/Check','GET /metrics','POST /api/v1/shipping/quote','SELECT inventory','GET /api/v1/search','UPDATE cart'][(number % 20) + 1] AS SpanName,
  ['SPAN_KIND_SERVER','SPAN_KIND_CLIENT','SPAN_KIND_INTERNAL','SPAN_KIND_PRODUCER'][(number % 4) + 1] AS SpanKind,
  concat('checkout-', toString(number % 8)) AS ServiceName,
  map('k8s.pod.name', concat('checkout-', toString(number % 8), '-7d9f8b6c5-', lower(substring(hex(cityHash64(intDiv(number, 4096))), 1, 5))),'k8s.namespace.name','production','k8s.node.name', concat('ip-10-0-', toString(number % 200), '-', toString(number % 250), '.ec2.internal'),'k8s.deployment.name', concat('checkout-', toString(number % 8)),'host.name', concat('ip-10-0-', toString(number % 200), '-', toString(number % 250)),'service.version','2.14.3','service.namespace','shop','cloud.region','us-east-1') AS ResourceAttributes,
  'io.opentelemetry.instrumentation.http' AS ScopeName, '2.14.3' AS ScopeVersion,
  map('http.request.method',['GET','POST','PUT','DELETE'][(number % 4) + 1],'http.route','/api/v1/cart/checkout','http.response.status_code', toString([200,200,200,200,201,400,404,500][(number % 8) + 1]),'url.path', concat('/api/v1/cart/', toString(number % 100000)),'url.scheme','https','server.address','checkout.shop.svc.cluster.local','server.port','8443','network.protocol.version','1.1','user_agent.original','Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36','client.address', concat('10.0.', toString(number % 200), '.', toString(number % 250))) AS SpanAttributes,
  (number * 2654435761) % 500000000 AS Duration,
  if(number % 8 = 5, 'STATUS_CODE_ERROR', 'STATUS_CODE_UNSET') AS StatusCode,
  if(number % 8 = 5, 'upstream connect error or disconnect/reset before headers', '') AS StatusMessage,
  '[]' AS EventsJSON, '[]' AS LinksJSON
SQL

echo "generating $SEGMENTS segments x $ROWS_PER_SEGMENT rows ..."
for i in $(seq -f "%03g" 0 $((SEGMENTS - 1))); do
  offset=$((10#$i * ROWS_PER_SEGMENT))
  {
    printf "INSERT INTO FUNCTION file('%s/user_files/seg/seg_%s.parquet', Parquet)\n" "$WORK" "$i"
    cat "$WORK/select.sql"
    printf "FROM numbers(%d, %d) SETTINGS engine_file_truncate_on_insert=1\n" "$offset" "$ROWS_PER_SEGMENT"
  } > "$WORK/one.sql"
  "$CH" client --queries-file "$WORK/one.sql"
done
# The same rows as the JSONEachRow payload the statement WAL carries.
{ printf "INSERT INTO FUNCTION file('%s/user_files/wal.jsonl', JSONEachRow)\n" "$WORK"
  cat "$WORK/select.sql"
  printf "FROM numbers(0, %d) SETTINGS engine_file_truncate_on_insert=1\n" "$TOTAL"; } > "$WORK/one.sql"
"$CH" client --queries-file "$WORK/one.sql"

SEG_BYTES=$(du -sb "$WORK/user_files/seg" | cut -f1)
WAL_BYTES=$(stat -c %s "$WORK/user_files/wal.jsonl")
python3 -c "
print('  segments %.1f MB (%.1f B/row); JSONEachRow %.1f MB (%.1f B/row) -> %.1fx'
      % ($SEG_BYTES/1048576, $SEG_BYTES/$TOTAL, $WAL_BYTES/1048576, $WAL_BYTES/$TOTAL, $WAL_BYTES/$SEG_BYTES))"

recreate() { "$CH" client -q "DROP TABLE IF EXISTS $1"; "$CH" client -q "CREATE TABLE $1 ($COLS) ENGINE=MergeTree ORDER BY (ServiceName, SpanName, Timestamp)"; }
settle() { while [ "$("$CH" client -q 'SELECT count() FROM system.merges')" != "0" ]; do sleep 0.5; done; }
report() { python3 -c "print('  %-42s %6.2f s -> %9.0f rows/s' % ('$1', $3-$2, $TOTAL/($3-$2)))"; }
parts() { "$CH" client -q "SELECT count() FROM system.parts WHERE table='$1' AND active"; }

cat > "$WORK/ins.sh" <<INS
#!/usr/bin/env bash
"$CH" client -q "INSERT INTO t_par SELECT * FROM file('seg/seg_\$1.parquet', Parquet)"
INS
chmod +x "$WORK/ins.sh"

echo
echo "== ingest patterns ($SEGMENTS segments, $TOTAL rows) =="
recreate t_seq; settle
a=$(date +%s.%N)
for i in $(seq -f "%03g" 0 $((SEGMENTS - 1))); do
  "$CH" client -q "INSERT INTO t_seq SELECT * FROM file('seg/seg_$i.parquet', Parquet)"
done
b=$(date +%s.%N)
report "$SEGMENTS sequential INSERTs" "$a" "$b"; echo "     active parts: $(parts t_seq)"

recreate t_glob; settle
a=$(date +%s.%N)
"$CH" client -q "INSERT INTO t_glob SELECT * FROM file('seg/seg_*.parquet', Parquet)"
b=$(date +%s.%N)
report "1 glob INSERT over all segments" "$a" "$b"; echo "     active parts: $(parts t_glob)"

recreate t_par; settle
( for _ in $(seq 1 400); do parts t_par 2>/dev/null; sleep 0.2; done > "$WORK/parts.txt" ) &
SAMPLER=$!
a=$(date +%s.%N)
seq -f "%03g" 0 $((SEGMENTS - 1)) | xargs -P 8 -n1 "$WORK/ins.sh"
b=$(date +%s.%N)
sleep 1; kill $SAMPLER 2>/dev/null || true
report "$SEGMENTS INSERTs, 8-way parallel" "$a" "$b"
echo "     peak active parts: $(sort -n "$WORK/parts.txt" | tail -1); settled: $(parts t_par)"
"$CH" client -q "SELECT event, value FROM system.events WHERE event IN ('DelayedInserts','RejectedInserts')" | sed 's/^/     /'

echo
echo "== format comparison, same rows, max_threads=4 =="
for spec in "Parquet seg/seg_*.parquet" "JSONEachRow wal.jsonl"; do
  set -- $spec
  recreate t_fmt; settle
  cat "$WORK/user_files/$2" > /dev/null 2>&1 || cat "$WORK"/user_files/seg/*.parquet > /dev/null
  a=$(date +%s.%N)
  "$CH" client -q "INSERT INTO t_fmt SELECT * FROM file('$2', $1) SETTINGS max_insert_threads=4, max_threads=4"
  b=$(date +%s.%N)
  report "$1" "$a" "$b"
done

echo
echo "== Parquet row-group structure (affects ingest parallelism) =="
"$CH" client -q "SELECT num_rows, num_row_groups FROM file('seg/seg_000.parquet', ParquetMetadata) FORMAT TSV" | sed 's/^/  seg_000 rows\/row-groups: /'
