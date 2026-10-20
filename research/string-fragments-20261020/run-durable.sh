#!/usr/bin/env bash
set -euo pipefail
task_candidate=/home/chuck/ai-src/worktrees/exporter-codec-string-fragments-20261020
task_sdk=/home/chuck/.jolt/gitlibs/https___github.com_casselc_otel.git/14f58e5a57d6cd1760451a426e40d8cddcf53949
task_support=/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019
task_reader=/home/chuck/ai-src/worktrees/exporter-live-empty-link-writers-20261018
task_fixture=/home/chuck/ai-src/worktrees/chdb-current-typed-storage-0821-20261014
task_old_sdk=/home/chuck/ai-src/worktrees/otel-batch-threshold-wakeup-20261017
task_old_json=/home/chuck/ai-src/worktrees/data-json-qualified-8d557cc-20261017
task_binary=/home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt
task_wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
task_out=${1:?NEW absolute output directory}
[[ "$task_out" == /* ]]
test ! -e "$task_out"
for task_entry in "$task_support:0af1008185cf84577298d3a0a0b2db87b97bddd5" "$task_reader:473edbb06fa11070968de35f8b953f62cb4b6cd3" "$task_fixture:3fd96c4691c5db49ed4d3ef55622aaa6b9074127" "$task_old_sdk:37c684a2c370fc82fde82331635973bce87a52b3" "$task_old_json:8d557cc18eb9526ed29ae5613089c3793d5973fa"; do
  task_path=${task_entry%:*}
  test "$(git -C "$task_path" rev-parse HEAD)" = "${task_entry##*:}"
  git -C "$task_path" diff --quiet HEAD
done
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
printf '%s  %s\n' a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557 "$JOLT_CHDB_LIB" | sha256sum -c -
printf '%s  %s\n' 7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94 "$task_binary" | sha256sum -c -
export SDK_STORAGE_ARM=local SDK_STREAM_ARM=stream SDK_EXPORT_CALLERS=1
export CHDB_BENCH_OWNED_OUTPUT=true CHDB_BENCH_SAMPLE_COUNTERS=true
export JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0
export BENCH_CLK_TCK
BENCH_CLK_TCK=$(getconf CLK_TCK)
task_writer_deps='{:paths ["src" "test" "research/string-fragments-20261020" "'"$task_support"'/test" "'"$task_support"'/bench" "'"$task_fixture"'/test" "'"$task_fixture"'/bench"]}'
task_reader_deps='{:paths ["src" "test" "bench" "'"$task_fixture"'/test" "'"$task_fixture"'/bench"] :deps {org.clojure/data.json {:local/root "'"$task_old_json"'"} io.github.casselc/otel {:local/root "'"$task_old_sdk"'"}}}'
mkdir -p "$task_out"
cd "$task_candidate"
sha256sum deps.edn src/otel/exporter/chdb.clj \
  research/string-fragments-20261020/durable-arm.clj research/string-fragments-20261020/run-durable.sh \
  incubator/codec/src/jolt/codec/scalar_text/experimental.clj incubator/codec/src/jolt/codec/scalar_text/plan.ss incubator/codec/src/jolt/codec/scalar_text/planner.ss \
  incubator/codec/src/jolt/codec/experimental.clj incubator/codec/src/jolt/codec/json.ss incubator/codec/src/jolt/traversal/kernel.ss \
  "$task_sdk/src/otel/any_value.clj" "$task_sdk/src/otel/any_value_native.clj" \
  "$task_sdk/resources/otel/payload_scalar_string_map_cache.ss" \
  "$task_support/research/nested-fragments-20261019/independent-reader.clj" \
  "$task_support/research/library-nested-text-20261019/rich-sdk-scaling-screen-20261019.clj" \
  "$task_support/research/library-nested-text-20261019/nested-sorted-sdk-physical-reader-20261018.clj" \
  "$task_support/research/library-nested-text-20261019/nested-sorted-sdk-fixture-20261018.clj" \
  "$task_support/research/library-nested-text-20261019/trace-affinity-scaling-helpers-20261019.clj" \
  "$task_support/research/library-nested-text-20261019/validate-rich-sdk-scaling-20261019.clj" > "$task_out/sources.sha256"
task_index=0
for task_arm in off on on off; do
  task_store=$(mktemp -d /tmp/string-fragments-durable.XXXXXXXX)
  task_report=$task_out/$task_index-$task_arm
  printf 'start index=%s arm=%s owned_store=%s\n' "$task_index" "$task_arm" "$task_store"
  cd "$task_candidate"
  sha256sum -c "$task_out/sources.sha256" > "$task_report.source-before.log"
  timeout --signal=TERM --kill-after=5s 240s "$task_wrapper" "$task_binary" -Srepro -A:experimental-span-codec -Sdeps "$task_writer_deps" \
    research/string-fragments-20261020/durable-arm.clj "$task_store" "$task_arm" > "$task_report.writer.log" 2>&1
  cp "$task_store/writer.edn" "$task_report.writer.edn"
  cd "$task_reader"
  timeout --signal=TERM --kill-after=5s 150s "$task_wrapper" env CHDB_BENCH_METADATA_READER=stock \
    "$task_binary" -Srepro -Sdeps "$task_reader_deps" \
    "$task_support/research/nested-fragments-20261019/independent-reader.clj" "$task_store" > "$task_report.reader.log" 2>&1
  cp "$task_store/reader.edn" "$task_report.reader.edn"
  cmp "$task_store/writer.edn" "$task_report.writer.edn"
  cmp "$task_store/reader.edn" "$task_report.reader.edn"
  cd "$task_candidate"
  bb "$task_support/research/library-nested-text-20261019/validate-rich-sdk-scaling-20261019.clj" "$task_report" 1 > "$task_report.validation.edn"
  sha256sum -c "$task_out/sources.sha256" > "$task_report.source-after.log"
  du -sk "$task_store" > "$task_report.retired-kib.txt"
  case "$task_store" in /tmp/string-fragments-durable.????????) ;; *) exit 91;; esac
  test ! -L "$task_store"
  test "$(realpath "$task_store")" = "$task_store"
  # Exact owned synthetic store only, after terminal writer/reader, full row
  # validator, copied/cmp receipts and unchanged sources. Failures retain store.
  rm -r -- "$task_store"
  test ! -e "$task_store"
  printf 'verified-and-retired index=%s arm=%s\n' "$task_index" "$task_arm"
  task_index=$((task_index + 1))
done
printf '%s\n' 'terminal0: string-fragment ABBA independently recovered and owned stores retired'
