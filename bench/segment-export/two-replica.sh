#!/usr/bin/env bash
# Two-replica half of the segment-export benchmark.
#
# bench/segment-export/replicated-dedup.sh measures one replica against one
# Keeper, which covers the Keeper round trip on the insert path but says
# nothing about replication itself. This script stands up two replicas of one
# shard and answers the questions a real centre cares about:
#
#   1. What does a second replica cost on the insert path, and how far behind
#      does it run under the default asynchronous replication?
#   2. What does insert_quorum=2 cost, if the centre wants both copies durable
#      before it acknowledges an SQS message?
#   3. Does insert_deduplication_token still hold when a redelivered segment
#      lands on a DIFFERENT replica than the original -- including when both
#      arrive at once? Consumers are load balanced, so this is the normal case,
#      not an edge case.
#   4. Does it hold across SHARDS? It does not, and that constrains how a
#      consumer may route object keys.
#
# Usage:
#   bench/segment-export/two-replica.sh [segments] [rows-per-segment]
#
# Requires a `clickhouse` binary in CLICKHOUSE_BIN or on PATH. Binds 9000, 9001,
# 9009, 9010, 9181 and 9234 on loopback. All state lives under WORK (default
# /tmp/ch-2rep); nothing touches the repository.
set -euo pipefail

SEGMENTS="${1:-30}"; RPS="${2:-25000}"; TOTAL=$((SEGMENTS * RPS))
WORK="${WORK:-/tmp/ch-2rep}"
CH="${CLICKHOUSE_BIN:-$(command -v clickhouse || true)}"
if [ -z "$CH" ]; then
  echo "No clickhouse binary. Set CLICKHOUSE_BIN, or fetch one with:" >&2
  echo "  curl https://clickhouse.com/ | sh" >&2
  exit 2
fi
rm -rf "$WORK"; mkdir -p "$WORK"/keeper/{log,snap} "$WORK"/shared_files
for n in 1 2; do mkdir -p "$WORK/s$n"/{data,tmp,fmt}; done

COLS="Timestamp DateTime64(9), TraceId String, SpanId String, ParentSpanId String,
TraceState String, SpanName String, SpanKind String, ServiceName String,
ResourceAttributes Map(String, String), ScopeName String, ScopeVersion String,
SpanAttributes Map(String, String), Duration UInt64, StatusCode String,
StatusMessage String, EventsJSON String, LinksJSON String"

# listen_host is explicit because Keeper otherwise binds [::1], which fails
# outright on a host with IPv6 disabled.
cat > "$WORK/keeper.xml" <<XML
<clickhouse>
  <logger><level>warning</level><console>1</console></logger>
  <listen_host>127.0.0.1</listen_host>
  <keeper_server>
    <tcp_port>9181</tcp_port><server_id>1</server_id>
    <log_storage_path>$WORK/keeper/log</log_storage_path>
    <snapshot_storage_path>$WORK/keeper/snap</snapshot_storage_path>
    <coordination_settings><operation_timeout_ms>10000</operation_timeout_ms>
      <session_timeout_ms>30000</session_timeout_ms><raft_logs_level>warning</raft_logs_level></coordination_settings>
    <raft_configuration><server><id>1</id><hostname>127.0.0.1</hostname><port>9234</port></server></raft_configuration>
  </keeper_server>
</clickhouse>
XML
for n in 1 2; do
  tcp=$((8999 + n)); inter=$((9008 + n))
  # interserver_http_port is not optional: without it a replica never creates
  # its is_active ephemeral node and sits in readonly mode reporting no
  # ZooKeeper exception at all. Both replicas share one user_files directory
  # so the segments are generated once.
  cat > "$WORK/s$n.xml" <<XML
<clickhouse>
  <logger><level>warning</level><console>1</console></logger>
  <listen_host>127.0.0.1</listen_host>
  <tcp_port>$tcp</tcp_port>
  <interserver_http_port>$inter</interserver_http_port>
  <interserver_http_host>127.0.0.1</interserver_http_host>
  <path>$WORK/s$n/data/</path><tmp_path>$WORK/s$n/tmp/</tmp_path>
  <user_files_path>$WORK/shared_files/</user_files_path>
  <format_schema_path>$WORK/s$n/fmt/</format_schema_path>
  <mark_cache_size>268435456</mark_cache_size>
  <zookeeper><node><host>127.0.0.1</host><port>9181</port></node></zookeeper>
  <macros><shard>01</shard><replica>r$n</replica></macros>
  <users><default><password></password><profile>default</profile><quota>default</quota>
    <networks><ip>::/0</ip></networks></default></users>
  <profiles><default/></profiles><quotas><default/></quotas>
