#!/usr/bin/env bash
# ReplicatedMergeTree half of the segment-export benchmark.
#
# The centre-side design depends on `insert_deduplication_token` to make
# at-least-once segment delivery idempotent, and that token only works on a
# table whose deduplication history lives somewhere. This script stands up a
# single-node ClickHouse Keeper plus a server and answers two questions the
# non-replicated harness cannot:
#
#   1. What does replication cost per insert, with and without a token?
#   2. What does the deduplication actually guarantee, and where does it stop?
#
# The second matters more than the first. Deduplication here has sharp edges
# that are silent in both directions: a colliding token drops data, and a
# spurious token difference admits duplicates.
#
# Usage:
#   bench/segment-export/replicated-dedup.sh [segments] [rows-per-segment]
#
# Requires a `clickhouse` binary on PATH or in CLICKHOUSE_BIN. All state lives
# under WORK (default /tmp/ch-repl-bench); nothing touches the repository.
set -euo pipefail

SEGMENTS="${1:-60}"
ROWS_PER_SEGMENT="${2:-50000}"
TOTAL=$((SEGMENTS * ROWS_PER_SEGMENT))
WORK="${WORK:-/tmp/ch-repl-bench}"
CH="${CLICKHOUSE_BIN:-$(command -v clickhouse || true)}"
KEEPER_PORT="${KEEPER_PORT:-9181}"
RAFT_PORT="${RAFT_PORT:-9234}"

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
mkdir -p "$WORK"/keeper/{log,snap} "$WORK"/s1/{data,tmp,fmt,user_files}

# listen_host is explicit because Keeper otherwise binds [::1], which fails
# outright on a host with IPv6 disabled.
cat > "$WORK/keeper.xml" <<XML
<clickhouse>
  <logger><level>warning</level><console>1</console></logger>
  <listen_host>127.0.0.1</listen_host>
  <keeper_server>
    <tcp_port>$KEEPER_PORT</tcp_port>
    <server_id>1</server_id>
    <log_storage_path>$WORK/keeper/log</log_storage_path>
    <snapshot_storage_path>$WORK/keeper/snap</snapshot_storage_path>
    <coordination_settings>
      <operation_timeout_ms>10000</operation_timeout_ms>
      <session_timeout_ms>30000</session_timeout_ms>
      <raft_logs_level>warning</raft_logs_level>
    </coordination_settings>
    <raft_configuration>
      <server><id>1</id><hostname>127.0.0.1</hostname><port>$RAFT_PORT</port></server>
    </raft_configuration>
  </keeper_server>
</clickhouse>
XML

# interserver_http_port is not optional: without it a replica cannot publish its
# host, never creates its is_active ephemeral node, and the table sits in
# readonly mode reporting no ZooKeeper exception at all.
cat > "$WORK/s1.xml" <<XML
<clickhouse>
  <logger><level>warning</level><console>1</console></logger>
  <listen_host>127.0.0.1</listen_host>
  <tcp_port>9000</tcp_port>
  <interserver_http_port>9009</interserver_http_port>
  <interserver_http_host>127.0.0.1</interserver_http_host>
  <path>$WORK/s1/data/</path>
  <tmp_path>$WORK/s1/tmp/</tmp_path>
  <user_files_path>$WORK/s1/user_files/</user_files_path>
  <format_schema_path>$WORK/s1/fmt/</format_schema_path>
  <mark_cache_size>536870912</mark_cache_size>
  <zookeeper><node><host>127.0.0.1</host><port>$KEEPER_PORT</port></node></zookeeper>
  <macros><shard>01</shard><replica>r1</replica></macros>
  <users><default><password></password><profile>default</profile><quota>default</quota>
    <networks><ip>::/0</ip></networks></default></users>
  <profiles><default/></profiles><quotas><default/></quotas>
</clickhouse>
XML

"$CH" keeper --config-file="$WORK/keeper.xml" > "$WORK/keeper.log" 2>&1 &
KEEPER_PID=$!
"$CH" server --config-file="$WORK/s1.xml" > "$WORK/s1.log" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID $KEEPER_PID 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do "$CH" client -q "SELECT 1" >/dev/null 2>&1 && break; sleep 1; done
"$CH" client -q "SELECT 1" >/dev/null 2>&1 || { echo "server did not start; see $WORK/s1.log" >&2; exit 1; }
echo "clickhouse $("$CH" client -q 'SELECT version()')  cores=$(nproc)"
echo "deduplication defaults: $("$CH" client -q "SELECT concat(name,'=',value) FROM system.merge_tree_settings WHERE name LIKE '%deduplication_window%' ORDER BY name" | tr '\n' ' ')"

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
  { printf "INSERT INTO FUNCTION file('%s/s1/user_files/seg_%s.parquet', Parquet)\n" "$WORK" "$i"
    cat "$WORK/select.sql"
    printf "FROM numbers(%d, %d) SETTINGS engine_file_truncate_on_insert=1\n" "$offset" "$ROWS_PER_SEGMENT"; } > "$WORK/one.sql"
  "$CH" client --queries-file "$WORK/one.sql"
done

settle() { while [ "$("$CH" client -q 'SELECT count() FROM system.merges')" != "0" ]; do sleep 0.5; done; }
rows() { "$CH" client -q "SELECT count() FROM $1"; }
mk() { # name, engine-clause, [settings]
  "$CH" client -q "DROP TABLE IF EXISTS $1 SYNC"
  "$CH" client -q "CREATE TABLE $1 ($COLS) ENGINE=$2 ORDER BY (ServiceName, SpanName, Timestamp) ${3:-}"
}
repl() { mk "$1" "ReplicatedMergeTree('/clickhouse/tables/{shard}/$1','{replica}')" "${2:-}"; }
ins() { # table, segment, [token]
  local extra=""
  [ -n "${3:-}" ] && extra="SETTINGS insert_deduplication_token='$3'"
  "$CH" client -q "INSERT INTO $1 SELECT * FROM file('seg_$2.parquet', Parquet) $extra"
}

