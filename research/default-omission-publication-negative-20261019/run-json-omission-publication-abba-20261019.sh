#!/usr/bin/env bash
set -euo pipefail
task_evidence=/home/chuck/ai-src/evidence
task_exporter=/home/chuck/ai-src/worktrees/exporter-stock-fold-json-consumer-20261018
task_baseline=/home/chuck/ai-src/worktrees/data-json-default-omission-identity-20261018
task_candidate=/home/chuck/ai-src/worktrees/data-json-default-omission-publication-20261019
test "$(git -C "$task_exporter" rev-parse HEAD)" = 4d48871cf2ac7a2ad0bf6517042c624df376a9bf
test "$(git -C "$task_baseline" rev-parse HEAD)" = 2172061060a102ccab779f5adc5d77f8b83a81f6
test "$(git -C "$task_candidate" rev-parse HEAD)" = 654c1f9dc23cca624add78ca71464d35fb7aed26
for task_path in "$task_exporter" "$task_baseline" "$task_candidate"; do git -C "$task_path" diff --quiet HEAD; done
task_driver=$task_evidence/json-rich-row-vs-emission-output-20261018.clj
task_sha=$(sha256sum "$task_driver" | cut -d ' ' -f1)
test "$task_sha" = 07f6f780d74fcd93d9444f6f922b1b31dffe7c3302e211f94ca084a5253a7f04
task_index=0
for task_arm in baseline candidate candidate baseline; do
  task_output=$task_evidence/json-omission-publication-abba-20261019-$task_index-$task_arm.edn
  test ! -e "$task_output"
  task_json=$task_baseline
  if test "$task_arm" = candidate; then task_json=$task_candidate; fi
  task_deps="{:paths [\"src\" \"resources\" \"test\" \"bench\"] :deps {org.clojure/data.json {:local/root \"$task_json\"}}}"
  export ROW_EMISSION_JSON_ARM=$task_arm
  cd "$task_exporter"
  timeout --signal=TERM --kill-after=5s 180s "$task_evidence/jolt-hosted-stock-fold-7098-20261018.sh" -Srepro -Sdeps "$task_deps" \
    "$task_driver" "$task_output" > "$task_output.log" 2>&1
  test "$(sha256sum "$task_driver" | cut -d ' ' -f1)" = "$task_sha"
  printf 'index=%s arm=%s terminal0 output=%s\n' "$task_index" "$task_arm" "$task_output"
  task_index=$((task_index + 1))
done
test "$task_index" = 4
