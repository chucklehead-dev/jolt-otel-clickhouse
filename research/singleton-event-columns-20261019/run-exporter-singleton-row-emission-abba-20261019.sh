#!/usr/bin/env bash
set -euo pipefail
task_evidence=/home/chuck/ai-src/evidence
task_baseline=/home/chuck/ai-src/worktrees/exporter-default-omission-json-20261018
task_candidate=/home/chuck/ai-src/worktrees/exporter-singleton-event-columns-20261019
test "$(git -C "$task_baseline" rev-parse HEAD)" = 4a42716286177fd0c033d69deb708b29dd162933
test "$(git -C "$task_candidate" rev-parse HEAD)" = 2c54b98d35464c8fc7c737b0c92a0042272b589a
for task_path in "$task_baseline" "$task_candidate"; do git -C "$task_path" diff --quiet HEAD; done
task_driver=$task_evidence/exporter-singleton-row-emission-20261019.clj
task_sha=$(sha256sum "$task_driver" | cut -d ' ' -f1)
task_index=0
for task_arm in baseline candidate candidate baseline; do
  task_output=$task_evidence/exporter-singleton-row-emission-abba-20261019-$task_index-$task_arm.edn
  test ! -e "$task_output"
  task_exporter=$task_baseline
  if test "$task_arm" = candidate; then task_exporter=$task_candidate; fi
  export ROW_EMISSION_JSON_ARM=$task_arm
  cd "$task_exporter"
  timeout --signal=TERM --kill-after=5s 180s "$task_evidence/jolt-hosted-stock-fold-7098-20261018.sh" -Srepro \
    -Sdeps '{:paths ["src" "resources" "test" "bench"]}' -A:native-json-byte-batch \
    "$task_driver" "$task_output" > "$task_output.log" 2>&1
  test "$(sha256sum "$task_driver" | cut -d ' ' -f1)" = "$task_sha"
  printf 'index=%s arm=%s terminal0 output=%s\n' "$task_index" "$task_arm" "$task_output"
  task_index=$((task_index + 1))
done
test "$task_index" = 4
