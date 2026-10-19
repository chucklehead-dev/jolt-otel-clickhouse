#!/usr/bin/env bash
set -euo pipefail
export JOLT_BIN=/home/chuck/ai-src/evidence/jolt-hosted-stock-fold-7098-20261018.sh
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
test "$(sha256sum "$JOLT_CHDB_LIB" | cut -d ' ' -f1)" = a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557
cd /home/chuck/ai-src/worktrees/oscope-singleton-event-exporter-20261019
for task_consumer in embedded standalone embedded-typed; do
  task_output=/home/chuck/ai-src/evidence/oscope-singleton-event-native-$task_consumer-20261019.log
  test ! -e "$task_output"
  timeout --signal=TERM --kill-after=5s 120s "$JOLT_BIN" -Srepro \
    -A:test-durable:native-json-byte-batch \
    -m oscope.export-options-native-test-runner stream "$task_consumer" \
    --require-json-companion > "$task_output" 2>&1
  printf 'consumer=%s terminal0 output=%s\n' "$task_consumer" "$task_output"
done
