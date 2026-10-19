#!/usr/bin/env bash
set -euo pipefail
task_here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
task_jolt=${FFC_JOLT:-/home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt}
task_wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
task_reference=/home/chuck/.jolt/gitlibs/https___github.com_clojure_data.json.git/1fce9070ee851f49a6c4ce6e1c0bb0db4d7594a0/src/main/clojure
task_time=/home/chuck/.jolt/gitlibs/https___github.com_jolt-lang_time.git/33c5e9831c046cfcbe68bc0c9c33e92568eaebb1
test "$(sha256sum "$task_jolt" | cut -d ' ' -f1)" = 7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94
export JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0
mkdir -p "$task_here/results"
cd "$task_here"
"$task_wrapper" "$task_jolt" --version > results/runtime.txt
"$task_wrapper" "$task_jolt" -Srepro -Sdeps '{:paths [] :deps {}}' -Sdescribe >> results/runtime.txt
sha256sum traversal.ss json.ss probe.clj oracle.clj check-oracle.bb benchmark.clj run.sh > results/sources.sha256
timeout --signal=TERM --kill-after=5s 60s "$task_wrapper" "$task_jolt" -Srepro -Sdeps '{:paths [] :deps {}}' probe.clj "$task_here" > results/probe.log 2>&1
task_oracle=$(mktemp /tmp/ffc-json-oracle-20261019.XXXXXXXX.edn)
trap 'rm -f -- "$task_oracle"' EXIT
timeout --signal=TERM --kill-after=5s 60s "$task_wrapper" "$task_jolt" -Srepro -Sdeps '{:paths [] :deps {}}' oracle.clj "$task_here" > "$task_oracle"
bb check-oracle.bb "$task_oracle" > results/oracle.log
timeout --signal=TERM --kill-after=5s 150s "$task_wrapper" "$task_jolt" -Srepro -Sdeps "{:paths [\"$task_reference\"] :deps {io.github.jolt-lang/time {:local/root \"$task_time\"}}}" benchmark.clj "$task_here" "$task_here/results/component.edn" > results/component.log 2>&1
sha256sum -c results/sources.sha256 > results/source-stability.log
printf 'terminal0: probe, independent parser, component, unchanged-source checks\n'
