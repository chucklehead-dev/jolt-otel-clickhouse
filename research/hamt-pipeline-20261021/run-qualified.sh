#!/usr/bin/env bash
# Research only. One release binary; recover each arm before retiring its stores.
set -euo pipefail
umask 077
research=$(cd -- "$(dirname -- "$0")" && pwd)
qualification=$(cd -- "$research/../.." && pwd)
run_root=${1:?absolute existing evidence root containing app and source-before.sha256}
[[ "$run_root" == /home/chuck/ai-src/evidence/hamt-pipeline-release-20261021.* ]]
[[ -d "$run_root" && ! -L "$run_root" && -x "$run_root/app" ]]
run_root=$(realpath -e -- "$run_root")
export JOLT_NO_USER_DEPS=1 JOLT_AOT_CACHE=0
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
jolt_bin=/home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt
(cd "$qualification" && sha256sum -c "$run_root/source-before.sha256") > "$run_root/source-check-before.log"
sha256sum "$run_root/app" "$jolt_bin" "$JOLT_CHDB_LIB" "$wrapper" > "$run_root/binaries.sha256"
(cd "$research/reader" && "$wrapper" "$jolt_bin" -Srepro -Spath) > "$run_root/reader-classpath.txt"
recover() {
  local store=$1 label=$2 phase
  for phase in oracle actual expected compare; do
    (cd "$research/reader" && "$wrapper" "$jolt_bin" -Srepro -m perf.sdk-preparation-recovery "$store" "$phase") > "$run_root/$label.reader-$phase.log" 2>&1
  done
  cp -- "$store/writer.edn" "$run_root/$label.writer.edn"
  cp -- "$store/hamt-mode.edn" "$run_root/$label.hamt-mode.edn"
  cp -- "$store/reader.edn" "$run_root/$label.reader.edn"
  cp -- "$store/actual-physical.edn" "$run_root/$label.actual-physical.edn"
  cp -- "$store/oracle-physical.edn" "$run_root/$label.oracle-physical.edn"
  bash /home/chuck/ai-src/tools/retain-run-artifacts prune-success "$run_root" "$label/objects" "$label/scratch-writer" "$label/scratch-reader" "$label/oracle-independent"
}
# Tiny native smoke and full physical recovery before timed qualification.
if [[ ! -f "$run_root/smoke-enabled/writer.edn" ]]; then
  "$run_root/app" "$run_root/smoke-enabled" enabled 8 2 1 release > "$run_root/smoke-enabled.writer.log" 2>&1
fi
if [[ ! -f "$run_root/smoke-enabled/reader.edn" ]]; then
  recover "$run_root/smoke-enabled" smoke-enabled
fi
for entry in 1-disabled 2-enabled 3-enabled 4-disabled; do
  mode=${entry#*-}
  printf 'starting %s\n' "$entry"
  "$run_root/app" "$run_root/$entry" "$mode" 5000 32 3 release > "$run_root/$entry.writer.log" 2>&1
  # Check inputs immediately after timed writer, before any recovery work.
  (cd "$qualification" && sha256sum -c "$run_root/source-before.sha256") > "$run_root/$entry.source-check.log"
  recover "$run_root/$entry" "$entry"
  printf 'qualified %s\n' "$entry"
done
sha256sum -c "$run_root/binaries.sha256" > "$run_root/binary-check-after.log"
printf 'all-arms-terminal-and-recovered\n'
