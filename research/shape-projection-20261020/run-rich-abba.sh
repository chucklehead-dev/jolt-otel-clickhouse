#!/usr/bin/env bash
set -euo pipefail
task_out=${1:?pass a NEW absolute output directory}
[[ "$task_out" == /* ]]
test ! -e "$task_out"
task_fixture=/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019
task_sdk=/home/chuck/.jolt/gitlibs/https___github.com_casselc_otel.git/3366a3322c3ea4024057f6278e50c999864171e8
task_bin=/home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt
test "$(git -C "$task_fixture" rev-parse HEAD)" = 0af1008185cf84577298d3a0a0b2db87b97bddd5
git -C "$task_fixture" diff --quiet HEAD
printf '%s  %s\n' 7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94 "$task_bin" | sha256sum -c -
export JOLT_AOT_CACHE=0 JOLT_NO_USER_DEPS=1
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
task_deps='{:paths ["src" "test" "research/shape-projection-20261020" "'"$task_fixture"'/test" "'"$task_fixture"'/bench"] :deps {io.github.casselc/otel {:git/url "https://github.com/casselc/otel.git" :git/sha "3366a3322c3ea4024057f6278e50c999864171e8" :exclusions [jolt-lang/jolt-crypto]} io.github.chucklehead-dev/jolt-codec {:git/url "https://github.com/chucklehead-dev/jolt-otel-clickhouse.git" :git/sha "7ab2bedcb2e1845efdf6ed5c55ae9d2164338a8d" :deps/root "incubator/codec"}}}'
mkdir -p "$task_out"
sha256sum research/projection-phases-20261020/screen.clj \
  research/shape-projection-20261020/shape_projection.ss \
  research/shape-projection-20261020/shape_projection.clj \
  research/shape-projection-20261020/shape_projection_test.clj \
  research/shape-projection-20261020/run-rich-abba.sh \
  src/otel/exporter/chdb.clj src/otel/exporter/chdb/attribute_projection.clj \
  "$task_fixture/bench/otel/exporter/chdb_ordinary_transport_benchmark.clj" \
  "$task_fixture/bench/otel/exporter/chdb_benchmark.clj" \
  "$task_sdk/resources/otel/scalar_string_map.ss" \
  "$task_sdk/resources/otel/sorted_scalar_string_map.ss" \
  "$task_sdk/resources/otel/payload_scalar_string_map_cache.ss" > "$task_out/sources.sha256"
for task_arm in 0-baseline 1-fixed-shape 2-fixed-shape 3-baseline; do
  task_mode=${task_arm#*-}
  timeout --signal=TERM --kill-after=5s 240s /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
    "$task_bin" -Srepro -Sdeps "$task_deps" research/projection-phases-20261020/screen.clj \
    "$task_out/$task_arm.edn" 3366a3322c3ea4024057f6278e50c999864171e8 "$task_mode" \
    > "$task_out/$task_arm.log" 2>&1
  sha256sum -c "$task_out/sources.sha256" > "$task_out/$task_arm.source-after.log"
done
printf '%s\n' 'terminal0: fixed-shape rich projection ABBA complete'
