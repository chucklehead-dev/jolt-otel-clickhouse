#!/usr/bin/env bash
# No branch/latest fallback. Fill these pins only from an actually qualified
# producer run; version strings and an archive's own checksums are not authority.
set -euo pipefail
unset GH_DEBUG GH_TRACE

repo=casselc/jolt
compiler=aea91781bbab68bf174fef4a689bb00dcf834ded
compiler_tree=a31de1596fcabd0e45fbcbc528842805acea0ee7
artifact_name=durable-runtime-aea91781-linux-x64
workflow_path=.github/workflows/durable-runtime-artifact.yml
for name in QUALIFIED_RUNTIME_RUN_ID QUALIFIED_RUNTIME_RUN_ATTEMPT QUALIFIED_RUNTIME_ARTIFACT_ID; do
  value=${!name:-}
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || { echo "qualified-runtime-invalid-numeric-pin:$name" >&2; exit 1; }
done
[[ "${QUALIFIED_RUNTIME_WORKFLOW_SHA:-}" =~ ^[a-f0-9]{40}$ ]] || { echo qualified-runtime-invalid-workflow-pin >&2; exit 1; }
for name in QUALIFIED_RUNTIME_ARTIFACT_SHA256 QUALIFIED_RUNTIME_BINARY_SHA256; do
  value=${!name:-}
  [[ "$value" =~ ^[a-f0-9]{64}$ ]] || { echo "qualified-runtime-invalid-checksum-pin:$name" >&2; exit 1; }
done
command -v gh >/dev/null
command -v jq >/dev/null
command -v unzip >/dev/null
root=$(mktemp -d "${TMPDIR:-/tmp}/qualified-durable-runtime.XXXXXXXX")
printf 'QUALIFIED_RUNTIME_EVIDENCE_ROOT=%s\n' "$root"

# Authentication is supplied by the caller, never embedded or printed here.
# Public artifact download permissions must be proved by a real consumer run;
# absent permission/expired artifacts fail, not an alternate compiler selection.
gh api --hostname github.com "repos/$repo/actions/runs/$QUALIFIED_RUNTIME_RUN_ID" > "$root/run.json"
jq -e --arg sha "$QUALIFIED_RUNTIME_WORKFLOW_SHA" \
  --arg path "$workflow_path" --argjson attempt "$QUALIFIED_RUNTIME_RUN_ATTEMPT" '
  .repository.full_name == "casselc/jolt" and
  .head_repository.full_name == "casselc/jolt" and
  .head_sha == $sha and .path == $path and
  .run_attempt == $attempt and .event == "push" and
  .head_branch == "integration/aspects" and
  .status == "completed" and .conclusion == "success"' "$root/run.json" > /dev/null
gh api --hostname github.com "repos/$repo/actions/artifacts/$QUALIFIED_RUNTIME_ARTIFACT_ID" > "$root/artifact.json"
jq -e --arg name "$artifact_name" --arg sha "$QUALIFIED_RUNTIME_WORKFLOW_SHA" \
  --arg digest "sha256:$QUALIFIED_RUNTIME_ARTIFACT_SHA256" \
  --argjson run "$QUALIFIED_RUNTIME_RUN_ID" --argjson id "$QUALIFIED_RUNTIME_ARTIFACT_ID" '
  .id == $id and .name == $name and .expired == false and
  .digest == $digest and .workflow_run.id == $run and
  .workflow_run.head_sha == $sha and
  .workflow_run.head_repository_id == 1310562894' "$root/artifact.json" > /dev/null
gh api --hostname github.com "repos/$repo/actions/artifacts/$QUALIFIED_RUNTIME_ARTIFACT_ID/zip" > "$root/artifact.zip"
printf '%s  %s\n' "$QUALIFIED_RUNTIME_ARTIFACT_SHA256" "$root/artifact.zip" | sha256sum -c -

# Read exact members without general extraction: traversal/symlink paths and
# duplicate members cannot become executable files or concatenate silently.
unzip -Z1 "$root/artifact.zip" > "$root/members.txt"
test "$(wc -l < "$root/members.txt")" = 3
test "$(sort "$root/members.txt")" = "$(printf '%s\n' SHA256SUMS build-manifest.txt jolt)"
for member in jolt build-manifest.txt SHA256SUMS; do
  unzip -p "$root/artifact.zip" "$member" > "$root/$member"
done
printf '%s  %s\n' "$QUALIFIED_RUNTIME_BINARY_SHA256" "$root/jolt" | sha256sum -c -
expected_sums=$(cd "$root" && sha256sum jolt build-manifest.txt)
test "$(cat "$root/SHA256SUMS")" = "$expected_sums"
require_manifest_line() {
  test "$(grep -Fxc -- "$1" "$root/build-manifest.txt")" = 1
}
require_manifest_line schema=1
require_manifest_line "repository=$repo"
require_manifest_line "compiler_source=$compiler"
require_manifest_line "compiler_tree=$compiler_tree"
require_manifest_line "workflow_source=$QUALIFIED_RUNTIME_WORKFLOW_SHA"
require_manifest_line "workflow_path=$workflow_path"
require_manifest_line "run_id=$QUALIFIED_RUNTIME_RUN_ID"
require_manifest_line "run_attempt=$QUALIFIED_RUNTIME_RUN_ATTEMPT"
require_manifest_line "binary_sha256=$QUALIFIED_RUNTIME_BINARY_SHA256"
require_manifest_line chez_version=10.4.1
require_manifest_line runner_os=Linux
require_manifest_line runner_arch=X64
require_manifest_line require_buildlib=1
require_manifest_line gate=pass
require_manifest_line ranged_append_ascii=98

# Artifact ZIP transport does not retain executable mode. Validate bytes first.
chmod 755 "$root/jolt"
timeout --signal=TERM --kill-after=5s 30s "$root/jolt" -Srepro -e '
  (let [out (java.io.ByteArrayOutputStream.)
        writer (java.io.OutputStreamWriter. out "UTF-8")]
    (.append writer "abc" 1 2)
    (.flush writer)
    (assert (= [98] (vec (.toByteArray out))))
    (println :qualified-runtime-ranged-append-pass))'
printf 'QUALIFIED_RUNTIME_BIN=%s\n' "$root/jolt"
