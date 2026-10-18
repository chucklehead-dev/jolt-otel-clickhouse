#!/usr/bin/env bash
set -euo pipefail
task_evidence=/home/chuck/ai-src/evidence
task_exporter=/home/chuck/ai-src/worktrees/exporter-stock-fold-json-consumer-20261018
task_baseline=/home/chuck/ai-src/worktrees/data-json-optional-stock-sorted-fold-20261018
task_candidate=/home/chuck/ai-src/worktrees/data-json-ascii-byte-scratch-string-20261018
test "$(git -C "$task_exporter" rev-parse HEAD)" = 4d48871cf2ac7a2ad0bf6517042c624df376a9bf
test "$(git -C "$task_baseline" rev-parse HEAD)" = 96f3e47dba5970728866dacb5ac41a25135a2df5
test "$(git -C "$task_candidate" rev-parse HEAD)" = a3799fb37a3d234f6980e26db621e41c9162a944
for task_path in "$task_exporter" "$task_baseline" "$task_candidate"; do git -C "$task_path" diff --quiet HEAD; done
task_index=0
for task_arm in baseline candidate candidate baseline; do
  task_output=$task_evidence/json-ascii-prefix-abba-20261018-$task_index-$task_arm.edn
  test ! -e "$task_output"
  task_json=$task_baseline
  if test "$task_arm" = candidate; then task_json=$task_candidate; fi
  task_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\"] :deps {org.clojure/data.json {:local/root \"$task_json\"}}}"
  export ROW_EMISSION_JSON_ARM=$task_arm
  cd "$task_exporter"
  timeout --signal=TERM --kill-after=5s 180s "$task_evidence/jolt-hosted-stock-fold-7098-20261018.sh" -Srepro -Sdeps "$task_deps" \
    "$task_evidence/json-rich-row-vs-emission-output-20261018.clj" "$task_output" > "$task_output.log" 2>&1
  printf 'index=%s arm=%s terminal0 output=%s\n' "$task_index" "$task_arm" "$task_output"
  task_index=$((task_index + 1))
done
test "$task_index" = 4
