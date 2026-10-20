#!/usr/bin/env bash
set -euo pipefail
task_candidate=/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019
task_reader=/home/chuck/ai-src/worktrees/exporter-live-empty-link-writers-20261018
task_fixture=/home/chuck/ai-src/worktrees/chdb-current-typed-storage-0821-20261014
task_old_sdk=/home/chuck/ai-src/worktrees/otel-batch-threshold-wakeup-20261017
task_old_json=/home/chuck/ai-src/worktrees/data-json-qualified-8d557cc-20261017
task_binary=/home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt
task_wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
task_out=/home/chuck/ai-src/evidence/nested-fragments-durable-abba-20261019
test ! -e "$task_out"
for task_entry in "$task_reader:473edbb06fa11070968de35f8b953f62cb4b6cd3" "$task_fixture:3fd96c4691c5db49ed4d3ef55622aaa6b9074127" "$task_old_sdk:37c684a2c370fc82fde82331635973bce87a52b3" "$task_old_json:8d557cc18eb9526ed29ae5613089c3793d5973fa"; do
  task_path=${task_entry%:*}
  test "$(git -C "$task_path" rev-parse HEAD)" = "${task_entry##*:}"
  git -C "$task_path" diff --quiet HEAD
done
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
test "$(sha256sum "$JOLT_CHDB_LIB" | cut -d ' ' -f1)" = a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557
test "$(sha256sum "$task_binary" | cut -d ' ' -f1)" = 7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94
export SDK_STORAGE_ARM=local SDK_STREAM_ARM=stream SDK_EXPORT_CALLERS=1
export CHDB_BENCH_OWNED_OUTPUT=true CHDB_BENCH_SAMPLE_COUNTERS=true
export JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0
export BENCH_CLK_TCK
BENCH_CLK_TCK=$(getconf CLK_TCK)
task_writer_deps="{:paths [\"src\" \"test\" \"bench\" \"$task_fixture/test\" \"$task_fixture/bench\"]}"
task_reader_deps="{:paths [\"src\" \"test\" \"bench\" \"$task_fixture/test\" \"$task_fixture/bench\"] :deps {org.clojure/data.json {:local/root \"$task_old_json\"} io.github.casselc/otel {:local/root \"$task_old_sdk\"}}}"
mkdir -p "$task_out"
cd "$task_candidate"
sha256sum src/otel/exporter/chdb.clj incubator/codec/src/jolt/codec/experimental.clj incubator/codec/src/jolt/codec/json.ss incubator/codec/src/jolt/traversal/kernel.ss \
  research/nested-fragments-20261019/durable-arm.clj research/nested-fragments-20261019/independent-reader.clj \
  research/library-nested-text-20261019/rich-sdk-scaling-screen-20261019.clj \
  research/library-nested-text-20261019/nested-sorted-sdk-physical-reader-20261018.clj \
  research/library-nested-text-20261019/trace-affinity-scaling-helpers-20261019.clj \
  research/library-nested-text-20261019/nested-sorted-sdk-fixture-20261018.clj \
  research/library-nested-text-20261019/validate-rich-sdk-scaling-20261019.clj > "$task_out/sources.sha256"
task_index=0
for task_arm in off on on off; do
  task_store=$(mktemp -d /tmp/nested-fragments-durable.XXXXXXXX)
  task_report=$task_out/$task_index-$task_arm
  printf 'start index=%s arm=%s owned_store=%s\n' "$task_index" "$task_arm" "$task_store"
  cd "$task_candidate"
  sha256sum -c "$task_out/sources.sha256" > "$task_report.source-before.log"
  timeout --signal=TERM --kill-after=5s 240s "$task_wrapper" "$task_binary" -Srepro -Sdeps "$task_writer_deps" \
    -A:experimental-span-codec research/nested-fragments-20261019/durable-arm.clj "$task_store" "$task_arm" \
    > "$task_report.writer.log" 2>&1
  cp "$task_store/writer.edn" "$task_report.writer.edn"
  cd "$task_reader"
  timeout --signal=TERM --kill-after=5s 150s "$task_wrapper" env CHDB_BENCH_METADATA_READER=stock \
    "$task_binary" -Srepro -Sdeps "$task_reader_deps" \
    "$task_candidate/research/nested-fragments-20261019/independent-reader.clj" "$task_store" \
    > "$task_report.reader.log" 2>&1
  cp "$task_store/reader.edn" "$task_report.reader.edn"
  cmp "$task_store/writer.edn" "$task_report.writer.edn"
  cmp "$task_store/reader.edn" "$task_report.reader.edn"
  cd "$task_candidate"
  bb research/library-nested-text-20261019/validate-rich-sdk-scaling-20261019.clj "$task_report" 1 \
    > "$task_report.validation.edn"
  sha256sum -c "$task_out/sources.sha256" > "$task_report.source-after.log"
  du -sk "$task_store" > "$task_report.retired-kib.txt"
  case "$task_store" in /tmp/nested-fragments-durable.????????) ;; *) exit 91;; esac
  test ! -L "$task_store"
  test "$(realpath "$task_store")" = "$task_store"
  # Only exact owned synthetic store, after terminal writer/reader/validator,
  # copied/cmp receipts and immutable source checks. Failures retain evidence.
  rm -r -- "$task_store"
  test ! -e "$task_store"
  printf 'verified-and-retired index=%s arm=%s\n' "$task_index" "$task_arm"
  task_index=$((task_index + 1))
done
printf '%s\n' 'terminal0: four arms independently recovered and owned stores retired'
