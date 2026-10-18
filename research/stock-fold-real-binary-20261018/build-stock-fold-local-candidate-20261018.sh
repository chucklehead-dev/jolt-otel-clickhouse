#!/usr/bin/env bash
set -euo pipefail
task_source=/home/chuck/ai-src/worktrees/jolt-qualified-stock-sorted-fold-20261018
task_evidence=/home/chuck/ai-src/evidence
task_artifact=$task_evidence/jolt-stock-fold-local-candidate-20261018
cd "$task_source"
test "$(git rev-parse HEAD)" = 631984d66445b8b123f2cfa154fc6442cf468fd4
test "$(git rev-parse HEAD^{tree})" = 79cd95a4fbee07670da584d0b1c0fd201581f05e
test -z "$(git status --porcelain)"
test ! -e "$task_artifact"
test ! -e "$task_evidence/stock-fold-local-candidate-build-20261018.log"
task_build=$(mktemp -d /tmp/stock-fold-local-build-20261018.XXXXXXXX)
printf 'owned_build_root=%s\n' "$task_build"
git ls-files -z host jolt-core stdlib tools/version.sh | xargs -0 sha256sum > "$task_evidence/stock-fold-local-candidate-source-20261018.sha256"
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 /home/chuck/ai-src/tools/chez-10.4.1 \
  --script host/chez/build-jolt.ss release "$task_build/jolt" > "$task_evidence/stock-fold-local-candidate-build-20261018.log" 2>&1
sha256sum -c "$task_evidence/stock-fold-local-candidate-source-20261018.sha256" > "$task_evidence/stock-fold-local-candidate-source-parity-20261018.log"
test -z "$(git status --porcelain)"
test "$(/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 "$task_build/jolt" --version)" = 'jolt v0.8.21-59-g631984d6'
mkdir "$task_artifact"
cp "$task_build/jolt" "$task_artifact/jolt"
cmp "$task_build/jolt" "$task_artifact/jolt"
sha256sum "$task_artifact/jolt" > "$task_evidence/stock-fold-local-candidate-artifact-20261018.sha256"
printf 'local_candidate=%s\nowned_build_root=%s\n' "$task_artifact/jolt" "$task_build"
# Main must verify terminal ownership and candidate receipts before removing
# this exact intermediate build root. This is NOT hosted full-gate qualification.
