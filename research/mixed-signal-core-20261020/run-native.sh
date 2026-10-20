#!/usr/bin/env bash
set -euo pipefail
task_binary=/home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt
task_wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
task_out=${1:-/home/chuck/ai-src/evidence/exporter-mixed-codec-fresh-20261020}
test ! -e "$task_out"
test "$(sha256sum "$task_binary" | cut -d ' ' -f1)" = 7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94
export JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
test "$(sha256sum "$JOLT_CHDB_LIB" | cut -d ' ' -f1)" = a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557
task_deps='{:paths ["src" "bench" "incubator/codec/test"]}'
mkdir -p "$task_out"
sha256sum src/otel/exporter/chdb.clj deps.edn \
  incubator/codec/test/otel/exporter/chdb_mixed_codec_native.clj \
  incubator/codec/test/otel/exporter/chdb_mixed_codec_test.clj \
  research/mixed-signal-core-20261020/run-native.sh > "$task_out/sources.sha256"
for task_wire in raw-ticks auto; do
  task_root=$(mktemp -d /tmp/mixed-codec-qualified.XXXXXXXX)
  printf 'wire=%s owned_store=%s\n' "$task_wire" "$task_root"
  timeout --signal=TERM --kill-after=5s 180s "$task_wrapper" "$task_binary" -Srepro -A:stock-span-codec \
    -Sdeps "$task_deps" -m otel.exporter.chdb-mixed-codec-native writer "$task_root" "$task_wire" \
    > "$task_out/$task_wire.writer.log" 2>&1
  timeout --signal=TERM --kill-after=5s 150s "$task_wrapper" "$task_binary" -Srepro -A:stock-span-codec \
    -Sdeps "$task_deps" -m otel.exporter.chdb-mixed-codec-native reader "$task_root" \
    > "$task_out/$task_wire.reader.log" 2>&1
  for task_file in writer.edn reader.edn expected.edn; do
    cp "$task_root/$task_file" "$task_out/$task_wire.$task_file"
    cmp "$task_root/$task_file" "$task_out/$task_wire.$task_file"
  done
  sha256sum -c "$task_out/sources.sha256" > "$task_out/$task_wire.source-after.log"
  du -sk "$task_root" > "$task_out/$task_wire.retired-kib.txt"
  case "$task_root" in /tmp/mixed-codec-qualified.????????) ;; *) exit 91;; esac
  test ! -L "$task_root"
  test "$(realpath "$task_root")" = "$task_root"
  # Exact owned synthetic store; terminal writer/reader, full-field equality,
  # precision/shape controls, immutable sources and copied/cmp receipts passed.
  rm -r -- "$task_root"
  printf 'verified-and-retired wire=%s\n' "$task_wire"
done
printf '%s\n' 'terminal0: raw-ticks and auto full native mixed-signal gates passed'
