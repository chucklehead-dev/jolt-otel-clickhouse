#!/usr/bin/env bash
set -euo pipefail
task_evidence=/home/chuck/ai-src/evidence
task_exporter=/home/chuck/ai-src/worktrees/exporter-sdk-scalar-map-20261018
task_reader=/home/chuck/ai-src/worktrees/exporter-live-empty-link-writers-20261018
task_fixture=/home/chuck/ai-src/worktrees/chdb-current-typed-storage-0821-20261014
task_json=/home/chuck/ai-src/worktrees/data-json-qualified-8d557cc-20261017
task_sdk=/home/chuck/ai-src/worktrees/otel-sdk-owned-scalar-map-20261018
task_candidate=/home/chuck/ai-src/worktrees/data-json-sorted-map-byte-seq-20261018
for task_entry in "$task_exporter:5a7617a4b3c806f21e15b902993156fc2f751a3c" "$task_reader:473edbb06fa11070968de35f8b953f62cb4b6cd3" "$task_fixture:3fd96c4691c5db49ed4d3ef55622aaa6b9074127" "$task_json:8d557cc18eb9526ed29ae5613089c3793d5973fa" "$task_sdk:697f58bd5b4925f72339694bedbace97bdc869bb"; do
  task_path=${task_entry%:*}
  test "$(git -C "$task_path" rev-parse HEAD)" = "${task_entry##*:}"
  git -C "$task_path" diff --quiet HEAD
done
test "$(git -C "$task_candidate" rev-parse --short=7 HEAD)" = a18ae06
git -C "$task_candidate" diff --quiet HEAD
task_report=$task_evidence/nested-sorted-sdk-durable-20261018
test ! -e "$task_report.writer.log"
task_root=$(mktemp -d /tmp/nested-sorted-sdk-20261018.XXXXXXXX)
printf 'owned_store=%s\n' "$task_root"
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
test "$(sha256sum "$JOLT_CHDB_LIB" | cut -d ' ' -f1)" = a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557
export SDK_STORAGE_ARM=local SDK_STREAM_ARM=stream SDK_EXPORT_CALLERS=1
export CHDB_BENCH_OWNED_OUTPUT=true CHDB_BENCH_SAMPLE_COUNTERS=true
task_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\" \"$task_fixture/bench\" \"$task_fixture/test\"] :deps {org.clojure/data.json {:local/root \"$task_json\"} io.github.casselc/otel {:local/root \"$task_sdk\"}}}"
cd "$task_exporter"
timeout --signal=TERM --kill-after=5s 180s "$task_evidence/jolt-measured-1ae-20261015.sh" -Srepro -Sdeps "$task_deps" \
  "$task_evidence/nested-sorted-sdk-durable-paired-20261018.clj" "$task_root" 1 saturated > "$task_report.writer.log" 2>&1
cp "$task_root/writer.edn" "$task_report.writer.edn"
cp "$task_root/paired.edn" "$task_report.paired.edn"
cmp "$task_root/writer.edn" "$task_report.writer.edn"
cmp "$task_root/paired.edn" "$task_report.paired.edn"
# Separate process, unchanged accepted exporter + old SDK + stock JSON reader.
cd "$task_reader"
task_deps="${task_deps//\/home\/chuck\/ai-src\/worktrees\/otel-sdk-owned-scalar-map-20261018/\/home\/chuck\/ai-src\/worktrees\/otel-batch-threshold-wakeup-20261017}"
export CHDB_BENCH_METADATA_READER=stock
timeout --signal=TERM --kill-after=5s 120s "$task_evidence/jolt-measured-1ae-20261015.sh" -Srepro -Sdeps "$task_deps" \
  "$task_evidence/nested-sorted-sdk-durable-reader-20261018.clj" "$task_root" > "$task_report.reader.log" 2>&1
cp "$task_root/reader.edn" "$task_report.reader.edn"
cmp "$task_root/reader.edn" "$task_report.reader.edn"
printf 'writer_reader_terminal0 owned_store=%s\n' "$task_root"
du -sh "$task_root"
# Keep until main validates paired receipts and independent recovery; then
# remove only this exact resolved directory. No broad automatic cleanup.
