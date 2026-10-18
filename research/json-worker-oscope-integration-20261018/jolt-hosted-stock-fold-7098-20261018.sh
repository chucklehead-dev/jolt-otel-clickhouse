#!/usr/bin/env bash
set -euo pipefail
task_jolt=/home/chuck/ai-src/evidence/jolt-runtime-stock-fold-7098-artifact-20261018.DQfwReHc/jolt
test "$(sha256sum "$task_jolt" | cut -d ' ' -f1)" = 75c47a6d30cc8f00e53230123383c078222bdfeda790c2660dbecfd399e08798
exec env JOLT_AOT_CACHE=0 JOLT_NO_USER_DEPS=1 \
  /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 "$task_jolt" "$@"
