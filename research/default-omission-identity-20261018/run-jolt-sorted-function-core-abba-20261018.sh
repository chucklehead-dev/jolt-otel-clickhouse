#!/usr/bin/env bash
set -euo pipefail
task_evidence=/home/chuck/ai-src/evidence
task_exporter=/home/chuck/ai-src/worktrees/exporter-stock-fold-json-consumer-20261018
task_json=/home/chuck/ai-src/worktrees/data-json-optional-stock-sorted-fold-20261018
task_core=/home/chuck/ai-src/worktrees/jolt-0823-sorted-function-equality-20261018
test "$(git -C "$task_core" rev-parse HEAD)" = 388a3ad613f35a519aeefb0b895ff82b17088616
test "$(git -C "$task_exporter" rev-parse HEAD)" = 4d48871cf2ac7a2ad0bf6517042c624df376a9bf
test "$(git -C "$task_json" rev-parse HEAD)" = 96f3e47dba5970728866dacb5ac41a25135a2df5
for task_path in "$task_core" "$task_exporter" "$task_json"; do git -C "$task_path" diff --quiet HEAD; done
task_driver=$task_evidence/jolt-sorted-function-core-screen-20261018.clj
task_sha=$(sha256sum "$task_driver" | cut -d ' ' -f1)
task_index=0
cd "$task_exporter"
for task_arm in baseline candidate candidate baseline; do
  task_output=$task_evidence/jolt-sorted-function-core-abba-20261018-$task_index-$task_arm.edn
  test ! -e "$task_output"
  export ROW_EMISSION_JSON_ARM=$task_arm
  timeout --signal=TERM --kill-after=5s 180s "$task_evidence/jolt-hosted-stock-fold-7098-20261018.sh" -Srepro \
    -Sdeps "{:paths [\"src\" \"resources\" \"test\" \"bench\"] :deps {org.clojure/data.json {:local/root \"$task_json\"}}}" \
    "$task_driver" "$task_output" > "$task_output.log" 2>&1
  test "$(sha256sum "$task_driver" | cut -d ' ' -f1)" = "$task_sha"
  printf 'index=%s arm=%s terminal0 output=%s\n' "$task_index" "$task_arm" "$task_output"
  task_index=$((task_index + 1))
done
test "$task_index" = 4
