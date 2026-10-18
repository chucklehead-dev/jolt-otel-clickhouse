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
export SDK_STORAGE_ARM=local CHDB_BENCH_METADATA_READER=stock
task_paths="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"]}"
task_reader_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"] :deps {org.clojure/data.json {:local/root \"$task_old_json\"} io.github.casselc/otel {:local/root \"$task_old_sdk\"}}}"
task_index=0
for task_arm in json rowbinary rowbinary json; do
  task_report=$task_evidence/rowbinary-rich-direct-abba-20261018-$task_index-$task_arm
  test ! -e "$task_report.writer.log"
  task_root=$(mktemp -d /tmp/rowbinary-rich-direct-abba-20261018.XXXXXXXX)
  printf 'index=%s arm=%s owned_store=%s\n' "$task_index" "$task_arm" "$task_root"
  export RICH_FORMAT_ARM=$task_arm
  cd "$task_exporter"
  timeout --signal=TERM --kill-after=5s 240s "$task_evidence/jolt-local-stock-fold-6319-20261018.sh" -Srepro -Sdeps "$task_paths" -M:native-json-byte-batch \
    "$task_evidence/rowbinary-rich-direct-writer-20261018.clj" "$task_root" > "$task_report.writer.log" 2>&1
  cp "$task_root/writer.edn" "$task_report.writer.edn"
  cmp "$task_root/writer.edn" "$task_report.writer.edn"
  cd "$task_reader"
  timeout --signal=TERM --kill-after=5s 180s "$task_evidence/jolt-measured-1ae-20261015.sh" -Srepro -Sdeps "$task_reader_deps" \
    "$task_evidence/rowbinary-rich-direct-reader-20261018.clj" "$task_root" > "$task_report.reader.log" 2>&1
  cp "$task_root/reader.edn" "$task_report.reader.edn"
  cmp "$task_root/reader.edn" "$task_report.reader.edn"
  printf 'index=%s arm=%s writer_reader_terminal0 owned_store=%s\n' "$task_index" "$task_arm" "$task_root"
  task_index=$((task_index + 1))
done
test "$task_index" = 4
printf 'all_four_format_arms_terminal0\n'
# Main validates and retains bounded receipts before explicit-path cleanup.
