#!/usr/bin/env bash
set -euo pipefail

# The resolver may leave one cache marker at a fetched dependency root.  Do not
# treat a generally clean-looking checkout as enough: an ignored source file
# can still shadow a loaded namespace.  Read porcelain records as NUL-delimited
# data so unusual filenames cannot make a second status entry look permitted.
dependency_source_clean() {
  local source_root=$1 receipt_file= record accepted=0
  receipt_file=$(mktemp "${TMPDIR:-/tmp}/exporter-dependency-status.XXXXXX") || return 1
  if ! git -C "$source_root" -c core.quotepath=true status --porcelain=v1 -z \
      --untracked-files=all --ignored=matching >"$receipt_file"; then
    rm -f "$receipt_file"
    return 1
  fi
  while IFS= read -r -d '' record; do
    if (( accepted == 0 )) && [[ "$record" == '?? .jolt-git-ok' ]]; then
      accepted=1
    else
      rm -f "$receipt_file"
      return 1
    fi
  done <"$receipt_file"
  rm -f "$receipt_file"
  return 0
}

reviewed_source_driver() {
  local driver reviewed_revision
  driver=$(realpath "${JOLT_CHDB_SOURCE_ROOT:?set the reviewed driver checkout}")
  reviewed_revision=${JOLT_EXPECTED_DRIVER_REV:?set the exact reviewed driver revision}
  [[ "$reviewed_revision" =~ ^[a-f0-9]{40}$ ]]
  [[ "$driver" != *'"'* && "$driver" != *'\'* && "$driver" != *$'\n'* ]]
  dependency_source_clean "$driver"
  [[ "$(git -C "$driver" rev-parse HEAD)" == "$reviewed_revision" ]]
  printf '%s\n' "$driver"
}

checked_reviewed_source() {
  local script=$1 fixture=$2 revision=
  revision=$(git -C "$fixture" rev-parse HEAD)
  JOLT_CHDB_SOURCE_ROOT="$fixture" JOLT_EXPECTED_DRIVER_REV="$revision" \
    bash "$script" --check-reviewed-source
}

run_cleanliness_controls() {
  local script fixture=
  script=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/$(basename "${BASH_SOURCE[0]}")
  fixture=$(mktemp -d "${TMPDIR:-/tmp}/exporter-dependency-cleanliness.XXXXXX") || return 1
  trap 'rm -rf "$fixture"' RETURN
  git init -q "$fixture"
  git -C "$fixture" config user.name qualification
  git -C "$fixture" config user.email qualification@example.invalid
  mkdir -p "$fixture/jdbc"
  printf '%s\n' 'tracked fixture' >"$fixture/jdbc/chdb.clj"
  printf '%s\n' 'jdbc/*.clj' >"$fixture/.gitignore"
  git -C "$fixture" add -f jdbc/chdb.clj .gitignore
  git -C "$fixture" commit -qm fixture

  dependency_source_clean "$fixture" || { echo cleanliness-control-clean-failed; return 1; }
  checked_reviewed_source "$script" "$fixture" || { echo reviewed-source-control-clean-failed; return 1; }
  : >"$fixture/.jolt-git-ok"
  dependency_source_clean "$fixture" || { echo cleanliness-control-sentinel-failed; return 1; }
  checked_reviewed_source "$script" "$fixture" || { echo reviewed-source-control-sentinel-failed; return 1; }
  : >"$fixture/untracked.txt"
  if checked_reviewed_source "$script" "$fixture"; then
    echo reviewed-source-control-untracked-accepted
    return 1
  fi
  rm -f "$fixture/untracked.txt"
  : >"$fixture/jdbc/injected.clj"
  if checked_reviewed_source "$script" "$fixture"; then
    echo reviewed-source-control-ignored-local-jdbc-source-accepted
    return 1
  fi
  rm -f "$fixture/jdbc/injected.clj"
  printf '%s\n' 'modified fixture' >"$fixture/jdbc/chdb.clj"
  if checked_reviewed_source "$script" "$fixture"; then
    echo reviewed-source-control-tracked-change-accepted
    return 1
  fi
  printf '%s\n' 'reviewed-source-cleanliness-controls-qualified'
}

if [[ "${1:-}" == --self-test-cleanliness ]]; then
  run_cleanliness_controls
  exit $?
fi

# Keep the existing narrow shell control entrypoint, but run the stricter
# NUL-delimited receipt implementation that also rejects ignored source.
if [[ "${1:-}" == --check-provider-cleanliness ]]; then
  [[ "$#" == 2 ]] || exit 1
  dependency_source_clean "$2"
  exit
fi

if [[ "${1:-}" == --check-reviewed-source ]]; then
  [[ "$#" == 1 ]] || exit 1
  reviewed_source_driver >/dev/null
  exit
fi

crash_reopen=false
case "${1:-}" in
  '') [[ "$#" == 0 ]] || exit 64 ;;
  --wal-crash-reopen) [[ "$#" == 1 ]] || exit 64; crash_reopen=true ;;
  *) echo invalid-native-gate-argument >&2; exit 64 ;;
