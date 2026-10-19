#!/usr/bin/env bash
set -euo pipefail
task_here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
task_evidence=/home/chuck/ai-src/evidence
task_exporter=/home/chuck/ai-src/worktrees/exporter-explicit-codec-row-adapter-20261019
task_reader=/home/chuck/ai-src/worktrees/exporter-live-empty-link-writers-20261018
task_fixture=/home/chuck/ai-src/worktrees/chdb-current-typed-storage-0821-20261014
task_old_sdk=/home/chuck/ai-src/worktrees/otel-batch-threshold-wakeup-20261017
task_old_json=/home/chuck/ai-src/worktrees/data-json-qualified-8d557cc-20261017
task_output=$task_here/durable-abba
test ! -e "$task_output"
mkdir -p "$task_output/source"
test "$(git -C "$task_exporter" rev-parse HEAD)" = 3731f87d8811e89ac42d68098c67a26087776a6c
for task_entry in \
 "$task_reader:473edbb06fa11070968de35f8b953f62cb4b6cd3" \
 "$task_fixture:3fd96c4691c5db49ed4d3ef55622aaa6b9074127" \
 "$task_old_sdk:37c684a2c370fc82fde82331635973bce87a52b3" \
 "$task_old_json:8d557cc18eb9526ed29ae5613089c3793d5973fa"; do
  task_path=${task_entry%:*}
  test "$(git -C "$task_path" rev-parse HEAD)" = "${task_entry##*:}"
  git -C "$task_path" diff --quiet HEAD
done
cp "$task_exporter/src/otel/exporter/chdb.clj" "$task_exporter/deps.edn" "$task_output/source/"
cp -r "$task_exporter/incubator/codec" "$task_output/source/codec"
sha256sum "$task_exporter/src/otel/exporter/chdb.clj" "$task_exporter/deps.edn" \
 "$task_exporter/incubator/codec/src/jolt/codec/experimental.clj" \
 "$task_exporter/incubator/codec/src/jolt/codec/traversal.ss" \
 "$task_exporter/incubator/codec/src/jolt/codec/json.ss" \
 "$task_here/durable-writer.clj" "$task_here/validate-durable.clj" \
 "$task_evidence/rich-sdk-scaling-screen-20261019.clj" \
 "$task_evidence/nested-sorted-sdk-fixture-20261018.clj" > "$task_output/sources.sha256"
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
test "$(sha256sum "$JOLT_CHDB_LIB" | cut -d ' ' -f1)" = a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557
export SDK_STORAGE_ARM=local SDK_STREAM_ARM=stream SDK_EXPORT_CALLERS=1
export CHDB_BENCH_OWNED_OUTPUT=true CHDB_BENCH_SAMPLE_COUNTERS=true
export BENCH_CLK_TCK
BENCH_CLK_TCK=$(getconf CLK_TCK)
task_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"]}"
task_reader_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"] :deps {org.clojure/data.json {:local/root \"$task_old_json\"} io.github.casselc/otel {:local/root \"$task_old_sdk\"}}}"
task_index=0
for task_arm in off on on off; do
  task_report=$task_output/$task_index-$task_arm
  task_root=$(mktemp -d /tmp/explicit-codec-durable-20261019.XXXXXXXX)
  printf 'index=%s arm=%s owned_store=%s\n' "$task_index" "$task_arm" "$task_root"
  unset CHDB_BENCH_METADATA_READER
  cd "$task_exporter"
  /usr/bin/time -v -o "$task_report.writer.os-resource.txt" timeout --signal=TERM --kill-after=5s 240s \
    "$task_evidence/jolt-hosted-stock-fold-7098-20261018.sh" -Srepro -Sdeps "$task_deps" \
    -A:native-json-byte-batch:experimental-span-codec "$task_here/durable-writer.clj" "$task_root" "$task_arm" \
    > "$task_report.writer.log" 2>&1
  cp "$task_root/writer.edn" "$task_report.writer.edn"
  cmp "$task_root/writer.edn" "$task_report.writer.edn"
  cd "$task_reader"
  export CHDB_BENCH_METADATA_READER=stock
  timeout --signal=TERM --kill-after=5s 120s "$task_evidence/jolt-measured-1ae-20261015.sh" \
    -Srepro -Sdeps "$task_reader_deps" "$task_evidence/nested-sorted-sdk-durable-reader-20261018.clj" \
    "$task_root" > "$task_report.reader.log" 2>&1
  cp "$task_root/reader.edn" "$task_report.reader.edn"
  cmp "$task_root/reader.edn" "$task_report.reader.edn"
  bb "$task_here/validate-durable.clj" "$task_report" "$task_arm" > "$task_report.validation.edn"
  du -sk "$task_root" > "$task_report.retired-store-kib.txt"
  printf 'index=%s writer_reader_validator_terminal0 owned_store=%s\n' "$task_index" "$task_root"
  case "$task_root" in /tmp/explicit-codec-durable-20261019.????????) ;; *) exit 91;; esac
  test -d "$task_root" && test ! -L "$task_root"
  test "$(realpath "$task_root")" = "$task_root"
  rm -rf -- "$task_root"
  test ! -e "$task_root"
  printf 'index=%s retired_validated_synthetic_store=%s\n' "$task_index" "$task_root"
  sha256sum -c "$task_output/sources.sha256" > "$task_report.source-stability.log"
  task_index=$((task_index + 1))
done
test "$task_index" = 4
printf 'terminal0: four writer/independent-reader/validator arms, all stores retired\n'
