#!/usr/bin/env bash
set -euo pipefail
task_evidence=/home/chuck/ai-src/evidence
task_exporter=/home/chuck/ai-src/worktrees/exporter-singleton-event-columns-20261019
task_reader=/home/chuck/ai-src/worktrees/exporter-live-empty-link-writers-20261018
task_fixture=/home/chuck/ai-src/worktrees/chdb-current-typed-storage-0821-20261014
task_old_sdk=/home/chuck/ai-src/worktrees/otel-batch-threshold-wakeup-20261017
task_old_json=/home/chuck/ai-src/worktrees/data-json-qualified-8d557cc-20261017
for task_entry in "$task_exporter:2c54b98d35464c8fc7c737b0c92a0042272b589a" "$task_reader:473edbb06fa11070968de35f8b953f62cb4b6cd3" "$task_fixture:3fd96c4691c5db49ed4d3ef55622aaa6b9074127" "$task_old_sdk:37c684a2c370fc82fde82331635973bce87a52b3" "$task_old_json:8d557cc18eb9526ed29ae5613089c3793d5973fa"; do
  task_path=${task_entry%:*}
  test "$(git -C "$task_path" rev-parse HEAD)" = "${task_entry##*:}"
  git -C "$task_path" diff --quiet HEAD
done
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
test "$(sha256sum "$JOLT_CHDB_LIB" | cut -d ' ' -f1)" = a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557
export SDK_STORAGE_ARM=local SDK_STREAM_ARM=stream SDK_EXPORT_CALLERS=1
export CHDB_BENCH_OWNED_OUTPUT=true CHDB_BENCH_SAMPLE_COUNTERS=true
task_reader_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"] :deps {org.clojure/data.json {:local/root \"$task_old_json\"} io.github.casselc/otel {:local/root \"$task_old_sdk\"}}}"
task_driver=$task_evidence/rich-sdk-lanes-writer-20261019.clj
task_driver_sha=$(sha256sum "$task_driver" | cut -d ' ' -f1)
task_inner_driver=$task_evidence/sdk-trace-affinity-screen-20261018.clj
task_inner_sha=$(sha256sum "$task_inner_driver" | cut -d ' ' -f1)
task_index=0
for task_lanes in 1 2 2 1; do
  task_arm=lanes-$task_lanes
  task_report=$task_evidence/rich-sdk-lanes-abba-20261019-$task_index-$task_arm
  test ! -e "$task_report.writer.log"
  task_root=$(mktemp -d /tmp/rich-sdk-lanes-abba-20261019.XXXXXXXX)
  printf 'index=%s arm=%s owned_store=%s\n' "$task_index" "$task_arm" "$task_root"
  task_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"]}"
  unset CHDB_BENCH_METADATA_READER
  cd "$task_exporter"
  timeout --signal=TERM --kill-after=5s 240s "$task_evidence/jolt-hosted-stock-fold-7098-20261018.sh" -Srepro -Sdeps "$task_deps" -A:native-json-byte-batch \
    "$task_driver" "$task_root" "$task_lanes" saturated > "$task_report.writer.log" 2>&1
  test "$(sha256sum "$task_driver" | cut -d ' ' -f1)" = "$task_driver_sha"
  test "$(sha256sum "$task_inner_driver" | cut -d ' ' -f1)" = "$task_inner_sha"
  cp "$task_root/writer.edn" "$task_report.writer.edn"
  cmp "$task_root/writer.edn" "$task_report.writer.edn"
  cd "$task_reader"
  export CHDB_BENCH_METADATA_READER=stock
  timeout --signal=TERM --kill-after=5s 120s "$task_evidence/jolt-measured-1ae-20261015.sh" -Srepro -Sdeps "$task_reader_deps" \
    "$task_evidence/nested-sorted-sdk-durable-reader-20261018.clj" "$task_root" > "$task_report.reader.log" 2>&1
  cp "$task_root/reader.edn" "$task_report.reader.edn"
  cmp "$task_root/reader.edn" "$task_report.reader.edn"
  bb "$task_evidence/validate-rich-sdk-lanes-20261019.clj" "$task_report" "$task_lanes" > "$task_report.validation.edn"
  printf 'index=%s arm=%s writer_reader_validator_terminal0 owned_store=%s\n' "$task_index" "$task_arm" "$task_root"
  # Each fixture is synthetic and fully reproduced by frozen source. Retire it
  # only between runs, after terminal children + validated byte-identical copies.
  case "$task_root" in /tmp/rich-sdk-lanes-abba-20261019.????????) ;; *) exit 91;; esac
  test -d "$task_root" && test ! -L "$task_root"
  test "$(realpath "$task_root")" = "$task_root"
  rm -rf -- "$task_root"
  test ! -e "$task_root"
  printf 'index=%s retired_validated_synthetic_store=%s\n' "$task_index" "$task_root"
  task_index=$((task_index + 1))
done
test "$task_index" = 4
printf 'all_four_arms_terminal0_and_owned_stores_retired\n'