esac

# Linux-only bounded acceptance lane. Run under an outer 210s timeout.
[[ "$(uname -s)" == Linux ]] || { echo unsupported-process-ownership-host; exit 1; }
worktree=$(cd "$(dirname "$0")/.." && pwd -P)
fixture="$worktree/test/otel/exporter/chdb_durable_typed_native_test.clj"
jolt=${JOLT_BIN:?set the exact qualified Jolt binary}
wrapper=${JOLT_WRAPPER:?set the mandatory Chez 10.4.1 launcher}
lib=$(realpath "${JOLT_CHDB_LIB:?set the qualified chDB 26.7.3 library}")
header=$(realpath "${JOLT_CHDB_HEADER:?set the matching chDB header}")
[[ "$(dirname "$lib")" == "$(dirname "$header")" ]] || { echo mismatched-native-pair-directory; exit 1; }
driver_mode=${JOLT_DURABLE_DRIVER_MODE:-root-pin}
case "$driver_mode" in root-pin|reviewed-source) ;; *) echo invalid-driver-mode; exit 1 ;; esac
binary_sha=${JOLT_EXPECTED_BINARY_SHA256:?set selected-binary SHA256}
wrapper_sha=${JOLT_EXPECTED_WRAPPER_SHA256:?set qualified wrapper SHA256}
library_sha=${JOLT_CHDB_EXPECTED_LIBRARY_SHA256:?set qualified library SHA256}
header_sha=${JOLT_CHDB_EXPECTED_HEADER_SHA256:?set matching header SHA256}
for checksum in "$binary_sha" "$wrapper_sha" "$library_sha" "$header_sha"; do
  [[ "$checksum" =~ ^[a-f0-9]{64}$ ]] || { echo invalid-integrity-checksum; exit 1; }
done
jolt=$(realpath "$jolt")
wrapper=$(realpath "$wrapper")
[[ -x "$jolt" && -x "$wrapper" ]]
printf '%s  %s\n' "$binary_sha" "$jolt" "$wrapper_sha" "$wrapper" \
  "$library_sha" "$lib" "$header_sha" "$header" | sha256sum -c -
if pgrep -x jolt >/dev/null; then echo runtime-slot-occupied; exit 1; fi
root=$(mktemp -d "${TMPDIR:-/tmp}/exporter-durable-native.XXXXXX")
mkdir -p "$root/objects" "$root/scratch-writer" "$root/scratch-reader" "$root/cache"
echo "EVIDENCE_ROOT=$root"
task_token="durable-native-${root##*/}"
writer_pid= writer_start=

