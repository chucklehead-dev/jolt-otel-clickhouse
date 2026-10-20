#!/usr/bin/env bash
set -euo pipefail
task_out=${1:?pass a new absolute output directory}
test ! -e "$task_out"
task_bin=/home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt
task_wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
task_candidate=/home/chuck/ai-src/worktrees/otel-scalar-policy-anchors-20261020
task_baseline=/home/chuck/.jolt/gitlibs/https___github.com_casselc_otel.git/3366a3322c3ea4024057f6278e50c999864171e8
task_fixture=/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019
test "$(git -C "$task_fixture" rev-parse HEAD)" = 0af1008185cf84577298d3a0a0b2db87b97bddd5
git -C "$task_fixture" diff --quiet HEAD
printf '%s  %s\n' 7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94 "$task_bin" | sha256sum -c -
export JOLT_AOT_CACHE=0 JOLT_NO_USER_DEPS=1
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
mkdir -p "$task_out"
sha256sum research/projection-phases-20261020/screen.clj src/otel/exporter/chdb.clj src/otel/exporter/chdb/attribute_projection.clj \
  "$task_fixture/bench/otel/exporter/chdb_ordinary_transport_benchmark.clj" "$task_fixture/bench/otel/exporter/chdb_benchmark.clj" \
  "$task_baseline/resources/otel/scalar_string_map.ss" "$task_baseline/resources/otel/sorted_scalar_string_map.ss" \
  "$task_candidate/resources/otel/scalar_string_map.ss" "$task_candidate/resources/otel/sorted_scalar_string_map.ss" > "$task_out/sources.sha256"
for task_arm in baseline candidate; do
  task_sdk='{:git/url "https://github.com/casselc/otel.git" :git/sha "3366a3322c3ea4024057f6278e50c999864171e8" :exclusions [jolt-lang/jolt-crypto]}'
  task_marker=3366a3322c3ea4024057f6278e50c999864171e8
  if [[ "$task_arm" == candidate ]]; then
    task_sdk='{:local/root "'"$task_candidate"'" :exclusions [jolt-lang/jolt-crypto]}'
    task_marker=$task_candidate
  fi
  task_deps='{:paths ["src" "test" "'"$task_fixture"'/test" "'"$task_fixture"'/bench"] :deps {io.github.casselc/otel '"$task_sdk"' io.github.chucklehead-dev/jolt-codec {:git/url "https://github.com/chucklehead-dev/jolt-otel-clickhouse.git" :git/sha "7ab2bedcb2e1845efdf6ed5c55ae9d2164338a8d" :deps/root "incubator/codec"}}}'
  bb -e '(let [d (clojure.edn/read-string (first *command-line-args*))] (assert (= 2 (count (:deps d)))) (assert (= 4 (count (:paths d)))))' "$task_deps"
  timeout --signal=TERM --kill-after=5s 240s "$task_wrapper" "$task_bin" -Srepro -Sdeps "$task_deps" \
    research/projection-phases-20261020/screen.clj "$task_out/$task_arm.edn" "$task_marker" > "$task_out/$task_arm.log" 2>&1
  sha256sum -c "$task_out/sources.sha256" > "$task_out/$task_arm.source-after.log"
done
printf '%s\n' 'terminal0: source-checked scalar policy-anchor component pair complete'
