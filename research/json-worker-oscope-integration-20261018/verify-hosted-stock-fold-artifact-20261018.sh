#!/usr/bin/env bash
set -euo pipefail
task_root=/home/chuck/ai-src/evidence/jolt-runtime-stock-fold-7098-artifact-20261018.DQfwReHc
cd "$task_root"
sha256sum -c SHA256SUMS
for task_line in \
  schema=1 repository=casselc/jolt \
  compiler_source=7098a8d6fbd1665215e3a24d5ac4bf9a53859101 \
  compiler_tree=571f8df965464e37298a5dce83e16d92127f4c76 \
  workflow_source=54a3719204ab19f96a297188508968b1bb8182d3 \
  run_id=39151375518 run_attempt=1 chez_version=10.4.1 require_buildlib=1 \
  gate=pass ranged_append_ascii=98 runtime_kind=plain stock_sorted_map_fold=pass \
  binary_sha256=75c47a6d30cc8f00e53230123383c078222bdfeda790c2660dbecfd399e08798; do
  test "$(rg -c -x -F "$task_line" build-manifest.txt)" = 1
done
test "$(rg -c -x -F 'binary_version=jolt v0.8.21-60-g7098a8d6' build-manifest.txt)" = 1
test "$(/home/chuck/ai-src/evidence/jolt-hosted-stock-fold-7098-20261018.sh --version)" = 'jolt v0.8.21-60-g7098a8d6'
printf 'exact_source_tree_workflow_run_version_binary_and_capabilities_verified\n'
