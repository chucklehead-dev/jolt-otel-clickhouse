#!/usr/bin/env bash
set -euo pipefail
task_here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
task_exporter=/home/chuck/ai-src/worktrees/exporter-explicit-codec-row-adapter-20261019
task_wrapper=/home/chuck/ai-src/evidence/jolt-hosted-stock-fold-7098-20261018.sh
task_output=${1:-$task_here/rich-projection}
test ! -e "$task_output"
mkdir -p "$task_output"
git -C "$task_exporter" merge-base --is-ancestor 2c54b98d35464c8fc7c737b0c92a0042272b589a HEAD
git -C "$task_exporter" rev-parse HEAD > "$task_output/exporter-head.txt"
git -C "$task_exporter" diff 2c54b98d35464c8fc7c737b0c92a0042272b589a -- src/otel/exporter/chdb.clj > "$task_output/exporter-adapter.patch"
test -s "$task_output/exporter-adapter.patch"
cp "$task_here/traversal.ss" "$task_here/json.ss" "$task_here/rich-projection-component.clj" "$task_output/"
sha256sum "$task_here/traversal.ss" "$task_here/json.ss" "$task_here/rich-projection-component.clj" \
  "$task_exporter/src/otel/exporter/chdb.clj" \
  /home/chuck/ai-src/evidence/nested-sorted-sdk-fixture-20261018.clj \
  /home/chuck/ai-src/evidence/trace-affinity-helpers-20261018.clj > "$task_output/sources.sha256"
cd "$task_exporter"
timeout --signal=TERM --kill-after=5s 150s "$task_wrapper" -Srepro \
  -Sdeps '{:paths ["src" "resources" "test" "bench"]}' -A:native-json-byte-batch \
  "$task_here/rich-projection-component.clj" "$task_here" "$task_output/component.edn" > "$task_output/component.log" 2>&1
sha256sum -c "$task_output/sources.sha256" > "$task_output/source-stability.log"
printf 'terminal0: rich full-projection comparison, all output digests, unchanged source\n'
