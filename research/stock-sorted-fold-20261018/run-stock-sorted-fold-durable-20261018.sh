#!/usr/bin/env bash
set -euo pipefail
task_evidence=/home/chuck/ai-src/evidence
task_exporter=/home/chuck/ai-src/worktrees/exporter-sdk-sorted-scalar-consumer-20261018
task_reader=/home/chuck/ai-src/worktrees/exporter-live-empty-link-writers-20261018
task_fixture=/home/chuck/ai-src/worktrees/chdb-current-typed-storage-0821-20261014
task_json=/home/chuck/ai-src/worktrees/data-json-sorted-map-byte-seq-20261018
task_sdk=/home/chuck/ai-src/worktrees/otel-sdk-sorted-scalar-map-20261018
task_old_sdk=/home/chuck/ai-src/worktrees/otel-batch-threshold-wakeup-20261017
task_old_json=/home/chuck/ai-src/worktrees/data-json-qualified-8d557cc-20261017
for task_entry in "$task_exporter:a1bedfcc50d84dd59597fe12dec144231b282920" "$task_reader:473edbb06fa11070968de35f8b953f62cb4b6cd3" "$task_fixture:3fd96c4691c5db49ed4d3ef55622aaa6b9074127" "$task_json:a18ae06c787e6e03709bd78f1b92911130a613f1" "$task_sdk:7878a2eb4340519d6843d13490a5a7c99ebade8a" "$task_old_sdk:37c684a2c370fc82fde82331635973bce87a52b3" "$task_old_json:8d557cc18eb9526ed29ae5613089c3793d5973fa"; do
  task_path=${task_entry%:*}
  test "$(git -C "$task_path" rev-parse HEAD)" = "${task_entry##*:}"
  git -C "$task_path" diff --quiet HEAD
done
task_mode=${SDK_SORTED_FOLD_MODE:-bounded}
case "$task_mode" in
  bounded) task_report=$task_evidence/stock-sorted-fold-durable-qualified-20261018 ;;
  runtime) task_report=$task_evidence/stock-sorted-fold-durable-runtime-20261018 ;;
  *) exit 2 ;;
esac
test ! -e "$task_report.writer.log"
task_root=$(mktemp -d "/tmp/stock-sorted-fold-durable-$task_mode-20261018.XXXXXXXX")
printf 'owned_store=%s\n' "$task_root"
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
test "$(sha256sum "$JOLT_CHDB_LIB" | cut -d ' ' -f1)" = a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557
export SDK_STORAGE_ARM=local SDK_STREAM_ARM=stream SDK_EXPORT_CALLERS=1
export CHDB_BENCH_OWNED_OUTPUT=true CHDB_BENCH_SAMPLE_COUNTERS=true
task_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"] :deps {org.clojure/data.json {:local/root \"$task_json\"} io.github.casselc/otel {:local/root \"$task_sdk\"}}}"
cd "$task_exporter"
timeout --signal=TERM --kill-after=5s 180s "$task_evidence/jolt-measured-1ae-20261015.sh" -Srepro -Sdeps "$task_deps" \
  "$task_evidence/stock-sorted-fold-durable-paired-20261018.clj" "$task_root" 1 saturated > "$task_report.writer.log" 2>&1
cp "$task_root/writer.edn" "$task_report.writer.edn"
cp "$task_root/paired.edn" "$task_report.paired.edn"
cmp "$task_root/writer.edn" "$task_report.writer.edn"
cmp "$task_root/paired.edn" "$task_report.paired.edn"
cd "$task_reader"
task_deps="${task_deps//\/home\/chuck\/ai-src\/worktrees\/otel-sdk-sorted-scalar-map-20261018/$task_old_sdk}"
task_deps="${task_deps//\/home\/chuck\/ai-src\/worktrees\/data-json-sorted-map-byte-seq-20261018/$task_old_json}"
export CHDB_BENCH_METADATA_READER=stock
timeout --signal=TERM --kill-after=5s 120s "$task_evidence/jolt-measured-1ae-20261015.sh" -Srepro -Sdeps "$task_deps" \
  "$task_evidence/nested-sorted-sdk-durable-reader-20261018.clj" "$task_root" > "$task_report.reader.log" 2>&1
cp "$task_root/reader.edn" "$task_report.reader.edn"
cmp "$task_root/reader.edn" "$task_report.reader.edn"
printf 'writer_reader_terminal0 owned_store=%s\n' "$task_root"
du -sh "$task_root"
# Main must validate scalar receipts and full recovery before exact-path removal.
