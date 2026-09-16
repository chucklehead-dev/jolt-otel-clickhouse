#!/usr/bin/env bash
set -euo pipefail
[[ "$(uname -s)" == Linux ]] || { echo unsupported-benchmark-host; exit 1; }
script=$(realpath "$0")
worktree=$(cd "$(dirname "$script")/.." && pwd -P)
fixture="$worktree/bench/otel/exporter/chdb_ordinary_transport_benchmark.clj"
jolt=$(realpath "${JOLT_BIN:?set qualified Jolt}")
wrapper=$(realpath "${JOLT_WRAPPER:?set Chez 10.4.1 wrapper}")
lib=$(realpath "${JOLT_CHDB_LIB:?set qualified library}")
header=$(realpath "${JOLT_CHDB_HEADER:?set matched header}")
driver=$(realpath "${JOLT_CHDB_SOURCE_ROOT:?set clean reviewed driver until merged row API pin}")
driver_rev=${JOLT_EXPECTED_DRIVER_REV:?set reviewed full driver commit}
[[ "$driver_rev" =~ ^[a-f0-9]{40}$ ]]
[[ "$(dirname "$lib")" == "$(dirname "$header")" ]]
[[ "$driver" != *'"'* && "$driver" != *'\'* && "$driver" != *$'\n'* ]]
[[ -x "$jolt" && -x "$wrapper" ]]
for spec in "${JOLT_EXPECTED_BINARY_SHA256:?}:$jolt" "${JOLT_EXPECTED_WRAPPER_SHA256:?}:$wrapper" "${JOLT_CHDB_EXPECTED_LIBRARY_SHA256:?}:$lib" "${JOLT_CHDB_EXPECTED_HEADER_SHA256:?}:$header"; do
  hash=${spec%%:*}; path=${spec#*:}
  [[ "$hash" =~ ^[a-f0-9]{64}$ ]]
  printf '%s  %s\n' "$hash" "$path" | sha256sum -c -
done
[[ "$(git -C "$driver" rev-parse HEAD)" == "$driver_rev" ]]
[[ -z "$(git -C "$driver" status --porcelain=v1)" ]]
if pgrep -x jolt >/dev/null; then echo runtime-slot-occupied; exit 1; fi
if [[ "${1:-}" != --bounded-inner ]]; then
  outer=$(mktemp -d "${TMPDIR:-/tmp}/ordinary-transport-outer.XXXXXXXX")
  echo "OUTER_EVIDENCE_ROOT=$outer"
  set +e
  /usr/bin/time -v -o "$outer/resources.txt" timeout --signal=TERM --kill-after=5s 360s bash "$script" --bounded-inner > "$outer/outer.log" 2>&1
  status=$?
  set -e
  printf '%s\n' "$status" > "$outer/exit-status.txt"
  sha256sum "$outer/outer.log" "$outer/resources.txt"
  exit "$status"
fi
root=$(mktemp -d "${TMPDIR:-/tmp}/ordinary-transport-abba.XXXXXXXX")
echo "EVIDENCE_ROOT=$root"
cd "$worktree"
git rev-parse HEAD > "$root/exporter-source.txt"
git -C "$driver" rev-parse HEAD > "$root/driver-source.txt"
sha256sum "$fixture" "$script" > "$root/harness-source.sha256"
git status --porcelain=v1 > "$root/status.before"
sha256sum src/otel/exporter/chdb.clj deps.edn > "$root/loaded-source.before.sha256"
options=(-Sdeps "{:deps {io.github.chucklehead-dev/jolt-chdb {:local/root \"$driver\"}}}")
expression='(try (require (quote jdbc.chdb)) (when-not (ns-resolve (quote jdbc.chdb) (quote insert-json-rows!)) (println :required-driver-row-api-missing) (System/exit 1)) (load-file (System/getenv "BENCH_FIXTURE")) ((ns-resolve (quote otel.exporter.chdb-ordinary-transport-benchmark) (quote -main)) (System/getenv "TASK_MODE") (System/getenv "TASK_ROUTE") (System/getenv "TASK_DB") "benchmark") (catch Throwable _ (println :ordinary-benchmark-launch-failed) (System/exit 1)))'
child() {
  local mode=$1 route=$2 db=$3 label=$4
  local cache
  cache=$(mktemp -d "$root/cache-$label.XXXXXXXX")
  /usr/bin/time -v -o "$root/$label.resources" timeout --signal=TERM --kill-after=5s 60s \
    env -i HOME="${HOME:?}" PATH="$(dirname "$jolt"):${HOME}/.local/bin:/usr/local/bin:/usr/bin:/bin" LANG=C.UTF-8 \
    JOLT_CACHE_DIR="$cache" JOLT_CHDB_LIB="$lib" BENCH_FIXTURE="$fixture" \
    TASK_MODE="$mode" TASK_ROUTE="$route" TASK_DB="$db" TASK_EXPRESSION="$expression" \
    TASK_CLASSPATH="$root/$label.classpath" \
    bash -c 'set -euo pipefail; "$@" -Spath > "$TASK_CLASSPATH"; "$@" -e "$TASK_EXPRESSION"' _ \
    "$wrapper" "$jolt" -Srepro "${options[@]}" -A:test > "$root/$label.log" 2>&1
}
for arm in A1 B1 B2 A2; do
  case "$arm" in A*) route=legacy ;; B*) route=candidate ;; esac
  db=$(mktemp -d "$root/storage-$arm.XXXXXXXX")
  child writer "$route" "$db" "$arm-writer"
  grep -Eq '^:writer-green :route :(legacy|candidate) :rows 25600$' "$root/$arm-writer.log"
  grep '^:payload-control' "$root/$arm-writer.log" > "$root/$arm.payload-controls"
  [[ "$(wc -l < "$root/$arm.payload-controls")" == 2 ]]
  child reader "$route" "$db" "$arm-reader"
  grep -Fxq ':fresh-reader-green :groups 1024 :rows 25600 :full-rows-equal true :exact-nanos true :typed-values-status true' "$root/$arm-reader.log"
done
for arm in B1 B2 A2; do cmp "$root/A1.payload-controls" "$root/$arm.payload-controls"; done
git status --porcelain=v1 > "$root/status.after"
sha256sum src/otel/exporter/chdb.clj deps.edn > "$root/loaded-source.after.sha256"
cmp "$root/status.before" "$root/status.after"
cmp "$root/loaded-source.before.sha256" "$root/loaded-source.after.sha256"
echo ORDINARY_TRANSPORT_ABBA_GREEN=no-p99-allocation-or-Durable-target-claim
sha256sum "$root"/*.log "$root"/*.resources "$root"/*.payload-controls
# Retain owned stores and safe aggregate logs; no automatic retries/deletion.