own_writer() {
  local -a argv=()
  [[ -n "$writer_pid" && -r "/proc/$writer_pid/stat" ]] || return 1
  [[ "$(awk '{print $22}' "/proc/$writer_pid/stat")" == "$writer_start" ]] || return 1
  [[ "$(awk '{print $4}' "/proc/$writer_pid/stat")" == "$$" ]] || return 1
  [[ "$(awk '{print $5}' "/proc/$writer_pid/stat")" == "$writer_pid" ]] || return 1
  [[ "$(awk '{print $6}' "/proc/$writer_pid/stat")" == "$writer_pid" ]] || return 1
  [[ -f "$root/writer-receipt" ]] || return 1
  [[ "$(cat "$root/writer-receipt")" == "$(awk '{print $1, $4, $5, $6, $22}' "/proc/$writer_pid/stat")" ]] || return 1
  [[ "$(readlink "/proc/$writer_pid/exe")" == "$jolt" ]] || return 1
  [[ "$(readlink "/proc/$writer_pid/cwd")" == "$worktree" ]] || return 1
  mapfile -d '' -t argv < "/proc/$writer_pid/cmdline" || return 1
  [[ "${argv[0]:-}" == "$jolt" && "${argv[1]:-}" == -Srepro ]] || return 1
  [[ "${argv[${#argv[@]}-1]:-}" == "$writer_form" ]] || return 1
  tr '\0' '\n' <"/proc/$writer_pid/environ" | grep -Fxq "JOLT_DURABLE_DRAFT_TOKEN=$task_token" || return 1
}
cleanup() {
  local original=$? incomplete=0
  trap - EXIT INT TERM
  if [[ -n "$writer_pid" ]]; then
    if own_writer; then
      kill -TERM -- "-$writer_pid" || incomplete=1
      for _ in {1..30}; do
        [[ -r "/proc/$writer_pid/stat" ]] || break
        [[ "$(awk '{print $3}' "/proc/$writer_pid/stat")" == Z ]] && break
        sleep 0.1
      done
      if own_writer && [[ "$(awk '{print $3}' "/proc/$writer_pid/stat")" != Z ]]; then
        kill -KILL -- "-$writer_pid" || incomplete=1
      fi
      wait "$writer_pid" 2>/dev/null || true
    elif kill -0 "$writer_pid" 2>/dev/null || kill -0 -- "-$writer_pid" 2>/dev/null; then
      incomplete=1 # Unconfirmed live PID or surviving group: do not signal.
    fi
    kill -0 -- "-$writer_pid" 2>/dev/null && incomplete=1
  fi
  (( incomplete == 0 )) || { echo owned-cleanup-incomplete; exit 1; }
  exit "$original"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
cd "$worktree"
driver_options=()
if [[ "$driver_mode" == reviewed-source ]]; then
  driver=$(reviewed_source_driver)
  reviewed_revision=$JOLT_EXPECTED_DRIVER_REV
  driver_options=(-Sdeps "{:deps {io.github.chucklehead-dev/jolt-chdb {:local/root \"$driver\"}}}")
fi
printf '%s\n' "$driver_mode" >"$root/driver-mode.txt"
git rev-parse HEAD >"$root/exporter-source.txt"
sha256sum "$fixture" >"$root/fixture-source.sha256"
launch=(env -i HOME="${HOME:?}" PATH="$(dirname "$jolt"):${HOME}/.local/bin:/usr/local/bin:/usr/bin:/bin"
        LANG=C.UTF-8 JOLT_CHDB_LIB="$lib" JOLT_CACHE_DIR="$root/cache"
        JOLT_DURABLE_DRAFT_ROOT="$root" JOLT_DURABLE_DRAFT_TOKEN="$task_token"
        OSCOPE_DURABLE_NATIVE_FIXTURE="$fixture")
if [[ -n "${JOLT_GITLIBS_DIR:-}" ]]; then
  launch+=(JOLT_GITLIBS_DIR="$JOLT_GITLIBS_DIR")
fi
runtime=("$wrapper" "$jolt" -Srepro "${driver_options[@]}" -A:test -e)
timeout --signal=TERM --kill-after=5s 30s "${launch[@]}" bash -c '
  wrapper=$1; binary=$2; shift 2
  "$wrapper" "$binary" -Srepro "$@" -A:test -Spath > "$JOLT_DURABLE_DRAFT_ROOT/classpath.txt"
  "$wrapper" "$binary" -Srepro -A:test -e "(require (quote clojure.edn)) (let [d ((ns-resolve (quote clojure.edn) (quote read-string)) (slurp \"deps.edn\"))] (println (get-in d [:deps (quote io.github.chucklehead-dev/jolt-chdb) :git/sha])) (println (get-in d [:deps (quote io.github.casselc/otel) :git/sha])))" > "$JOLT_DURABLE_DRAFT_ROOT/declared-pins.txt"
' _ "$wrapper" "$jolt" "${driver_options[@]}"
mapfile -t declared <"$root/declared-pins.txt"
[[ "${#declared[@]}" == 2 && "${declared[0]}" =~ ^[a-f0-9]{40}$ && "${declared[1]}" =~ ^[a-f0-9]{40}$ ]]
[[ -z "${JOLT_EXPECTED_DRIVER_REV:-}" || "$driver_mode" != root-pin || "$JOLT_EXPECTED_DRIVER_REV" == "${declared[0]}" ]]
mapfile -t classpaths <"$root/classpath.txt"
[[ "${#classpaths[@]}" == 1 ]]
IFS=: read -r -a source_roots <<< "${classpaths[0]}"
driver_roots=(); sdk_roots=()
for source_root in "${source_roots[@]}"; do
  [[ -f "$source_root/jdbc/chdb.clj" ]] && driver_roots+=("$(realpath "$source_root")")
  [[ -f "$source_root/otel/sdk/metrics.clj" ]] && sdk_roots+=("$(realpath "$source_root")")
done
[[ "${#driver_roots[@]}" == 1 && "${#sdk_roots[@]}" == 1 ]] || { echo ambiguous-source-provider; exit 1; }
driver_revision=$(git -C "${driver_roots[0]}" rev-parse HEAD)
sdk_revision=$(git -C "${sdk_roots[0]}" rev-parse HEAD)
# Only Jolt's one exact untracked cache sentinel may appear.  This includes
# ignored paths in the receipt: otherwise an ignored .clj file could shadow
# the resolved provider while the revision proof still appeared clean.
dependency_source_clean "${driver_roots[0]}" && dependency_source_clean "${sdk_roots[0]}"
if [[ "$driver_mode" == root-pin ]]; then expected_driver=${declared[0]}; else expected_driver=$reviewed_revision; fi
[[ "$driver_revision" == "$expected_driver" && "$sdk_revision" == "${declared[1]}" ]] || { echo source-pin-mismatch; exit 1; }
printf '%s\n' "$driver_revision" >"$root/driver-source.txt"
printf '%s\n' "$sdk_revision" >"$root/sdk-source.txt"
timeout --signal=TERM --kill-after=5s 30s "${launch[@]}" "${runtime[@]}" '(try (require (quote jdbc.chdb)) (when-not (fn? (some-> (ns-resolve (quote jdbc.chdb) (quote insert-json-rows!)) deref)) (println :required-driver-row-api-missing) (System/exit 1)) (println :source-provenance-qualified) (catch Throwable _ (println :source-provenance-failed) (System/exit 1)))' >"$root/provenance.log" 2>&1
[[ "$(grep -Fxc ':source-provenance-qualified' "$root/provenance.log")" == 1 ]]
writer_form='(try (require (quote jdbc.chdb)) (when-not (ns-resolve (quote jdbc.chdb) (quote insert-json-rows!)) (println :required-driver-row-api-missing) (System/exit 1)) (load-file (System/getenv "OSCOPE_DURABLE_NATIVE_FIXTURE")) ((ns-resolve (quote otel.exporter.chdb-durable-typed-native-test) (quote -main)) "writer" (System/getenv "JOLT_DURABLE_DRAFT_ROOT")) (catch Throwable _ (println :safe-durable-writer-launch-failed) (System/exit 1)))'
reader_form='(try (require (quote jdbc.chdb)) (when-not (ns-resolve (quote jdbc.chdb) (quote insert-json-rows!)) (println :required-driver-row-api-missing) (System/exit 1)) (load-file (System/getenv "OSCOPE_DURABLE_NATIVE_FIXTURE")) ((ns-resolve (quote otel.exporter.chdb-durable-typed-native-test) (quote -main)) "reader" (System/getenv "JOLT_DURABLE_DRAFT_ROOT")) (catch Throwable _ (println :safe-durable-reader-launch-failed) (System/exit 1)))'
setsid "${launch[@]}" bash -c 'awk "{print \$1, \$4, \$5, \$6, \$22}" "/proc/$$/stat" > "$JOLT_DURABLE_DRAFT_ROOT/writer-receipt"; exec "$@"' _ \
  "${runtime[@]}" "$writer_form" >"$root/writer.log" 2>&1 &
writer_pid=$!
[[ -r "/proc/$writer_pid/stat" ]]
writer_start=$(awk '{print $22}' "/proc/$writer_pid/stat")
deadline=$((SECONDS + 5))
until own_writer; do
  [[ -r "/proc/$writer_pid/stat" ]] || { echo writer-identity-establishment-failed; exit 1; }
  (( SECONDS < deadline )) || { echo writer-identity-establishment-deadline; exit 1; }
  sleep 0.05
done
deadline=$((SECONDS + 90))
until [[ -f "$root/wal-ready" ]]; do
  own_writer || { echo writer-before-readiness-failed; exit 1; }
  (( SECONDS < deadline )) || { echo writer-readiness-deadline; exit 1; }
  sleep 0.1
done
if "$crash_reopen"; then
  # The writer has published a typed batch and is parked on its test-only
  # reader handshake. Kill only the verified, task-owned process group, then
  # reopen from persisted WAL in a new reader process. Never use a guessed PID.
  own_writer || { echo writer-before-crash-identity-failed; exit 1; }
  [[ "$(cat "$root/wal-ready")" == ready ]] || { echo writer-wal-readiness-invalid; exit 1; }
  kill -KILL -- "-$writer_pid"
  set +e
  wait "$writer_pid"
  crash_status=$?
  set -e
  [[ "$crash_status" == 137 ]] || { echo writer-crash-status-unexpected; exit 1; }
  kill -0 -- "-$writer_pid" 2>/dev/null && { echo writer-group-residual; exit 1; }
  printf 'verified-writer-sigkill status=%s\n' "$crash_status" > "$root/crash-receipt.txt"
  writer_pid=
fi
timeout --signal=TERM --kill-after=5s 90s "${launch[@]}" "${runtime[@]}" "$reader_form" >"$root/reader.log" 2>&1
[[ -f "$root/reader-done" ]]
if ! "$crash_reopen"; then
  deadline=$((SECONDS + 10))
  while [[ -r "/proc/$writer_pid/stat" ]] && [[ "$(awk '{print $3}' "/proc/$writer_pid/stat")" != Z ]]; do
    (( SECONDS < deadline )) || { echo writer-close-deadline; exit 1; }
    sleep 0.1
  done
  wait "$writer_pid"
  kill -0 -- "-$writer_pid" 2>/dev/null && { echo writer-group-residual; exit 1; }
  writer_pid=
fi
sha256sum "$root/writer.log" "$root/reader.log"
if "$crash_reopen"; then
  echo durable-typed-wal-crash-replay-qualified
else
  echo durable-typed-native-qualified
fi
