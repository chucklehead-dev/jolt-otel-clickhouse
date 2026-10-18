#!/usr/bin/env bash
set -euo pipefail
# Local provisional candidate, not a substitute for the hosted artifact gate.
task_jolt=/home/chuck/ai-src/evidence/jolt-stock-fold-local-candidate-20261018/jolt
test "$(sha256sum "$task_jolt" | cut -d ' ' -f1)" = 64272bbb25330b00481486a52db17b90d6aff27bef84966bdac9bb8d49b3dd22
exec env JOLT_AOT_CACHE=0 JOLT_NO_USER_DEPS=1 \
  /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 "$task_jolt" "$@"
