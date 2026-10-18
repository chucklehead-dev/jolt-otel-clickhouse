#!/usr/bin/env bash
set -euo pipefail
task_evidence=/home/chuck/ai-src/evidence
task_runtime=/home/chuck/ai-src/worktrees/jolt-0823-stock-sorted-fold-20261018
task_exporter=/home/chuck/ai-src/worktrees/exporter-stock-fold-json-consumer-20261018
task_json=/home/chuck/ai-src/worktrees/data-json-optional-stock-sorted-fold-20261018
test "$(git -C "$task_runtime" rev-parse HEAD)" = 18dce94a633b798e3e5fafce964e8e42b398a4bb
test "$(git -C "$task_exporter" rev-parse HEAD)" = 4d48871cf2ac7a2ad0bf6517042c624df376a9bf
test "$(git -C "$task_json" rev-parse HEAD)" = 96f3e47dba5970728866dacb5ac41a25135a2df5
for task_path in "$task_runtime" "$task_exporter" "$task_json"; do
  git -C "$task_path" diff --quiet HEAD
done
task_driver=$task_evidence/stock-0823-row-preparation-linkage-screen-20261018.clj
task_driver_sha=$(sha256sum "$task_driver" | cut -d ' ' -f1)
test "$task_driver_sha" = b45a829ba0deadae3e025453dc6effd7dd9de0bea84e07bf5bc5a875e3f83fd4
task_index=0
cd "$task_exporter"
for task_arm in open direct direct open; do
  task_output=$task_evidence/stock-0823-row-preparation-linkage-$task_index-$task_arm-20261018.edn
  test ! -e "$task_output"
  timeout --signal=TERM --kill-after=5s 180s \
    /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
    env JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0 JOLT_NO_DEVCACHE=1 \
    "$task_runtime/bin/jolt" -Srepro \
    -Sdeps "{:paths [\"src\" \"resources\" \"test\" \"bench\"] :deps {org.clojure/data.json {:local/root \"$task_json\"}}}" \
    "$task_driver" "$task_arm" "$task_output" > "$task_output.log" 2>&1
  test "$(sha256sum "$task_driver" | cut -d ' ' -f1)" = "$task_driver_sha"
  printf 'index=%s arm=%s terminal0 output=%s\n' "$task_index" "$task_arm" "$task_output"
  task_index=$((task_index + 1))
done
test "$task_index" = 4
