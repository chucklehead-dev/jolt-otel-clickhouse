#!/usr/bin/env bash
set -euo pipefail
task_evidence=/home/chuck/ai-src/evidence
task_json=/home/chuck/ai-src/worktrees/data-json-optional-stock-sorted-fold-20261018
task_exporter=/home/chuck/ai-src/worktrees/exporter-stock-fold-json-consumer-20261018
task_jolt=$task_evidence/jolt-hosted-stock-fold-7098-20261018.sh
test "$(git -C "$task_json" rev-parse HEAD)" = 96f3e47dba5970728866dacb5ac41a25135a2df5
test "$(git -C "$task_exporter" rev-parse HEAD)" = 4d48871cf2ac7a2ad0bf6517042c624df376a9bf
git -C "$task_json" diff --quiet HEAD
git -C "$task_exporter" diff --quiet HEAD
test "$("$task_jolt" --version)" = 'jolt v0.8.21-60-g7098a8d6'
cd "$task_json"
"$task_jolt" -Srepro -Sdeps '{:paths ["src/main/clojure" "src/test/clojure"]}' \
  "$task_evidence/qualify-stock-fold-real-binary-20261018.clj" > "$task_evidence/hosted-stock-fold-json-suite-20261018.log" 2>&1
cd "$task_exporter"
export JOLT_TEST_CHILD_EXECUTABLE=$task_jolt
"$task_jolt" -Srepro -Sdeps '{:paths ["src" "resources" "test" "bench"]}' -M:native-json-byte-batch \
  "$task_evidence/qualify-exporter-stock-fold-pin-20261018.clj" present > "$task_evidence/hosted-stock-fold-exporter-suite-20261018.log" 2>&1
printf 'hosted_json_and_exporter_terminal0\n'
