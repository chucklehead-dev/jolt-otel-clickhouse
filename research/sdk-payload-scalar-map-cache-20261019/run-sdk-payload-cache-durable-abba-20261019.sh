#!/usr/bin/env bash
set -euo pipefail
task_evidence=/home/chuck/ai-src/evidence
task_exporter=/home/chuck/ai-src/worktrees/exporter-sdk-payload-scalar-cache-consumer-20261019
task_sdk=/home/chuck/ai-src/worktrees/otel-payload-scalar-map-native-budget-20261019
task_reader=/home/chuck/ai-src/worktrees/exporter-live-empty-link-writers-20261018
task_fixture=/home/chuck/ai-src/worktrees/chdb-current-typed-storage-0821-20261014
task_old_sdk=/home/chuck/ai-src/worktrees/otel-batch-threshold-wakeup-20261017
task_old_json=/home/chuck/ai-src/worktrees/data-json-qualified-8d557cc-20261017
test "$(git -C "$task_sdk" rev-parse --short=7 HEAD)" = c474e02
git -C "$task_sdk" diff --quiet HEAD
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
test "$(sha256sum "$JOLT_CHDB_LIB" | cut -d ' ' -f1)" = a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557
export SDK_STORAGE_ARM=local SDK_STREAM_ARM=stream SDK_EXPORT_CALLERS=1
export CHDB_BENCH_OWNED_OUTPUT=true CHDB_BENCH_SAMPLE_COUNTERS=true
export BENCH_CLK_TCK
BENCH_CLK_TCK=$(getconf CLK_TCK)
task_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"] :deps {io.github.casselc/otel {:local/root \"$task_sdk\" :exclusions [jolt-lang/jolt-crypto]}}}"
task_reader_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"] :deps {org.clojure/data.json {:local/root \"$task_old_json\"} io.github.casselc/otel {:local/root \"$task_old_sdk\"}}}"
task_index=0
for task_arm in off on on off; do
  task_report=$task_evidence/sdk-payload-cache-durable-fixed-20261019-$task_index-$task_arm
  test ! -e "$task_report.writer.log"
  task_root=$(mktemp -d /tmp/sdk-payload-cache-durable-20261019.XXXXXXXX)
  printf 'index=%s cache=%s owned_store=%s\n' "$task_index" "$task_arm" "$task_root"
  unset CHDB_BENCH_METADATA_READER
  cd "$task_exporter"
  /usr/bin/time -v -o "$task_report.writer.os-resource.txt" timeout --signal=TERM --kill-after=5s 240s "$task_evidence/jolt-hosted-stock-fold-7098-20261018.sh" -Srepro -Sdeps "$task_deps" -A:native-json-byte-batch \
    "$task_evidence/sdk-payload-cache-durable-writer-20261019.clj" "$task_root" "$task_arm" > "$task_report.writer.log" 2>&1
  cp "$task_root/writer.edn" "$task_report.writer.edn"
  cmp "$task_root/writer.edn" "$task_report.writer.edn"
  cd "$task_reader"
  export CHDB_BENCH_METADATA_READER=stock
  timeout --signal=TERM --kill-after=5s 120s "$task_evidence/jolt-measured-1ae-20261015.sh" -Srepro -Sdeps "$task_reader_deps" \
    "$task_evidence/nested-sorted-sdk-durable-reader-20261018.clj" "$task_root" > "$task_report.reader.log" 2>&1
  cp "$task_root/reader.edn" "$task_report.reader.edn"
  cmp "$task_root/reader.edn" "$task_report.reader.edn"
  bb "$task_evidence/validate-rich-sdk-scaling-20261019.clj" "$task_report" 1 > "$task_report.validation.edn"
  printf 'index=%s cache=%s writer_reader_validator_terminal0 owned_store=%s\n' "$task_index" "$task_arm" "$task_root"
  case "$task_root" in /tmp/sdk-payload-cache-durable-20261019.????????) ;; *) exit 91;; esac
  test -d "$task_root" && test ! -L "$task_root"
  test "$(realpath "$task_root")" = "$task_root"
  # Explicit per-arm synthetic store: no removal on a failed child/gate.
  rm -rf -- "$task_root"
  test ! -e "$task_root"
  printf 'index=%s retired_validated_synthetic_store=%s\n' "$task_index" "$task_root"
  task_index=$((task_index + 1))
done
test "$task_index" = 4
printf 'all_four_cache_arms_terminal0_and_owned_stores_retired\n'