echo
echo "== 1. insert cost: MergeTree vs ReplicatedMergeTree =="
printf "  %-28s %-17s %8s %12s %12s\n" engine pattern wall_s rows/s ms/insert
for spec in "t_plain MergeTree" "t_repl replicated" "t_tok replicated+token"; do
  set -- $spec; t=$1; label=$2
  case "$label" in
    MergeTree) mk "$t" "MergeTree" ;;
    *) repl "$t" "SETTINGS replicated_deduplication_window=100000" ;;
  esac
  cat > "$WORK/ins_$t.sh" <<INS
#!/usr/bin/env bash
tok=""
[ "$label" = "replicated+token" ] && tok="SETTINGS insert_deduplication_token='seg_\$1'"
"$CH" client -q "INSERT INTO $t SELECT * FROM file('seg_\$1.parquet', Parquet) \$tok"
INS
  chmod +x "$WORK/ins_$t.sh"
  for mode in seq par; do
    "$CH" client -q "TRUNCATE TABLE $t"; settle; sleep 1
    a=$(date +%s.%N)
    if [ "$mode" = seq ]; then
      for i in $(seq -f "%03g" 0 $((SEGMENTS - 1))); do "$WORK/ins_$t.sh" "$i"; done
    else
      seq -f "%03g" 0 $((SEGMENTS - 1)) | xargs -P 8 -n1 "$WORK/ins_$t.sh"
    fi
    b=$(date +%s.%N)
    pattern=$([ "$mode" = seq ] && echo "$SEGMENTS sequential" || echo "$SEGMENTS x 8-parallel")
    python3 -c "print('  %-28s %-17s %8.2f %12.0f %12.1f'%('$label','$pattern',$b-$a,$TOTAL/($b-$a),($b-$a)*1000/$SEGMENTS))"
  done
done
echo "  Keeper ops: $("$CH" client -q "SELECT value FROM system.events WHERE event='ZooKeeperTransactions'") transactions total"

echo
echo "== 2. what deduplication actually guarantees =="
repl d_a
ins d_a 000; first=$(rows d_a); ins d_a 000
echo "  A identical INSERT..SELECT twice, no token:      $first -> $(rows d_a)   content dedup does NOT cover INSERT..SELECT"
"$CH" client -n -q "DROP TABLE IF EXISTS d_v SYNC; CREATE TABLE d_v (a UInt64) ENGINE=ReplicatedMergeTree('/clickhouse/tables/{shard}/d_v','{replica}') ORDER BY a; INSERT INTO d_v VALUES (1),(2),(3);" >/dev/null
v1=$(rows d_v); "$CH" client -q "INSERT INTO d_v VALUES (1),(2),(3)" >/dev/null
echo "  A' same again but INSERT..VALUES:                $v1 -> $(rows d_v)   content dedup DOES cover VALUES"
repl d_b
ins d_b 000 "same-key"; b1=$(rows d_b); ins d_b 001 "same-key"
echo "  B same token, different segments:                $b1 -> $(rows d_b)   second segment SILENTLY DROPPED"
repl d_c
ins d_c 000 "key-a"; c1=$(rows d_c); ins d_c 000 "key-b"
echo "  C different tokens, identical segment:           $c1 -> $(rows d_c)   token REPLACES content dedup"
repl d_d "SETTINGS replicated_deduplication_window=2"
ins d_d 000 k0; ins d_d 001 k1; d1=$(rows d_d); ins d_d 002 k2; ins d_d 003 k3; ins d_d 000 k0
echo "  D retry after window eviction (window=2):        $d1 -> $(rows d_d)   late retry slips through"
mk d_e "MergeTree"
ins d_e 000 k0; e1=$(rows d_e); ins d_e 000 k0
echo "  E non-replicated MergeTree, default:             $e1 -> $(rows d_e)   token IGNORED"
mk d_f "MergeTree" "SETTINGS non_replicated_deduplication_window=100"
ins d_f 000 k0; f1=$(rows d_f); ins d_f 000 k0
echo "  F non-replicated + non_replicated_dedup_window:  $f1 -> $(rows d_f)   token honoured, no Keeper needed"
repl d_g
cat > "$WORK/dup.sh" <<INS
#!/usr/bin/env bash
"$CH" client -q "INSERT INTO d_g SELECT * FROM file('seg_000.parquet', Parquet) SETTINGS insert_deduplication_token='race-key'"
INS
chmod +x "$WORK/dup.sh"
seq 1 8 | xargs -P 8 -I{} "$WORK/dup.sh"
echo "  G 8 concurrent inserts, one token:               0 -> $(rows d_g)   race-safe"

echo
echo "== 3. full duplicate replay of every segment =="
before=$(rows t_tok)
a=$(date +%s.%N); seq -f "%03g" 0 $((SEGMENTS - 1)) | xargs -P 8 -n1 "$WORK/ins_t_tok.sh"; b=$(date +%s.%N)
after=$(rows t_tok)
python3 -c "print('  replayed all $SEGMENTS segments in %.2f s: %s -> %s (%s)'%($b-$a,'$before','$after','all deduped' if '$before'=='$after' else 'DUPLICATES LANDED'))"
