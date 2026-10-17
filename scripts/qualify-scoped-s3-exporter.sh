#!/usr/bin/env bash
set -euo pipefail
umask 077
runner=${1:?supply the selected Jolt executable or pinned wrapper}
(( $# <= 3 ))
format=${2:-json-each-row}
case "$format" in
  json-each-row|json-compact-each-row) ;;
  *) echo invalid-fixture-format >&2; exit 1 ;;
esac
mode=${3:-text}
case "$mode" in
  text) ;;
  owned) [[ "$format" == json-compact-each-row ]] || exit 64 ;;
  *) echo invalid-fixture-output-mode >&2; exit 64 ;;
esac
runtime_options=()
if [[ "$mode" == owned ]]; then runtime_options=(-A:native-json-byte-batch); fi
driver=${JOLT_CHDB_SOURCE_ROOT:?supply the exact root dependency checkout}
revision=$("$runner" -Srepro -e "(require 'clojure.edn) (println (get-in (clojure.edn/read-string (slurp \"deps.edn\")) [:deps 'io.github.chucklehead-dev/jolt-chdb :git/sha]))")
[[ "$revision" =~ ^[a-f0-9]{40}$ ]]
JOLT_EXPECTED_DRIVER_REV="$revision" bash scripts/qualify-durable-typed-native.sh --check-reviewed-source
test -f "$driver/test/support/s3_http_fixture.py"
fixture_dir=$(mktemp -d /tmp/exporter-scoped-s3.XXXXXXXX)
server_pid=
cleanup() {
  local result=$?
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  if (( result == 0 )); then
    rm -rf -- "$fixture_dir"
  else
    printf 'Scoped S3 fixture retained for diagnosis: %s\n' "$fixture_dir" >&2
  fi
}
trap cleanup EXIT
python3 "$driver/test/support/s3_http_fixture.py" "$fixture_dir/port" \
  > "$fixture_dir/fixture.log" 2>&1 &
server_pid=$!
for _ in $(seq 1 100); do
  [[ -s "$fixture_dir/port" ]] && break
  kill -0 "$server_pid"
  sleep 0.05
done
test -s "$fixture_dir/port"
port=$(<"$fixture_dir/port")
[[ "$port" =~ ^[0-9]+$ ]]
for phase in writer reader; do
  JOLT_SCOPED_S3_OWNED_OUTPUT="$mode" "$runner" -Srepro -Sdeps '{:paths ["src" "test"]}' "${runtime_options[@]}" \
    -m otel.exporter.chdb-scoped-s3-native-test "$phase" \
    "http://127.0.0.1:$port" "$fixture_dir" "$format"
done
echo scoped-s3-exporter-qualified
