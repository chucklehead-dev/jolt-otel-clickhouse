#!/usr/bin/env bash
set -euo pipefail
task_evidence=/home/chuck/ai-src/evidence
task_exporter=/home/chuck/ai-src/worktrees/exporter-stock-fold-json-consumer-20261018
task_reader=/home/chuck/ai-src/worktrees/exporter-live-empty-link-writers-20261018
task_fixture=/home/chuck/ai-src/worktrees/chdb-current-typed-storage-0821-20261014
task_old_sdk=/home/chuck/ai-src/worktrees/otel-batch-threshold-wakeup-20261017
task_old_json=/home/chuck/ai-src/worktrees/data-json-qualified-8d557cc-20261017
for task_entry in "$task_exporter:4d48871cf2ac7a2ad0bf6517042c624df376a9bf" "$task_reader:473edbb06fa11070968de35f8b953f62cb4b6cd3" "$task_fixture:3fd96c4691c5db49ed4d3ef55622aaa6b9074127" "$task_old_sdk:37c684a2c370fc82fde82331635973bce87a52b3" "$task_old_json:8d557cc18eb9526ed29ae5613089c3793d5973fa"; do
  task_path=${task_entry%:*}
  test "$(git -C "$task_path" rev-parse HEAD)" = "${task_entry##*:}"
  git -C "$task_path" diff --quiet HEAD
done
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
test "$(sha256sum "$JOLT_CHDB_LIB" | cut -d ' ' -f1)" = a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557
export SDK_STORAGE_ARM=local SDK_STREAM_ARM=stream SDK_EXPORT_CALLERS=1
export CHDB_BENCH_OWNED_OUTPUT=true CHDB_BENCH_SAMPLE_COUNTERS=true
task_paths="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"]}"
task_reader_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"] :deps {org.clojure/data.json {:local/root \"$task_old_json\"} io.github.casselc/otel {:local/root \"$task_old_sdk\"}}}"
task_index=0
for task_arm in baseline candidate candidate baseline; do
  task_report=$task_evidence/stock-fold-real-binary-abba-20261018-$task_index-$task_arm
  test ! -e "$task_report.writer.log"
  task_root=$(mktemp -d /tmp/stock-fold-real-binary-abba-20261018.XXXXXXXX)
  printf 'index=%s arm=%s owned_store=%s\n' "$task_index" "$task_arm" "$task_root"
  task_runtime=$task_evidence/jolt-measured-1ae-20261015.sh
  if test "$task_arm" = candidate; then
    task_runtime=$task_evidence/jolt-local-stock-fold-6319-20261018.sh
  fi
  export SDK_STOCK_FOLD_ARM=$task_arm
  unset CHDB_BENCH_METADATA_READER
  cd "$task_exporter"
  timeout --signal=TERM --kill-after=5s 180s "$task_runtime" -Srepro -Sdeps "$task_paths" -M:native-json-byte-batch \
    "$task_evidence/stock-fold-real-binary-abba-writer-20261018.clj" "$task_root" 1 saturated > "$task_report.writer.log" 2>&1
  cp "$task_root/writer.edn" "$task_report.writer.edn"
  cmp "$task_root/writer.edn" "$task_report.writer.edn"
  cd "$task_reader"
  export CHDB_BENCH_METADATA_READER=stock
  timeout --signal=TERM --kill-after=5s 120s "$task_evidence/jolt-measured-1ae-20261015.sh" -Srepro -Sdeps "$task_reader_deps" \
    "$task_evidence/nested-sorted-sdk-durable-reader-20261018.clj" "$task_root" > "$task_report.reader.log" 2>&1
  cp "$task_root/reader.edn" "$task_report.reader.edn"
  cmp "$task_root/reader.edn" "$task_report.reader.edn"
  bb "$task_evidence/validate-stock-fold-real-binary-durable-20261018.clj" "$task_report" > "$task_report.validation.edn"
  printf 'index=%s arm=%s writer_reader_validator_terminal0 owned_store=%s\n' "$task_index" "$task_arm" "$task_root"
  task_index=$((task_index + 1))
done
test "$task_index" = 4
printf 'all_four_arms_terminal0\n'
# Keep stores until main verifies the exact terminal session and scalar receipts.