</clickhouse>
XML
done

"$CH" keeper --config-file="$WORK/keeper.xml" > "$WORK/keeper.log" 2>&1 &
KP=$!
"$CH" server --config-file="$WORK/s1.xml" > "$WORK/s1.log" 2>&1 & P1=$!
"$CH" server --config-file="$WORK/s2.xml" > "$WORK/s2.log" 2>&1 & P2=$!
trap 'kill $P1 $P2 $KP 2>/dev/null || true' EXIT
c1() { "$CH" client --port 9000 "$@"; }
c2() { "$CH" client --port 9001 "$@"; }
for _ in $(seq 1 60); do c1 -q "SELECT 1" >/dev/null 2>&1 && c2 -q "SELECT 1" >/dev/null 2>&1 && break; sleep 1; done
c1 -q "SELECT 1" >/dev/null 2>&1 || { echo "s1 down"; tail -5 "$WORK/s1.log"; exit 1; }
c2 -q "SELECT 1" >/dev/null 2>&1 || { echo "s2 down"; tail -5 "$WORK/s2.log"; exit 1; }
echo "two replicas up: $(c1 -q 'SELECT version()')"

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
echo "generating $SEGMENTS x $RPS ..."
for i in $(seq -f "%03g" 0 $((SEGMENTS-1))); do
  off=$((10#$i * RPS))
  { printf "INSERT INTO FUNCTION file('%s/shared_files/seg_%s.parquet', Parquet)\n" "$WORK" "$i"
    cat "$WORK/select.sql"; printf "FROM numbers(%d, %d) SETTINGS engine_file_truncate_on_insert=1\n" "$off" "$RPS"; } > "$WORK/g.sql"
  c1 --queries-file "$WORK/g.sql"
done
echo "$WORK ready"

mk() { # name, [table settings]
  for c in c1 c2; do $c -q "DROP TABLE IF EXISTS $1 SYNC" >/dev/null 2>&1 || true; done
  c1 -q "CREATE TABLE $1 ($COLS) ENGINE=ReplicatedMergeTree('/clickhouse/tables/{shard}/$1','{replica}') ORDER BY (ServiceName, SpanName, Timestamp) ${2:-}"
  c2 -q "CREATE TABLE $1 ($COLS) ENGINE=ReplicatedMergeTree('/clickhouse/tables/{shard}/$1','{replica}') ORDER BY (ServiceName, SpanName, Timestamp) ${2:-}"
}
n1() { c1 -q "SELECT count() FROM $1"; }
n2() { c2 -q "SELECT count() FROM $1"; }
sync_wait() { # table, expected -> seconds until replica 2 agrees
  local t=$1 want=$2 start now
  start=$(date +%s.%N)
  while [ "$(n2 "$t")" != "$want" ]; do sleep 0.05; done
  now=$(date +%s.%N); python3 -c "print('%.2f'%($now-$start))"
}
echo
echo "== 1. insert on r1, default quorum (async replication) =="
mk t_q0 "SETTINGS replicated_deduplication_window=100000"
cat > "$WORK/ins0.sh" <<INS
#!/usr/bin/env bash
"$CH" client --port 9000 -q "INSERT INTO t_q0 SELECT * FROM file('seg_\$1.parquet', Parquet) SETTINGS insert_deduplication_token='seg_\$1'"
INS
chmod +x "$WORK/ins0.sh"
a=$(date +%s.%N); seq -f "%03g" 0 $((SEGMENTS-1)) | xargs -P 8 -n1 "$WORK/ins0.sh"; b=$(date +%s.%N)
lag=$(sync_wait t_q0 "$TOTAL")
python3 -c "print('  insert wall %.2f s -> %.0f rows/s (%.1f ms/insert); replica 2 caught up %s s later'%($b-$a,$TOTAL/($b-$a),($b-$a)*1000/$SEGMENTS,'$lag'))"
echo "  r1=$(n1 t_q0)  r2=$(n2 t_q0)"

echo
echo "== 2. insert on r1 with insert_quorum=2 (sync before ack) =="
mk t_q2 "SETTINGS replicated_deduplication_window=100000"
cat > "$WORK/ins2.sh" <<INS
#!/usr/bin/env bash
"$CH" client --port 9000 -q "INSERT INTO t_q2 SELECT * FROM file('seg_\$1.parquet', Parquet) SETTINGS insert_deduplication_token='seg_\$1', insert_quorum=2, insert_quorum_parallel=1"
INS
chmod +x "$WORK/ins2.sh"
a=$(date +%s.%N); seq -f "%03g" 0 $((SEGMENTS-1)) | xargs -P 8 -n1 "$WORK/ins2.sh"; b=$(date +%s.%N)
lag=$(sync_wait t_q2 "$TOTAL")
python3 -c "print('  insert wall %.2f s -> %.0f rows/s (%.1f ms/insert); replica 2 lag after ack %s s'%($b-$a,$TOTAL/($b-$a),($b-$a)*1000/$SEGMENTS,'$lag'))"
echo "  r1=$(n1 t_q2)  r2=$(n2 t_q2)"

echo
echo "== 3. cross-replica deduplication =="
mk x_a "SETTINGS replicated_deduplication_window=100000"
c1 -q "INSERT INTO x_a SELECT * FROM file('seg_000.parquet', Parquet) SETTINGS insert_deduplication_token='cross-key'"
sync_wait x_a "$RPS" >/dev/null
before=$(n1 x_a)
c2 -q "INSERT INTO x_a SELECT * FROM file('seg_000.parquet', Parquet) SETTINGS insert_deduplication_token='cross-key'"
sleep 2
echo "  A same token, r1 then r2 (after sync):   $before -> r1=$(n1 x_a) r2=$(n2 x_a)"

mk x_b "SETTINGS replicated_deduplication_window=100000"
cat > "$WORK/race1.sh" <<INS
#!/usr/bin/env bash
"$CH" client --port \$1 -q "INSERT INTO x_b SELECT * FROM file('seg_000.parquet', Parquet) SETTINGS insert_deduplication_token='race-key'"
INS
chmod +x "$WORK/race1.sh"
printf "9000\n9001\n9000\n9001\n" | xargs -P 4 -n1 "$WORK/race1.sh"
sleep 3
echo "  B same token, both replicas concurrently: 0 -> r1=$(n1 x_b) r2=$(n2 x_b)  (expect $RPS)"

echo
echo "== 4. replication counters on replica 2 =="
c2 -q "SELECT event, value FROM system.events WHERE event IN ('ReplicatedPartFetches','ReplicatedPartFetchesOfMerged','ReplicatedPartMerges','ReplicatedPartChecks') AND value > 0 FORMAT TSV" | sed 's/^/  /'
c2 -q "SELECT table, absolute_delay, queue_size, inserts_in_queue, total_replicas, active_replicas FROM system.replicas WHERE table LIKE 't_q%' OR table LIKE 'x_%' ORDER BY table FORMAT TSV" | sed 's/^/  /'
echo "  interserver bytes read by r2: $(c2 -q "SELECT formatReadableSize(value) FROM system.events WHERE event='ReadBufferFromS3Bytes' UNION ALL SELECT formatReadableSize(sum(bytes_on_disk)) FROM system.parts WHERE active AND table='t_q0'" 2>/dev/null | tail -1)"

echo
echo "== 5. does deduplication cross SHARD boundaries? =="
# Two tables with DIFFERENT Keeper paths = two shards, not two replicas.
c1 -q "DROP TABLE IF EXISTS sh_a SYNC"; c2 -q "DROP TABLE IF EXISTS sh_b SYNC"
c1 -q "CREATE TABLE sh_a ($COLS) ENGINE=ReplicatedMergeTree('/clickhouse/tables/shard-A/seg','r1') ORDER BY (ServiceName, SpanName, Timestamp)"
c2 -q "CREATE TABLE sh_b ($COLS) ENGINE=ReplicatedMergeTree('/clickhouse/tables/shard-B/seg','r1') ORDER BY (ServiceName, SpanName, Timestamp)"
c1 -q "INSERT INTO sh_a SELECT * FROM file('seg_000.parquet', Parquet) SETTINGS insert_deduplication_token='shard-key'"
c2 -q "INSERT INTO sh_b SELECT * FROM file('seg_000.parquet', Parquet) SETTINGS insert_deduplication_token='shard-key'"
sleep 1
a=$(c1 -q "SELECT count() FROM sh_a"); b=$(c2 -q "SELECT count() FROM sh_b")
total=$((a + b))
echo "  same token to shard A and shard B: A=$a B=$b  cluster total=$total (one copy would be $RPS)"
[ "$total" = "$RPS" ] && echo "  -> deduplicated across shards" || echo "  -> NOT deduplicated: each shard keeps its own history"
