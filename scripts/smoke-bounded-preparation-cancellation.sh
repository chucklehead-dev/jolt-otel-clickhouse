#!/usr/bin/env bash
# Published dependency graph; causal native control, never a timed benchmark.
set -euo pipefail
[[ $# == 0 ]] || exit 64
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"
task_jolt=${JOLT_BIN:?explicit stock Jolt binary required}
task_lib=${JOLT_CHDB_LIB:?explicit native library required}
wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
printf '%s  %s\n' 7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94 "$task_jolt" | sha256sum -c -
printf '%s  %s\n' a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557 "$task_lib" | sha256sum -c -
task_root=$(mktemp -d /tmp/chdb-preparation-cancel-20261021.XXXXXXXX)
printf 'EVIDENCE_ROOT=%s\n' "$task_root"
base=("$wrapper" env JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0 JOLT_CHDB_LIB="$task_lib"
      "$task_jolt" -Srepro -Sdeps '{:paths ["src" "test"]}' -A:stock-span-codec)
"${base[@]}" -Spath > "$task_root/classpath.txt"
rg -q 'jolt-chdb.git/312812f0dfeff5498a6d1aa29b0476a97d8b65f3/src' "$task_root/classpath.txt"
rg -q 'casselc_otel.git/38278ab4cf3b7bad7ed817170ca7e380122068b3/src' "$task_root/classpath.txt"
sources=(deps.edn src/otel/exporter/chdb.clj src/otel/exporter/chdb/preparation.clj
         test/otel/exporter/chdb_preparation_native_cancellation.clj
         test/otel/exporter/chdb_preparation_adapter_test.clj "$0" "$wrapper" "$task_jolt" "$task_lib")
IFS=: read -r -a providers < "$task_root/classpath.txt"
for provider in "${providers[@]}"; do
  for relative in otel/sdk/export.clj otel/sdk/preparation.clj otel/sdk/lifecycle.clj \
      jdbc/chdb/durable.clj jdbc/chdb/durable/writer.clj jdbc/chdb/durable/control.clj \
      jdbc/chdb/owned_statement.clj jdbc/chdb/owned_ascii_wal.ss; do
    [[ ! -f "$provider/$relative" ]] || sources+=("$provider/$relative")
  done
done
sha256sum "${sources[@]}" > "$task_root/source-before.sha256"
for ownership in owned borrowed; do
  mkdir "$task_root/$ownership"
  timeout 120 "${base[@]}" -m otel.exporter.chdb-preparation-native-cancellation \
    writer "$task_root/$ownership" "$ownership" > "$task_root/$ownership-writer.log" 2>&1
  timeout 120 "${base[@]}" -m otel.exporter.chdb-preparation-native-cancellation \
    reader "$task_root/$ownership" > "$task_root/$ownership-reader.log" 2>&1
  rg -q '^:native-cancellation-writer-confirmed' "$task_root/$ownership-writer.log"
  rg -q '^:native-cancellation-reader-confirmed' "$task_root/$ownership-reader.log"
done
sha256sum -c "$task_root/source-before.sha256" > "$task_root/source-check.log"
printf 'NATIVE_CANCELLATION_CONTROL_PASSED=%s\n' "$task_root"
# Retain failures and this small first control until independent review; no
# unconditional EXIT cleanup and no bulk benchmark data is created here.
